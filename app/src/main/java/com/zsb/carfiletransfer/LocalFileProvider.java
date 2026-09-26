package com.zsb.carfiletransfer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Minimal replacement for androidx FileProvider: shares files from the
 * received-files directory so other apps (viewer / package installer) can
 * read them through a content:// URI on Android 7+.
 */
public class LocalFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.zsb.carfiletransfer.files";
    private static final int MATCH_FILE = 1;
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        MATCHER.addURI(AUTHORITY, "*", MATCH_FILE);
    }

    private File root;

    public static Uri uriFor(String fileName) {
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(fileName));
    }

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        if (ctx == null) return false;
        root = FileRepository.getStorageDir(ctx);
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (MATCHER.match(uri) != MATCH_FILE) {
            throw new FileNotFoundException("unsupported uri: " + uri);
        }
        String name = uri.getLastPathSegment();
        if (TextUtils.isEmpty(name)) throw new FileNotFoundException("missing name");
        File f = new File(root, name);
        if (!f.exists() || !f.isFile()) throw new FileNotFoundException("not found: " + name);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        return name == null ? "*/*" : FileRepository.mimeOf(name);
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
