package com.mangabox.sdstorage;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import java.io.FileNotFoundException;
import java.util.List;

/**
 * Read-only view of a picked folder as content://<app id>.sdstorage/<tree key>/<relative path>.
 *
 * Capacitor serves content:// URLs to the WebView, and with this layout a book folder URL plus
 * "/pages/1.jpg" is still a valid URL, so the reader code can keep building page URLs by
 * concatenation. Not exported: only this app can use it.
 */
public class SdStorageProvider extends ContentProvider {

    static String authority(android.content.Context context) {
        return context.getPackageName() + ".sdstorage";
    }

    static Uri uriFor(android.content.Context context, Uri treeUri, String relativePath) {
        Uri.Builder builder = new Uri.Builder().scheme("content").authority(authority(context)).appendPath(TreeStorage.keyOf(treeUri));
        for (String part : relativePath.split("/")) {
            if (!part.isEmpty()) builder.appendPath(part);
        }
        return builder.build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        List<String> segments = uri.getPathSegments();
        if (segments.size() < 2) throw new FileNotFoundException("Bad uri");
        TreeStorage tree;
        try {
            tree = new TreeStorage(getContext(), TreeStorage.treeOfKey(segments.get(0)));
        } catch (IllegalArgumentException e) {
            throw new FileNotFoundException("Bad tree key");
        }
        if (!tree.hasPermission(false)) throw new FileNotFoundException("Folder permission lost");
        String path = TextUtils.join("/", segments.subList(1, segments.size()));
        Uri document;
        try {
            document = tree.find(path);
        } catch (IllegalArgumentException e) {
            throw new FileNotFoundException("Bad path");
        }
        if (document == null) throw new FileNotFoundException("Not found: " + path);
        try {
            return getContext().getContentResolver().openFileDescriptor(document, "r");
        } catch (FileNotFoundException | SecurityException e) {
            // Stale cache (file changed outside the app, card swapped): look it up again once
            TreeStorage.clearCache(tree.treeUri);
            Uri retry = new TreeStorage(getContext(), tree.treeUri).find(path);
            if (retry == null) throw new FileNotFoundException("Not found: " + path);
            return getContext().getContentResolver().openFileDescriptor(retry, "r");
        }
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || !name.contains(".")) return "application/octet-stream";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(name.lastIndexOf('.') + 1).toLowerCase());
        return mime != null ? mime : "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
