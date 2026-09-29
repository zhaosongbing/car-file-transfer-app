package com.zsb.carfiletransfer;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.zsb.carfiletransfer.miuix.MiuixDialog;
import com.zsb.carfiletransfer.miuix.MiuixProgress;
import com.zsb.carfiletransfer.miuix.MiuixText;
import com.zsb.carfiletransfer.miuix.MiuixTheme;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

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

    /**
     * The package is stored per version. One shared file name meant a half
     * download of an older build could be resumed into a newer one: the result
     * matched the new size and still started with "PK", but was a mix of two
     * archives - which is what made the system installer fail with
     * "解析软件包时出现问题".
     */
    private static final String APK_EXT = ".apk";
    private static final String PART_EXT = ".part";
    private static final String KEY_PARTIAL = "update_partial_id";
    private static final String KEY_PENDING = "update_pending_path";

    /** Distinguish "slow answer" from "no answer at all". */
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;

    /** Downloads are bulkier than the metadata call, so allow far more slack. */
    private static final int DL_CONNECT_TIMEOUT_MS = 20000;
    private static final int DL_READ_TIMEOUT_MS = 45000;
    private static final int MAX_ATTEMPTS_PER_SOURCE = 3;

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
        /**
         * Every URL able to serve this package, best source first. They all
         * deliver the identical file, so a partial download may resume from
         * whichever source actually answers.
         */
        public final List<String> apkUrls = new ArrayList<String>();
    }

    /** Why a check failed, so the toast can name the real cause. */
    public enum Fail {
        OFFLINE, TIMEOUT, HTTP, NOT_FOUND, FORBIDDEN, RATE_LIMITED, PARSE, NO_ASSET, UNKNOWN
    }

    /** Outcome of one release query: either a parsed release or the reason it failed. */
    private static final class Outcome {
        Release release;
        Fail fail = Fail.UNKNOWN;
        int httpCode;
        String detail;
    }

    /** Why a download failed, so the toast can name the real cause. */
    public enum DlFail {
        OFFLINE, TIMEOUT, HTTP, STORAGE, CORRUPT, ALL_FAILED, UNKNOWN
    }

    /** Outcome of one download attempt, again carrying the real reason. */
    private static final class DownloadResult {
        boolean ok;
        DlFail fail = DlFail.UNKNOWN;
        int httpCode;
        String detail;   // exception text, or the url that failed
        String host;     // host we ultimately could not reach
    }

    /**
     * Kick off a check. Every failure now carries a reason, so a manual check
     * never reports a vague error for what was really something else.
     */
    public static void check(final Activity a, final boolean force) {
        // immediate acknowledgement so a click never feels dead, and so the user
        // knows the detection logic actually fired
        if (force) {
            Toast.makeText(a, a.getString(R.string.update_checking), Toast.LENGTH_SHORT).show();
        }
        new Thread(new Runnable() {
            public void run() {
                final Outcome o = fetchLatest(a);
                if (o.release == null || o.release.apkUrl == null) {
                    if (force) notifyCheckFailed(a, o.fail, o.httpCode, o.detail);
                    return;
                }
                final Release r = o.release;
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

    private static void notifyCheckFailed(final Activity a, final Fail fail,
                                          final int httpCode, final String detail) {
        ui.post(new Runnable() {
            public void run() {
                String msg;
                switch (fail) {
                    case OFFLINE:
                        msg = a.getString(R.string.update_err_offline);
                        break;
                    case TIMEOUT:
                        msg = a.getString(R.string.update_err_timeout);
                        break;
                    case NOT_FOUND:
                        msg = a.getString(R.string.update_err_not_found);
                        break;
                    case FORBIDDEN:
                        msg = a.getString(R.string.update_err_forbidden, httpCode);
                        break;
                    case RATE_LIMITED:
                        msg = a.getString(R.string.update_err_rate_limited);
                        break;
                    case PARSE:
                        msg = a.getString(R.string.update_err_parse);
                        break;
                    case NO_ASSET:
                        msg = a.getString(R.string.update_err_no_asset);
                        break;
                    case HTTP:
                        msg = a.getString(R.string.update_err_http_generic, httpCode);
                        break;
                    default:
                        msg = a.getString(R.string.update_err_unknown,
                                detail == null ? "unknown" : detail);
                        break;
                }
                Toast.makeText(a, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ---------------------------------------------------------------- network

    /**
     * Query GitHub for the newest release.
     *
     * <p>Plain HTTPS against {@link #API} using the platform trust store - no
     * custom TrustManager, no cleartext fallback, so a hostile network cannot
     * swap the update metadata.</p>
     *
     * <p>Every failure path records <em>why</em> it failed instead of returning
     * null, which is what previously made every problem look like a network
     * outage (including a 404 caused by querying a private repository).</p>
     */
    private static Outcome fetchLatest(Context c) {
        Outcome out = new Outcome();
        if (!isOnline(c)) {
            out.fail = Fail.OFFLINE;
            return out;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(API).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "CarFileTransfer");
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            out.httpCode = code;
            if (code == 404) {                     // private repo, or no releases yet
                out.fail = Fail.NOT_FOUND;
                return out;
            }
            if (code == 403) {
                out.fail = Fail.FORBIDDEN;
                return out;
            }
            if (code == 429) {
                out.fail = Fail.RATE_LIMITED;
                return out;
            }
            if (code != 200) {
                out.fail = Fail.HTTP;
                return out;
            }

            String json = readAll(conn.getInputStream());
            JSONObject o;
            try {
                o = new JSONObject(json);
            } catch (Exception e) {
                Log.i(TAG, "release json unreadable: " + e.getMessage());
                out.fail = Fail.PARSE;
                return out;
            }

            Release r = new Release();
            r.tag = o.optString("tag_name", "");
            r.version = stripTag(r.tag);
            r.name = o.optString("name", r.tag);
            r.notes = o.optString("body", "");
            // Show the notes in full - the dialog scrolls, so nothing is cut off.
            if (r.notes.length() > 8000) r.notes = r.notes.substring(0, 8000) + "…";
            if (r.tag.length() == 0) {
                out.fail = Fail.PARSE;
                return out;
            }

            // Prefer the versioned attachment; the bare name is kept as a
            // compatibility alias, so either one is a valid download.
            //
            // GitHub hands every one of these links out, but they all end up
            // redirecting to the release-assets CDN host - a host some networks
            // simply cannot reach even though this very metadata call succeeded
            // against api.github.com. So collect them all and let the download
            // step move on to the next source instead of giving up.
            LinkedHashSet<String> urls = new LinkedHashSet<String>();
            String versionedApi = null, versionedWeb = null;
            String anyApi = null, anyWeb = null;
            long versionedSize = 0L, anySize = 0L;
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) continue;
                    String name = asset.optString("name", "");
                    if (!name.endsWith(".apk")) continue;
                    String web = asset.optString("browser_download_url", "");
                    String api = asset.optString("url", "");
                    long size = asset.optLong("size", 0L);
                    if (anyWeb == null) {
                        anyWeb = web;
                        anyApi = api;
                        anySize = size;
                    }
                    if (name.startsWith("CarFileTransfer-v") && versionedWeb == null) {
                        versionedWeb = web;
                        versionedApi = api;
                        versionedSize = size;
                    }
                }
            }
            if (versionedWeb != null) {
                if (versionedApi != null) urls.add(versionedApi);
                urls.add(versionedWeb);
            }
            if (anyWeb != null) {
                if (anyApi != null) urls.add(anyApi);
                urls.add(anyWeb);
            }
            if (urls.isEmpty() && r.tag.length() > 0) {
                // No usable asset list - fall back to the canonical file names.
                urls.add("https://github.com/" + OWNER + "/" + REPO
                        + "/releases/download/" + r.tag + "/CarFileTransfer-" + r.tag + ".apk");
                urls.add("https://github.com/" + OWNER + "/" + REPO
                        + "/releases/download/" + r.tag + "/CarFileTransfer.apk");
            }
            for (String u : urls) {
                if (u != null && u.length() > 0) r.apkUrls.add(u);
            }
            if (versionedSize > 0) {
                r.apkUrl = versionedWeb;
                r.apkSize = versionedSize;
            } else if (anySize > 0) {
                r.apkUrl = anyWeb;
                r.apkSize = anySize;
            } else if (!r.apkUrls.isEmpty()) {
                r.apkUrl = r.apkUrls.get(0);
            }
            if (r.apkUrl == null) {
                out.fail = Fail.NO_ASSET;
                return out;
            }
            out.release = r;
            return out;
        } catch (java.net.SocketTimeoutException e) {
            Log.i(TAG, "update check timed out");
            out.fail = Fail.TIMEOUT;
            return out;
        } catch (Exception e) {
            Log.i(TAG, "update check failed: " + e.getMessage());
            out.fail = Fail.UNKNOWN;
            out.detail = e.getMessage();
            return out;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Coarse connectivity probe, only used to separate "the device is offline"
     * from "the server did not answer". Anything unexpected defers to actually
     * trying the request.
     */
    private static boolean isOnline(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            if (Build.VERSION.SDK_INT >= 23) {
                android.net.Network n = cm.getActiveNetwork();
                if (n == null) return false;
                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                return caps != null && caps.hasCapability(
                        android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable t) {
            return true;
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
        // Determinate progress: a real bar plus a live byte counter, so a slow
        // connection is visibly progressing rather than looking hung.
        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        MiuixProgress bar = new MiuixProgress(a, 6f);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        MiuixText stat = new MiuixText(a, a.getString(R.string.update_dl_preparing),
                MiuixText.Role.BODY_SMALL, MiuixText.Tone.SECONDARY);
        LinearLayout.LayoutParams statLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statLp.topMargin = MiuixTheme.dp(a, 10f);
        stat.setLayoutParams(statLp);
        panel.addView(bar);
        panel.addView(stat);

        final MiuixDialog[] progress = new MiuixDialog[1];
        progress[0] = new MiuixDialog.Builder(a)
                .setTitle(a.getString(R.string.update_title, r.version))
                .setMessage(a.getString(R.string.update_downloading))
                .setContent(panel)
                .setCancelable(false)
                .show();

        final DownloadListener listener = new UiProgress(a, bar, stat);
        final File out = targetFile(a, r);
        final File part = partialFile(a, r);

        new Thread(new Runnable() {
            public void run() {
                final DownloadResult res = fetchPackage(a, r, out, part, listener);
                ui.post(new Runnable() {
                    public void run() {
                        if (progress[0] != null) progress[0].dismiss();
                        if (res.ok) {
                            install(a, out);
                        } else {
                            notifyDownloadFailed(a, res);
                        }
                    }
                });
            }
        }, "update-download").start();
    }

    // ---------------------------------------------------------------- files

    /** Version letters only - the name becomes a file and a content URI. */
    private static String safeVersion(String v) {
        String s = v == null ? "" : v.trim();
        if (s.length() == 0) s = "latest";
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Final, installable package for one release. */
    private static File targetFile(Context c, Release r) {
        return new File(UpdateProvider.updateDir(c),
                "CarFileTransfer-" + safeVersion(r.version) + APK_EXT);
    }

    /** Half-finished download; only promoted to the final name once verified. */
    private static File partialFile(Context c, Release r) {
        return new File(UpdateProvider.updateDir(c),
                "CarFileTransfer-" + safeVersion(r.version) + APK_EXT + PART_EXT);
    }

    private static String partialId(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY_PARTIAL, "");
    }

    private static void rememberPartial(Context c, String id) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY_PARTIAL, id).apply();
    }

    private static void clearPartial(Context c) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().remove(KEY_PARTIAL).apply();
    }

    /**
     * Download, then make sure the result really is an installable package.
     *
     * <p>Handing a broken file to the installer is how "解析软件包时出现问题"
     * reached the user, so nothing leaves this method without being parsed by
     * {@link PackageManager} first. A first failure gets one clean retry with
     * any half file discarded, because the usual causes - a partial left over
     * from another release, or a server that answers 200 instead of honouring
     * {@code Range} - are both fixed by starting over.</p>
     */
    private static DownloadResult fetchPackage(Context ctx, Release r, File out, File part,
                                               DownloadListener l) {
        DownloadResult res = downloadAny(ctx, r, part, l);
        if (res.ok) res = verifyPackage(ctx, r, part, res, l);
        if (res.ok) {
            if (!promote(part, out)) {
                res.ok = false;
                res.fail = DlFail.STORAGE;
                res.detail = out.getAbsolutePath();
            } else {
                sweep(ctx, out.getName());
            }
            return res;
        }
        // Retrying cannot conjure a network or more storage.
        if (res.fail == DlFail.OFFLINE || res.fail == DlFail.STORAGE) return res;

        clearPartial(ctx);
        part.delete();
        out.delete();
        l.onPhase(ctx.getString(R.string.update_dl_retry, 1));
        DownloadResult again = downloadAny(ctx, r, part, l);
        if (again.ok) again = verifyPackage(ctx, r, part, again, l);
        if (again.ok) {
            if (!promote(part, out)) {
                again.ok = false;
                again.fail = DlFail.STORAGE;
                again.detail = out.getAbsolutePath();
            } else {
                sweep(ctx, out.getName());
            }
        }
        return again;
    }

    /** Move the verified partial to its final name. */
    private static boolean promote(File part, File out) {
        if (!part.exists()) return false;
        if (out.exists() && !out.delete()) {
            Log.w(TAG, "cannot replace " + out);
        }
        if (part.renameTo(out)) return true;
        // Different mount points refuse rename; copy instead.
        java.io.InputStream in = null;
        java.io.OutputStream os = null;
        try {
            in = new java.io.FileInputStream(part);
            os = new java.io.FileOutputStream(out);
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
            return out.length() == part.length();
        } catch (Exception e) {
            Log.w(TAG, "promote failed: " + e.getMessage());
            return false;
        } finally {
            close(in);
            close(os);
        }
    }

    /** Drop packages left behind by earlier versions. */
    private static void sweep(Context ctx, String keep) {
        File dir = UpdateProvider.updateDir(ctx);
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && !keep.equals(f.getName())) {
                f.delete();
            }
        }
    }

    /**
     * Refuse anything the platform itself cannot parse.
     *
     * <p>Size and the "PK" magic catch a truncated or replaced body, but not a
     * file assembled from two different builds - only a real parse does. The
     * installer would otherwise reject it after the download looked finished.</p>
     */
    private static DownloadResult verifyPackage(Context ctx, Release r, File f,
                                                DownloadResult res, DownloadListener l) {
        l.onPhase(ctx.getString(R.string.update_dl_verify));
        if (f == null || !f.exists() || f.length() <= 0) {
            res.ok = false;
            res.fail = DlFail.CORRUPT;
            res.detail = "empty file";
            return res;
        }
        long expected = r.apkSize;
        if (expected > 0 && f.length() != expected) {
            Log.w(TAG, "size mismatch: got " + f.length() + " expected " + expected);
            res.ok = false;
            res.fail = DlFail.CORRUPT;
            res.detail = "size " + f.length() + " != " + expected;
            return res;
        }
        if (!looksLikeZip(f)) {
            // A captive portal or proxy answered with HTML instead.
            Log.w(TAG, "downloaded file is not an APK");
            res.ok = false;
            res.fail = DlFail.CORRUPT;
            res.detail = "not a zip/apk";
            return res;
        }
        PackageInfo pi = parsePackage(ctx, f);
        if (pi == null) {
            Log.w(TAG, "package manager cannot parse " + f);
            res.ok = false;
            res.fail = DlFail.CORRUPT;
            res.detail = "unparsable package";
            return res;
        }
        if (pi.packageName == null || !ctx.getPackageName().equals(pi.packageName)) {
            Log.w(TAG, "wrong package: " + pi.packageName);
            res.ok = false;
            res.fail = DlFail.CORRUPT;
            res.detail = "package " + pi.packageName;
            return res;
        }
        // Guard against a cached / renamed asset serving another build. The
        // manifest version has no trailing ".0", so compare numerically.
        if (!sameVersion(parts(r.version), parts(pi.versionName))) {
            Log.w(TAG, "version mismatch: " + pi.versionName + " != " + r.version);
            res.ok = false;
            res.fail = DlFail.CORRUPT;
            res.detail = "version " + pi.versionName;
            return res;
        }
        return res;
    }

    private static boolean sameVersion(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    /** null when the file is not a readable package for this device. */
    private static PackageInfo parsePackage(Context ctx, File f) {
        try {
            return ctx.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
        } catch (Throwable t) {
            Log.w(TAG, "getPackageArchiveInfo threw: " + t.getMessage());
            return null;
        }
    }

    /** Live feedback from the download thread. */
    private interface DownloadListener {
        void onProgress(long done, long total);

        void onPhase(String phase);
    }

    /** Throttles byte counts down to a handful of UI updates per second. */
    private static final class UiProgress implements DownloadListener {

        private final WeakReference<Activity> act;
        private final MiuixProgress bar;
        private final MiuixText stat;
        private long lastPostMs;
        private int lastPercent = -1;

        UiProgress(Activity a, MiuixProgress bar, MiuixText stat) {
            this.act = new WeakReference<Activity>(a);
            this.bar = bar;
            this.stat = stat;
        }

        public void onProgress(final long done, final long total) {
            final int pct = percent(done, total);
            long now = System.currentTimeMillis();
            if (pct == lastPercent && now - lastPostMs < 400L) return;
            lastPercent = pct;
            lastPostMs = now;
            ui.post(new Runnable() {
                public void run() {
                    Activity a = act.get();
                    if (a == null) return;
                    bar.setProgress(pct);
                    stat.setText(a.getString(R.string.update_dl_progress, pct,
                            humanSize(done), total > 0 ? humanSize(total)
                                    : a.getString(R.string.update_dl_unknown_size)));
                }
            });
        }

        public void onPhase(final String phase) {
            ui.post(new Runnable() {
                public void run() {
                    stat.setText(phase);
                }
            });
        }
    }

    /**
     * Fetch the package, trying every source we know of in turn.
     *
     * <p>All GitHub links redirect to the release-assets CDN, a host that some
     * networks cannot reach at all - which is why the update dialog could
     * appear (metadata came from {@code api.github.com}) while the download
     * always failed. Sources are interchangeable because they serve the
     * identical file, so a stalled source leaves a partial file that the next
     * attempt continues from - important on flaky mobile links where starting
     * over every time never finishes.</p>
     */
    private static DownloadResult downloadAny(Context ctx, Release r, File out,
                                              DownloadListener l) {
        DownloadResult res = new DownloadResult();
        if (!isOnline(ctx)) {
            res.fail = DlFail.OFFLINE;
            return res;
        }
        List<String> sources = new ArrayList<String>();
        for (String u : r.apkUrls) {
            if (u != null && u.length() > 0 && !sources.contains(u)) sources.add(u);
        }
        if (sources.isEmpty()) {
            res.fail = DlFail.UNKNOWN;
            res.detail = "no download source";
            return res;
        }
        // Resume identity: a half file belongs to one release served from one
        // source. Anything else - an older build, a different asset, a file
        // already at full size - is dropped, because resuming across builds
        // silently builds a package that no parser accepts.
        long expected = r.apkSize;
        String identity = r.version + "|" + sources.get(0);
        if (out.exists() && !identity.equals(partialId(ctx))) out.delete();
        if (out.exists() && (expected <= 0 || out.length() >= expected)) out.delete();
        rememberPartial(ctx, identity);

        DownloadResult last = null;
        for (String url : sources) {
            for (int attempt = 0; attempt < MAX_ATTEMPTS_PER_SOURCE; attempt++) {
                if (attempt > 0) {
                    l.onPhase(ctx.getString(R.string.update_dl_retry, attempt + 1));
                    sleepQuiet(1500L * attempt);
                }
                last = tryDownload(url, out, expected, l);
                if (last.ok) return last;
                // Nothing downstream can fix these two, so stop early.
                if (last.fail == DlFail.OFFLINE || last.fail == DlFail.STORAGE) {
                    return last;
                }
            }
        }
        if (last == null) {
            last = new DownloadResult();
            last.fail = DlFail.UNKNOWN;
            last.detail = "no download source";
            return last;
        }
        last.fail = DlFail.ALL_FAILED;
        last.host = hostOf(last.detail);
        return last;
    }

    /**
     * One source, resuming from whatever is already on disk. A {@code 416}
     * means the partial no longer matches the server, so start that source over.
     */
    private static DownloadResult tryDownload(String url, File out, long expected,
                                              DownloadListener l) {
        long start = out.exists() ? out.length() : 0L;
        if (start > 0) {
            DownloadResult r = streamDownload(url, out, start, expected, l);
            if (r.ok) return r;
            if (r.httpCode == 416) {
                out.delete();
                return streamDownload(url, out, 0L, expected, l);
            }
            return r;
        }
        return streamDownload(url, out, 0L, expected, l);
    }

    private static DownloadResult streamDownload(String url, File out, long start,
                                                 long expected, DownloadListener l) {
        DownloadResult res = new DownloadResult();
        res.detail = url;
        res.host = hostOf(url);
        HttpURLConnection c = null;
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            File parent = out.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                res.fail = DlFail.STORAGE;
                res.detail = parent.getAbsolutePath();
                return res;
            }
            try {
                fos = new FileOutputStream(out, start > 0);
            } catch (Exception e) {
                Log.w(TAG, "cannot write " + out + ": " + e.getMessage());
                res.fail = DlFail.STORAGE;
                res.detail = e.getMessage();
                return res;
            }

            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(DL_CONNECT_TIMEOUT_MS);
            c.setReadTimeout(DL_READ_TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "application/octet-stream");
            c.setRequestProperty("User-Agent", "CarFileTransfer");
            if (start > 0) c.setRequestProperty("Range", "bytes=" + start + "-");

            int code = c.getResponseCode();
            res.httpCode = code;
            if (code != 200 && code != 206) {
                Log.w(TAG, "download returned " + code + " from " + res.host);
                res.fail = DlFail.HTTP;
                return res;
            }
            if (start > 0) {
                // Resuming is only valid when the server actually honours the
                // Range request: a 200 (or a different offset) means it is
                // sending the whole body, and appending that to the bytes
                // already on disk would produce a corrupt package.
                if (code != 206 || rangeStart(c.getHeaderField("Content-Range")) != start) {
                    Log.w(TAG, "range not honoured (code " + code + "), restarting");
                    close(fos);
                    fos = null;
                    out.delete();
                    fos = new FileOutputStream(out, false);
                    start = 0L;
                }
            }

            long total = 0L;
            String range = c.getHeaderField("Content-Range");
            if (range != null && range.indexOf('/') > 0) {
                try {
                    total = Long.parseLong(range.substring(range.indexOf('/') + 1).trim());
                } catch (NumberFormatException ignored) {
                    total = 0L;
                }
            }
            if (total <= 0) {
                long declared = contentLength(c);
                total = start + declared;
            }
            long shown = expected > 0 ? expected : total;

            in = c.getInputStream();
            byte[] buf = new byte[32768];
            long written = start;
            int n;
            l.onProgress(written, shown);
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                written += n;
                l.onProgress(written, shown);
            }
            // Flush to the filesystem before anything else reads the file: an
            // installer that races a dirty page cache sees a short package.
            try {
                fos.flush();
                fos.getFD().sync();
            } catch (Exception ignored) {
            }
            close(fos);
            fos = null;

            long len = out.length();
            if (expected > 0 && len != expected) {
                Log.w(TAG, "size mismatch: got " + len + " expected " + expected);
                res.fail = DlFail.CORRUPT;
                res.detail = "size " + len + " != " + expected;
                return res;
            }
            if (!looksLikeZip(out)) {
                // A captive portal or proxy answered with HTML instead.
                Log.w(TAG, "downloaded file is not an APK");
                res.fail = DlFail.CORRUPT;
                res.detail = "not a zip/apk";
                return res;
            }
            Log.i(TAG, "downloaded " + len + " bytes");
            res.ok = true;
            return res;
        } catch (java.net.SocketTimeoutException e) {
            Log.w(TAG, "download timed out: " + e.getMessage());
            res.fail = DlFail.TIMEOUT;
            res.detail = e.getMessage();
            return res;
        } catch (java.io.FileNotFoundException e) {
            Log.w(TAG, "storage problem: " + e.getMessage());
            res.fail = DlFail.STORAGE;
            res.detail = e.getMessage();
            return res;
        } catch (Exception e) {
            Log.w(TAG, "download failed: " + e.getMessage());
            res.fail = DlFail.UNKNOWN;
            res.detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            return res;
        } finally {
            close(fos);
            close(in);
            if (c != null) c.disconnect();
        }
    }

    private static void notifyDownloadFailed(final Activity a, final DownloadResult res) {
        ui.post(new Runnable() {
            public void run() {
                String msg;
                switch (res.fail) {
                    case OFFLINE:
                        msg = a.getString(R.string.update_dl_err_offline);
                        break;
                    case TIMEOUT:
                        msg = a.getString(R.string.update_dl_err_timeout);
                        break;
                    case HTTP:
                        msg = a.getString(R.string.update_dl_err_http, res.httpCode);
                        break;
                    case STORAGE:
                        msg = a.getString(R.string.update_dl_err_storage);
                        break;
                    case CORRUPT:
                        msg = a.getString(R.string.update_dl_err_corrupt);
                        break;
                    case ALL_FAILED:
                        msg = a.getString(R.string.update_dl_err_all,
                                res.host == null ? "unknown" : res.host);
                        break;
                    default:
                        msg = a.getString(R.string.update_dl_err_unknown,
                                res.detail == null ? "unknown" : res.detail);
                        break;
                }
                Toast.makeText(a, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** APKs are zip archives, so a real download always starts with "PK". */
    private static boolean looksLikeZip(File f) {
        java.io.FileInputStream fis = null;
        try {
            fis = new java.io.FileInputStream(f);
            byte[] magic = new byte[4];
            int n = fis.read(magic);
            return n >= 2 && magic[0] == 'P' && magic[1] == 'K';
        } catch (Exception e) {
            return false;
        } finally {
            close(fis);
        }
    }

    private static long contentLength(HttpURLConnection c) {
        try {
            String h = c.getHeaderField("Content-Length");
            return h == null ? -1L : Long.parseLong(h.trim());
        } catch (Exception e) {
            return -1L;
        }
    }

    /** First byte of a "bytes=START-END/TOTAL" header, or -1 when absent. */
    private static long rangeStart(String range) {
        if (range == null) return -1L;
        try {
            int eq = range.indexOf('=');
            int dash = range.indexOf('-');
            if (eq < 0 || dash <= eq) return -1L;
            return Long.parseLong(range.substring(eq + 1, dash).trim());
        } catch (Exception e) {
            return -1L;
        }
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    private static int percent(long done, long total) {
        if (total <= 0) return 0;
        int p = (int) (100L * done / total);
        return Math.max(0, Math.min(100, p));
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1048576L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        }
        return String.format(Locale.US, "%.2f MB", bytes / 1048576f);
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void install(Activity a, File apk) {
        try {
            if (apk == null || !apk.exists() || apk.length() <= 0) {
                Toast.makeText(a, a.getString(R.string.update_failed), Toast.LENGTH_LONG).show();
                return;
            }
            if (Build.VERSION.SDK_INT >= 26
                    && !a.getPackageManager().canRequestPackageInstalls()) {
                // the user has to allow "install unknown apps" for this source
                // once; remember the package so the install resumes on return
                // instead of being silently forgotten
                rememberPending(a, apk.getAbsolutePath());
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + a.getPackageName()));
                try {
                    a.startActivity(i);
                } catch (Exception e) {
                    Log.w(TAG, "cannot open install settings: " + e.getMessage());
                }
                Toast.makeText(a, a.getString(R.string.update_allow_install),
                        Toast.LENGTH_LONG).show();
                return;
            }
            launchInstaller(a, apk);
        } catch (Exception e) {
            Log.w(TAG, "install: " + e.getMessage());
            Toast.makeText(a, a.getString(R.string.update_failed), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Hand the package to the system installer.
     *
     * <p>The read grant is attached to the intent, to the clip data and to every
     * package that can handle it. Installers that only look at one of the three
     * (MIUI's among them) otherwise open a URI they are not allowed to read and
     * report a parse failure rather than a permission error.</p>
     */
    private static void launchInstaller(Context a, File apk) {
        Uri uri = UpdateProvider.uriFor(apk.getName());
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            i.setClipData(ClipData.newUri(a.getContentResolver(), "update", uri));
        } catch (Exception ignored) {
        }
        try {
            PackageManager pm = a.getPackageManager();
            List<ResolveInfo> targets = pm.queryIntentActivities(i, 0);
            if (targets != null) {
                for (ResolveInfo ri : targets) {
                    if (ri.activityInfo == null || ri.activityInfo.packageName == null) continue;
                    a.grantUriPermission(ri.activityInfo.packageName, uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "grant failed: " + e.getMessage());
        }
        try {
            if (a instanceof Activity) {
                ((Activity) a).startActivity(i);
            } else {
                a.startActivity(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "no installer: " + e.getMessage());
            Toast.makeText(a, a.getString(R.string.update_no_installer),
                    Toast.LENGTH_LONG).show();
        }
    }

    private static void rememberPending(Context c, String path) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY_PENDING, path).apply();
    }

    private static void clearPending(Context c) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().remove(KEY_PENDING).apply();
    }

    /**
     * Finish an install that was interrupted by the "unknown sources" prompt.
     * Called when the app comes back to the foreground.
     */
    public static void retryPendingInstall(Activity a) {
        String path = a.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY_PENDING, "");
        if (path == null || path.length() == 0) return;
        File f = new File(path);
        if (!f.exists()) {
            clearPending(a);
            return;
        }
        if (Build.VERSION.SDK_INT >= 26
                && !a.getPackageManager().canRequestPackageInstalls()) {
            return;   // still not allowed - leave it for later
        }
        // re-check: an interrupted download must never reach the installer
        PackageInfo pi = parsePackage(a, f);
        if (pi == null || pi.packageName == null
                || !a.getPackageName().equals(pi.packageName)) {
            Log.w(TAG, "pending package no longer valid - discarding");
            f.delete();
            clearPending(a);
            return;
        }
        clearPending(a);
        launchInstaller(a, f);
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }
}
