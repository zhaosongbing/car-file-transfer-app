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
 * Serves the downloaded update package to the system installer.
 *
 * <p>Android 7+ refuses {@code file://} URIs handed to another app, so the
 * package installer has to read through a content provider. The update lives in
 * its own directory so it never shows up in the received-files list.</p>
 */
public class UpdateProvider extends ContentProvider {

    public static final String AUTHORITY = "com.zsb.carfiletransfer.update";
    private static final int MATCH_FILE = 1;
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        MATCHER.addURI(AUTHORITY, "*", MATCH_FILE);
    }

    private File root;

    /** Where the downloaded APK is kept. */
    public static File updateDir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File d = new File(base, "update");
        if (!d.exists() && !d.mkdirs()) {
            android.util.Log.w("UpdateProvider", "cannot create " + d.getAbsolutePath());
        }
        return d;
    }

    public static Uri uriFor(String fileName) {
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(fileName));
    }

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        if (ctx == null) return false;
        root = updateDir(ctx);
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
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
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
