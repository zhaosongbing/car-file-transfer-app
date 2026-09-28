package com.zsb.carfiletransfer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import com.zsb.carfiletransfer.miuix.MiuixDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Online update: asks GitHub Releases for the newest published version and
 * offers to install it.
 *
 * <p>The check runs on a worker thread the first time the app is resumed, so it
 * never blocks the UI. A version the user skipped is remembered and no longer
 * offered. The downloaded package is handed to the system installer through
 * {@link UpdateProvider}.</p>
 */
public final class UpdateManager {

    private static final String TAG = "UpdateManager";

    private static final String OWNER = "zhaosongbing";
    private static final String REPO = "car-file-transfer-app";
    private static final String API =
            "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest";

    private static final String PREF = "carfile";
    private static final String KEY_SKIPPED = "update_skipped_version";
    private static final String APK_NAME = "CarFileTransfer-update.apk";

    private static final Handler ui = new Handler(Looper.getMainLooper());

    private UpdateManager() {
    }

    /** Parsed description of the newest release. */
    public static final class Release {
        public String tag;        // v8.0.0
        public String version;    // 8.0.0
        public String name;       // release title
        public String notes;      // release body
        public String apkUrl;
        public long apkSize;
    }

    /**
     * Kick off a check. Silent on any network problem - an update prompt is a
     * convenience, never a blocker for using the app.
     */
    public static void check(final Activity a, final boolean force) {
        new Thread(new Runnable() {
            public void run() {
                final Release r = fetchLatest();
                if (r == null || r.apkUrl == null) return;
                if (!isNewer(r.version, currentVersion(a))) {
                    if (force) notifyUpToDate(a);
                    return;
                }
                if (!force && r.version.equals(skippedVersion(a))) return;
                ui.post(new Runnable() {
                    public void run() {
                        showUpdateDialog(a, r);
                    }
                });
            }
        }, "update-check").start();
    }

    private static void notifyUpToDate(final Activity a) {
        ui.post(new Runnable() {
            public void run() {
                Toast.makeText(a, a.getString(R.string.update_latest), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---------------------------------------------------------------- network

    private static Release fetchLatest() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(API).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestMethod("GET");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "CarFileTransfer");
            int code = c.getResponseCode();
            if (code != 200) {
                Log.i(TAG, "release query returned " + code);
                return null;
            }
            String json = readAll(c.getInputStream());
            JSONObject o = new JSONObject(json);

            Release r = new Release();
            r.tag = o.optString("tag_name", "");
            r.version = stripTag(r.tag);
            r.name = o.optString("name", r.tag);
            r.notes = o.optString("body", "");
            if (r.notes.length() > 600) r.notes = r.notes.substring(0, 600) + "…";

            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) continue;
                    String url = asset.optString("browser_download_url", "");
                    if (url.endsWith(".apk")) {
                        r.apkUrl = url;
                        r.apkSize = asset.optLong("size", 0L);
                        break;
                    }
                }
            }
            if (r.apkUrl == null && r.tag.length() > 0) {
                r.apkUrl = "https://github.com/" + OWNER + "/" + REPO
                        + "/releases/download/" + r.tag + "/CarFileTransfer.apk";
            }
            return r;
        } catch (Exception e) {
            Log.i(TAG, "update check failed: " + e.getMessage());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    // ---------------------------------------------------------------- version

    private static String stripTag(String tag) {
        String v = tag == null ? "" : tag.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        return v;
    }

    private static String currentVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName == null ? "0" : pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** Numeric compare of dot separated version strings ("8.0" vs "8.0.0"). */
    static boolean isNewer(String candidate, String installed) {
        int[] a = parts(candidate);
        int[] b = parts(installed);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] > b[i];
        }
        return false;
    }

    private static int[] parts(String v) {
        int[] out = new int[]{0, 0, 0};
        if (v == null) return out;
        String[] seg = v.split("[._-]");
        for (int i = 0; i < 3 && i < seg.length; i++) {
            try {
                out[i] = Integer.parseInt(seg[i].trim());
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static String skippedVersion(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return p.getString(KEY_SKIPPED, "");
    }

    private static void rememberSkipped(Context c, String version) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        p.edit().putString(KEY_SKIPPED, version).apply();
    }

    // ---------------------------------------------------------------- dialog

    private static void showUpdateDialog(final Activity a, final Release r) {
        String notes = (r.notes == null || r.notes.trim().isEmpty())
                ? a.getString(R.string.update_no_notes) : r.notes.trim();
        String message = a.getString(R.string.update_message, r.version, notes);

        new MiuixDialog.Builder(a)
                .setTitle(a.getString(R.string.update_title, r.version))
                .setMessage(message)
                .setPositive(a.getString(R.string.update_now),
                        new MiuixDialog.OnActionListener() {
                            public void onAction(MiuixDialog d) {
                                d.dismiss();
                                downloadAndInstall(a, r);
                            }
                        })
                .setNegative(a.getString(R.string.update_later), null)
                .setNeutral(a.getString(R.string.update_skip),
                        new MiuixDialog.OnActionListener() {
                            public void onAction(MiuixDialog d) {
                                rememberSkipped(a, r.version);
                                d.dismiss();
                            }
                        })
                .setCancelable(true)
                .show();
    }

    // ---------------------------------------------------------------- install

    private static void downloadAndInstall(final Activity a, final Release r) {
        final MiuixDialog[] progress = new MiuixDialog[1];
        progress[0] = new MiuixDialog.Builder(a)
                .setTitle(a.getString(R.string.update_title, r.version))
                .setMessage(a.getString(R.string.update_downloading))
                .setCancelable(false)
                .show();

        new Thread(new Runnable() {
            public void run() {
                File out = new File(UpdateProvider.updateDir(a), APK_NAME);
                boolean ok = download(r.apkUrl, out);
                final boolean done = ok;
                ui.post(new Runnable() {
                    public void run() {
                        if (progress[0] != null) progress[0].dismiss();
                        if (done) {
                            install(a, out);
                        } else {
                            Toast.makeText(a, a.getString(R.string.update_failed),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "update-download").start();
    }

    private static boolean download(String url, File out) {
        HttpURLConnection c = null;
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            if (out.exists()) out.delete();
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "download returned " + code);
                return false;
            }
            long total = c.getContentLength();
            in = c.getInputStream();
            fos = new FileOutputStream(out);
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            fos.flush();
            Log.i(TAG, "downloaded " + out.length() + " bytes (declared " + total + ")");
            return out.length() > 0;
        } catch (Exception e) {
            Log.w(TAG, "download failed: " + e.getMessage());
            return false;
        } finally {
            close(fos);
            close(in);
            if (c != null) c.disconnect();
        }
    }

    private static void install(Activity a, File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 26
                    && !a.getPackageManager().canRequestPackageInstalls()) {
                // the user has to allow "install unknown apps" for this source once
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + a.getPackageName()));
                a.startActivity(i);
                Toast.makeText(a, a.getString(R.string.update_allow_install),
                        Toast.LENGTH_LONG).show();
                return;
            }
            Uri uri = UpdateProvider.uriFor(apk.getName());
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "install: " + e.getMessage());
            Toast.makeText(a, a.getString(R.string.update_failed), Toast.LENGTH_LONG).show();
        }
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }
}
