package com.mangabox.sdstorage;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.util.Base64;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Offline book storage outside the app's internal folder.
 *
 * Two kinds of roots, both without any storage permission:
 * - the app folder on the removable SD card, getExternalFilesDirs(null)[1..] (plain java.io);
 * - a folder the user picked with the system folder picker (call option "tree", Storage Access Framework).
 *
 * @capacitor/filesystem treats any absolute path it does not recognise as public external storage
 * and demands READ/WRITE_EXTERNAL_STORAGE on Android 9-12, so the file I/O is done here instead.
 * Every path argument is relative to the root and may not escape it.
 */
@CapacitorPlugin(name = "SdStorage")
public class SdStoragePlugin extends Plugin {

    private static final String ERR_UNAVAILABLE = "SD_UNAVAILABLE";
    private static final String ERR_READONLY = "SD_READONLY";
    private static final String ERR_NOT_FOUND = "NOT_FOUND";
    private static final String ERR_BAD_PATH = "BAD_PATH";
    private static final String ERR_IO = "IO_ERROR";
    private static final String ERR_CANCELLED = "CANCELLED";

    //MBX SD card app folder

    /** First secondary volume app dir, preferring removable ones. Null when no card is present. */
    private File findSdRoot() {
        File[] dirs = getContext().getExternalFilesDirs(null);
        File fallback = null;
        for (int i = 1; i < dirs.length; i++) {
            File dir = dirs[i];
            if (dir == null) continue; // volume known but not mounted
            if (isRemovable(dir)) return dir;
            if (fallback == null) fallback = dir;
        }
        return fallback;
    }

    private static boolean isRemovable(File dir) {
        try {
            return Environment.isExternalStorageRemovable(dir);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String stateOf(File dir) {
        String state = Environment.getExternalStorageState(dir);
        if (Environment.MEDIA_MOUNTED.equals(state)) return "mounted";
        if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) return "mounted_ro";
        return "unavailable";
    }

    @PluginMethod
    public void getVolumes(PluginCall call) {
        JSObject ret = new JSObject();

        JSObject internal = new JSObject();
        File filesDir = getContext().getFilesDir();
        internal.put("path", filesDir.getAbsolutePath());
        internal.put("freeBytes", filesDir.getUsableSpace());
        internal.put("totalBytes", filesDir.getTotalSpace());
        ret.put("internal", internal);

        File root = findSdRoot();
        if (root != null) {
            JSObject sd = new JSObject();
            sd.put("path", root.getAbsolutePath());
            sd.put("state", stateOf(root));
            sd.put("removable", isRemovable(root));
            sd.put("freeBytes", root.getUsableSpace());
            sd.put("totalBytes", root.getTotalSpace());
            ret.put("sdcard", sd);
        } else {
            ret.put("sdcard", JSObject.NULL);
        }
        call.resolve(ret);
    }

    //MBX Picked folder

    @PluginMethod
    public void pickFolder(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION |
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        );
        startActivityForResult(call, intent, "pickFolderResult");
    }

