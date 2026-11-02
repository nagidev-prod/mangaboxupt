package com.mangabox.sdstorage;

import android.content.ContentResolver;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.util.Base64;
import android.webkit.MimeTypeMap;

import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File access inside a folder the user picked with ACTION_OPEN_DOCUMENT_TREE.
 *
 * Paths are relative to the picked folder ("offline-books/123/pages/1.jpg").
 * Looking a child up means listing its parent folder, so every listed folder is cached:
 * downloading a book then costs one listing per folder instead of one per page.
 */
class TreeStorage {

    /** relative path -> document uri, per tree */
    private static final Map<String, Map<String, Uri>> CACHE = new ConcurrentHashMap<>();
    /** relative folder paths already listed, per tree */
    private static final Map<String, Map<String, Boolean>> LISTED = new ConcurrentHashMap<>();

    final Uri treeUri;
    private final ContentResolver resolver;
    private final Map<String, Uri> cache;
    private final Map<String, Boolean> listed;

    TreeStorage(Context context, Uri treeUri) {
        this.treeUri = treeUri;
        this.resolver = context.getContentResolver();
        this.cache = CACHE.computeIfAbsent(treeUri.toString(), k -> new ConcurrentHashMap<>());
        this.listed = LISTED.computeIfAbsent(treeUri.toString(), k -> new ConcurrentHashMap<>());
        cache.putIfAbsent("", DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)));
    }

    /** Short URL safe key for a tree, used in content:// URLs served by SdStorageProvider */
    static String keyOf(Uri treeUri) {
        return Base64.encodeToString(treeUri.toString().getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    static Uri treeOfKey(String key) {
        return Uri.parse(new String(Base64.decode(key, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8));
    }

    /** True when the app still holds the persisted permission for this tree */
    boolean hasPermission(boolean write) {
        for (UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (permission.getUri().equals(treeUri) && permission.isReadPermission() && (!write || permission.isWritePermission())) {
                return true;
            }
        }
        return false;
    }

    /** True when the folder can be reached right now (the SD card holding it may be removed) */
    boolean isAvailable() {
        try (Cursor cursor = resolver.query(cache.get(""), new String[] { Document.COLUMN_DOCUMENT_ID }, null, null, null)) {
            return cursor != null && cursor.moveToFirst();
        } catch (Exception e) {
            return false;
        }
    }

    /** Human readable location, e.g. "SD card 1A2B-3C4D/Manga" or "Internal storage/Manga" */
    String displayName() {
        String id = DocumentsContract.getTreeDocumentId(treeUri);
        int colon = id.indexOf(':');
        if (colon < 0) return id;
        String volume = id.substring(0, colon);
        String path = id.substring(colon + 1);
        String volumeName = "primary".equals(volume) ? "Internal storage" : "SD card " + volume;
        return path.isEmpty() ? volumeName : volumeName + "/" + path;
    }

    private static String normalize(String path) {
        StringBuilder out = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) throw new IllegalArgumentException("Path escapes folder");
            if (out.length() > 0) out.append('/');
            out.append(part);
        }
        return out.toString();
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String nameOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private void listChildren(String folder, Uri folderUri) throws FileNotFoundException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(folderUri));
        try (Cursor cursor = resolver.query(children, new String[] { Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME }, null, null, null)) {
            if (cursor == null) throw new FileNotFoundException("Folder not available");
            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                String child = folder.isEmpty() ? name : folder + "/" + name;
                cache.put(child, DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0)));
            }
        }
        listed.put(folder, true);
    }

    /** Document uri of an existing file or folder, or null */
    Uri find(String rawPath) throws FileNotFoundException {
        String path = normalize(rawPath);
        Uri cached = cache.get(path);
        if (cached != null) return cached;
        if (path.isEmpty()) return null;
        String parent = parentOf(path);
        Uri parentUri = find(parent);
        if (parentUri == null) return null;
        if (!listed.containsKey(parent)) {
            listChildren(parent, parentUri);
            return cache.get(path);
        }
        return null;
    }

    Uri mkdirs(String rawPath) throws FileNotFoundException {
        String path = normalize(rawPath);
        Uri existing = find(path);
        if (existing != null) return existing;
        Uri parentUri = mkdirs(parentOf(path));
        Uri created = DocumentsContract.createDocument(resolver, parentUri, Document.MIME_TYPE_DIR, nameOf(path));
        if (created == null) throw new FileNotFoundException("Could not create folder " + path);
        cache.put(path, created);
        listed.put(path, true);
        return created;
    }

    /** Existing file, or a new empty one */
    Uri createFile(String rawPath) throws FileNotFoundException {
        String path = normalize(rawPath);
        Uri existing = find(path);
        if (existing != null) return existing;
        Uri parentUri = mkdirs(parentOf(path));
        String name = nameOf(path);
        String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        Uri created = DocumentsContract.createDocument(resolver, parentUri, mime != null ? mime : "application/octet-stream", name);
        if (created == null) throw new FileNotFoundException("Could not create file " + path);
        cache.put(path, created);
        return created;
    }

    /** Checks with the provider, cached lookups can be stale when files are deleted outside the app */
    boolean exists(Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[] { Document.COLUMN_DOCUMENT_ID }, null, null, null)) {
            return cursor != null && cursor.moveToFirst();
        } catch (Exception e) {
            return false;
        }
    }

    boolean isDirectory(Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[] { Document.COLUMN_MIME_TYPE }, null, null, null)) {
            return cursor != null && cursor.moveToFirst() && Document.MIME_TYPE_DIR.equals(cursor.getString(0));
        }
    }

    long size(Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[] { Document.COLUMN_SIZE }, null, null, null)) {
            return cursor != null && cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getLong(0) : 0;
        }
    }

    /** Deletes a file or a whole folder */
    void delete(String rawPath) throws FileNotFoundException {
        String path = normalize(rawPath);
        Uri uri = find(path);
        if (uri == null) throw new FileNotFoundException("Not found: " + path);
        if (!DocumentsContract.deleteDocument(resolver, uri)) throw new FileNotFoundException("Could not delete " + path);
        String prefix = path + "/";
        cache.keySet().removeIf(key -> key.equals(path) || key.startsWith(prefix));
        listed.keySet().removeIf(key -> key.equals(path) || key.startsWith(prefix));
    }

    /** Forget cached lookups, e.g. after the card was removed and inserted again */
    static void clearCache(Uri treeUri) {
        CACHE.remove(treeUri.toString());
        LISTED.remove(treeUri.toString());
    }
}
