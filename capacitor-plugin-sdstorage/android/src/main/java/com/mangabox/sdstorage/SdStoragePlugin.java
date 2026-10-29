package com.mangabox.sdstorage;

import android.net.Uri;
import android.os.Environment;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * App-private storage on the removable SD card, i.e. getExternalFilesDirs(null)[1..].
 *
 * Android grants an app full access to these folders without any permission (API 19+),
 * but @capacitor/filesystem treats any absolute path it does not recognise as public
 * external storage and demands READ/WRITE_EXTERNAL_STORAGE on Android 9-12.
 * This plugin does the file I/O itself so no permission is ever requested.
 *
 * Every path argument is relative to the SD card root and may not escape it.
 */
@CapacitorPlugin(name = "SdStorage")
public class SdStoragePlugin extends Plugin {

    private static final String ERR_UNAVAILABLE = "SD_UNAVAILABLE";
    private static final String ERR_READONLY = "SD_READONLY";
    private static final String ERR_NOT_FOUND = "NOT_FOUND";
    private static final String ERR_BAD_PATH = "BAD_PATH";
    private static final String ERR_IO = "IO_ERROR";

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

    /** Resolves a relative path inside the SD root, or rejects the call and returns null. */
    private File resolve(PluginCall call, boolean forWrite) {
        String path = call.getString("path");
        if (path == null) {
            call.reject("path is required", ERR_BAD_PATH);
            return null;
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
            return file;
        } catch (IOException e) {
            call.reject("Cannot resolve path: " + e.getMessage(), ERR_BAD_PATH);
            return null;
        }
    }

    @PluginMethod
    public void mkdir(PluginCall call) {
        File dir = resolve(call, true);
        if (dir == null) return;
        if (dir.isDirectory() || dir.mkdirs()) {
            call.resolve();
        } else {
            call.reject("Could not create directory " + dir.getAbsolutePath(), ERR_IO);
        }
    }

    @PluginMethod
    public void writeFile(PluginCall call) {
        File file = resolve(call, true);
        if (file == null) return;
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
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            call.reject("Could not create directory " + parent.getAbsolutePath(), ERR_IO);
            return;
        }
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
            JSObject ret = new JSObject();
            ret.put("uri", Uri.fromFile(file).toString());
            call.resolve(ret);
        } catch (IOException e) {
            call.reject("Write failed: " + e.getMessage(), ERR_IO);
        }
    }

    @PluginMethod
    public void readFile(PluginCall call) {
        File file = resolve(call, false);
        if (file == null) return;
        if (!file.isFile()) {
            call.reject("File does not exist", ERR_NOT_FOUND);
            return;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int off = 0;
            while (off < bytes.length) {
                int n = in.read(bytes, off, bytes.length - off);
                if (n < 0) break;
                off += n;
            }
            String encoding = call.getString("encoding");
            JSObject ret = new JSObject();
            ret.put("data", "utf8".equals(encoding)
                ? new String(bytes, 0, off, StandardCharsets.UTF_8)
                : Base64.encodeToString(bytes, 0, off, Base64.NO_WRAP));
            call.resolve(ret);
        } catch (IOException e) {
            call.reject("Read failed: " + e.getMessage(), ERR_IO);
        }
    }

    @PluginMethod
    public void stat(PluginCall call) {
        File file = resolve(call, false);
        if (file == null) return;
        if (!file.exists()) {
            call.reject("File does not exist", ERR_NOT_FOUND);
            return;
        }
        JSObject ret = new JSObject();
        ret.put("type", file.isDirectory() ? "directory" : "file");
        ret.put("size", file.length());
        ret.put("mtime", file.lastModified());
        ret.put("uri", Uri.fromFile(file).toString());
        call.resolve(ret);
    }

    @PluginMethod
    public void getUri(PluginCall call) {
        File file = resolve(call, false);
        if (file == null) return;
        JSObject ret = new JSObject();
        ret.put("uri", Uri.fromFile(file).toString());
        call.resolve(ret);
    }

    @PluginMethod
    public void rmdir(PluginCall call) {
        File dir = resolve(call, true);
        if (dir == null) return;
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
        if (deleteTree(dir)) {
            call.resolve();
        } else {
            call.reject("Could not delete " + dir.getAbsolutePath(), ERR_IO);
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