    @ActivityCallback
    private void pickFolderResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        Intent data = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || data == null || data.getData() == null) {
            call.reject("No folder picked", ERR_CANCELLED);
            return;
        }
        Uri treeUri = data.getData();
        try {
            getContext().getContentResolver().takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
        } catch (SecurityException e) {
            call.reject("The folder cannot be used: " + e.getMessage(), ERR_READONLY);
            return;
        }
        call.resolve(folderInfo(new TreeStorage(getContext(), treeUri)));
    }

    private JSObject folderInfo(TreeStorage tree) {
        JSObject ret = new JSObject();
        ret.put("tree", tree.treeUri.toString());
        ret.put("name", tree.displayName());
        String state;
        if (!tree.hasPermission(false)) state = "unavailable";
        else if (!tree.isAvailable()) state = "unavailable";
        else if (!tree.hasPermission(true)) state = "mounted_ro";
        else state = "mounted";
        ret.put("state", state);
        long[] space = folderSpace(tree.treeUri);
        if (space != null) {
            ret.put("freeBytes", space[0]);
            ret.put("totalBytes", space[1]);
        }
        return ret;
    }

    /** Free / total bytes of the volume holding a picked folder, when it can be found (API 30+) */
    private long[] folderSpace(Uri treeUri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        try {
            String id = DocumentsContract.getTreeDocumentId(treeUri);
            String volume = id.contains(":") ? id.substring(0, id.indexOf(':')) : id;
            StorageManager manager = getContext().getSystemService(StorageManager.class);
            for (StorageVolume storageVolume : manager.getStorageVolumes()) {
                boolean match = "primary".equals(volume) ? storageVolume.isPrimary() : volume.equals(storageVolume.getUuid());
                File dir = storageVolume.getDirectory();
                if (match && dir != null) {
                    StatFs stat = new StatFs(dir.getAbsolutePath());
                    return new long[] { stat.getAvailableBytes(), stat.getTotalBytes() };
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @PluginMethod
    public void checkFolder(PluginCall call) {
        String tree = call.getString("tree");
        if (tree == null) {
            call.reject("tree is required", ERR_BAD_PATH);
            return;
        }
        call.resolve(folderInfo(new TreeStorage(getContext(), Uri.parse(tree))));
    }

    //MBX File operations, on the SD card app folder or on a picked folder ("tree")

    /** A resolved target: either a File on the SD card or a path inside a picked folder */
    private static class Target {
        File file;
        TreeStorage tree;
        String path;
    }

    /** Resolves the call's path, or rejects the call and returns null */
    private Target resolve(PluginCall call, boolean forWrite) {
        String path = call.getString("path");
        if (path == null) {
            call.reject("path is required", ERR_BAD_PATH);
            return null;
        }
        Target target = new Target();
        target.path = path;

        String treeString = call.getString("tree");
        if (treeString != null) {
            TreeStorage tree = new TreeStorage(getContext(), Uri.parse(treeString));
            if (!tree.hasPermission(false) || !tree.isAvailable()) {
                TreeStorage.clearCache(tree.treeUri);
                call.reject("Folder not available", ERR_UNAVAILABLE);
                return null;
            }
            if (forWrite && !tree.hasPermission(true)) {
                call.reject("Folder is read-only", ERR_READONLY);
                return null;
            }
            target.tree = tree;
            return target;
        }

        File root = findSdRoot();
        if (root == null) {
            call.reject("SD card not available", ERR_UNAVAILABLE);
            return null;
        }
        String state = stateOf(root);
        if ("unavailable".equals(state)) {
            call.reject("SD card not available", ERR_UNAVAILABLE);
            return null;
        }
        if (forWrite && "mounted_ro".equals(state)) {
            call.reject("SD card is read-only", ERR_READONLY);
            return null;
        }
        try {
            String rootPath = root.getCanonicalPath();
            File file = new File(root, path);
            String filePath = file.getCanonicalPath();
            if (!filePath.equals(rootPath) && !filePath.startsWith(rootPath + File.separator)) {
                call.reject("Path escapes SD storage root", ERR_BAD_PATH);
                return null;
            }
            target.file = file;
            return target;
        } catch (IOException e) {
            call.reject("Cannot resolve path: " + e.getMessage(), ERR_BAD_PATH);
            return null;
        }
    }

    private void rejectIo(PluginCall call, String what, Exception e) {
        if (e instanceof IllegalArgumentException) {
            call.reject(what + ": " + e.getMessage(), ERR_BAD_PATH);
        } else {
            call.reject(what + ": " + e.getMessage(), ERR_IO);
        }
    }

    @PluginMethod
    public void mkdir(PluginCall call) {
        Target target = resolve(call, true);
        if (target == null) return;
        try {
            if (target.tree != null) {
                target.tree.mkdirs(target.path);
            } else if (!target.file.isDirectory() && !target.file.mkdirs()) {
                throw new IOException("Could not create directory " + target.file.getAbsolutePath());
            }
            call.resolve();
        } catch (Exception e) {
            rejectIo(call, "mkdir failed", e);
        }
    }

    @PluginMethod
    public void writeFile(PluginCall call) {
        write(call, false);
    }

    @PluginMethod
    public void appendFile(PluginCall call) {
        write(call, true);
    }

    private void write(PluginCall call, boolean append) {
        Target target = resolve(call, true);
        if (target == null) return;
        String data = call.getString("data", "");
        String encoding = call.getString("encoding");
        byte[] bytes;
        try {
            bytes = "utf8".equals(encoding)
                ? data.getBytes(StandardCharsets.UTF_8)
                : Base64.decode(data, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            call.reject("Invalid base64 data", ERR_IO);
            return;
        }
        try {
            if (target.tree != null) {
                Uri document = target.tree.createFile(target.path);
                try (OutputStream out = openTreeOutput(target, document, append)) {
                    out.write(bytes);
                }
                JSObject ret = new JSObject();
                ret.put("uri", SdStorageProvider.uriFor(getContext(), target.tree.treeUri, target.path).toString());
                call.resolve(ret);
            } else {
                File parent = target.file.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Could not create directory " + parent.getAbsolutePath());
                }
                try (OutputStream out = new FileOutputStream(target.file, append)) {
                    out.write(bytes);
                }
                JSObject ret = new JSObject();
                ret.put("uri", Uri.fromFile(target.file).toString());
                call.resolve(ret);
            }
        } catch (Exception e) {
            rejectIo(call, "Write failed", e);
        }
    }

    private OutputStream openTreeOutput(Target target, Uri document, boolean append) throws FileNotFoundException {
        String mode = append ? "wa" : "wt";
        OutputStream out;
        try {
            out = getContext().getContentResolver().openOutputStream(document, mode);
        } catch (FileNotFoundException | IllegalArgumentException e) {
            if (append) throw new FileNotFoundException("Append not supported: " + e.getMessage());
            // Some providers do not know "wt"; "w" truncates on most of them
            out = getContext().getContentResolver().openOutputStream(document, "w");
        }
        if (out == null) throw new FileNotFoundException("Cannot open " + target.path);
        return out;
    }

    @PluginMethod
    public void readFile(PluginCall call) {
        Target target = resolve(call, false);
        if (target == null) return;
        try {
            InputStream in;
            if (target.tree != null) {
                Uri document = target.tree.find(target.path);
                if (document == null) {
                    call.reject("File does not exist", ERR_NOT_FOUND);
                    return;
                }
                in = getContext().getContentResolver().openInputStream(document);
            } else {
                if (!target.file.isFile()) {
                    call.reject("File does not exist", ERR_NOT_FOUND);
                    return;
                }
                in = new FileInputStream(target.file);
            }
            if (in == null) throw new IOException("Cannot open " + target.path);
            byte[] bytes;
            try (InputStream stream = in; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[64 * 1024];
                int n;
                while ((n = stream.read(chunk)) > 0) buffer.write(chunk, 0, n);
                bytes = buffer.toByteArray();
            }
            String encoding = call.getString("encoding");
            JSObject ret = new JSObject();
            ret.put("data", "utf8".equals(encoding)
                ? new String(bytes, StandardCharsets.UTF_8)
                : Base64.encodeToString(bytes, Base64.NO_WRAP));
            call.resolve(ret);
        } catch (Exception e) {
            rejectIo(call, "Read failed", e);
        }
    }

    @PluginMethod
    public void stat(PluginCall call) {
        Target target = resolve(call, false);
        if (target == null) return;
        try {
            JSObject ret = new JSObject();
            if (target.tree != null) {
                Uri document = target.tree.find(target.path);
                if (document == null) {
                    call.reject("File does not exist", ERR_NOT_FOUND);
                    return;
                }
                boolean directory = target.tree.isDirectory(document);
                ret.put("type", directory ? "directory" : "file");
                ret.put("size", directory ? 0 : target.tree.size(document));
                ret.put("uri", SdStorageProvider.uriFor(getContext(), target.tree.treeUri, target.path).toString());
            } else {
                if (!target.file.exists()) {
                    call.reject("File does not exist", ERR_NOT_FOUND);
                    return;
                }
                ret.put("type", target.file.isDirectory() ? "directory" : "file");
                ret.put("size", target.file.length());
                ret.put("mtime", target.file.lastModified());
                ret.put("uri", Uri.fromFile(target.file).toString());
            }
            call.resolve(ret);
        } catch (Exception e) {
            rejectIo(call, "stat failed", e);
        }
    }

    @PluginMethod
    public void getUri(PluginCall call) {
        Target target = resolve(call, false);
        if (target == null) return;
        JSObject ret = new JSObject();
        if (target.tree != null) {
            // Served by SdStorageProvider: child paths can be appended to this uri
            ret.put("uri", SdStorageProvider.uriFor(getContext(), target.tree.treeUri, target.path).toString());
        } else {
            ret.put("uri", Uri.fromFile(target.file).toString());
        }
        call.resolve(ret);
    }

    @PluginMethod
    public void rmdir(PluginCall call) {
        Target target = resolve(call, true);
        if (target == null) return;
        try {
            if (target.tree != null) {
                if (target.tree.find(target.path) == null) {
                    call.reject("Directory does not exist", ERR_NOT_FOUND);
                    return;
                }
                target.tree.delete(target.path);
                call.resolve();
                return;
            }
            File dir = target.file;
            if (!dir.exists()) {
                call.reject("Directory does not exist", ERR_NOT_FOUND);
                return;
            }
            boolean recursive = Boolean.TRUE.equals(call.getBoolean("recursive", false));
            if (!recursive) {
                String[] children = dir.list();
                if (children != null && children.length > 0) {
                    call.reject("Directory is not empty", ERR_IO);
                    return;
                }
            }
            if (!deleteTree(dir)) throw new IOException("Could not delete " + dir.getAbsolutePath());
            call.resolve();
        } catch (Exception e) {
            rejectIo(call, "rmdir failed", e);
        }
    }

    private static boolean deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!deleteTree(child)) return false;
            }
        }
        return file.delete();
    }
}
