package com.zsb.carfiletransfer;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

public class FileRepository {

    private static final String TAG = "FileRepository";
    private final File dir;

    public FileRepository(Context ctx) {
        dir = getStorageDir(ctx);
    }

    public static File getStorageDir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File d = new File(base, "received");
        if (!d.exists() && !d.mkdirs()) {
            Log.w(TAG, "cannot create " + d.getAbsolutePath());
        }
        return d;
    }

    public File getDir() {
        return dir;
    }

    /**
     * Strip path separators and unsafe characters so a hostile filename cannot
     * escape the storage dir, while still preserving CJK names - "简历.pdf" must
     * survive the round trip because both transfer directions surface this name
     * back to the phone.
     */
    public String sanitize(String raw) {
        String n = (raw == null || raw.trim().isEmpty()) ? "unnamed.bin" : raw.trim();
        // no traversal
        n = n.replace('\\', '_').replace('/', '_');
        n = n.replace("..", "_");
        // drop control characters and shell / Windows-hostile punctuation
        n = n.replaceAll("[\\p{Cntrl}<>:\"|?*]", "_");
        // a leading dot would hide the file on unix
        n = n.replaceAll("^[ .]+", "");
        n = n.trim();
        if (n.isEmpty()) n = "unnamed.bin";
        if (n.length() > 120) {
            int dot = n.lastIndexOf('.');
            if (dot > 0 && n.length() - dot <= 12) {
                String ext = n.substring(dot);
                n = n.substring(0, 120 - ext.length()) + ext;
            } else {
                n = n.substring(0, 120);
            }
        }
        return n;
    }

    /** Resolve a non-colliding destination file for an incoming upload. */
    public synchronized File target(String rawName) {
        String safe = sanitize(rawName);
        File f = new File(dir, safe);
        if (!f.exists()) return f;
        String base = safe;
        String ext = "";
        int dot = safe.lastIndexOf('.');
        if (dot > 0) {
            base = safe.substring(0, dot);
            ext = safe.substring(dot);
        }
        int k = 1;
        while (f.exists() && k < 1000) {
            f = new File(dir, base + "(" + k + ")" + ext);
            k++;
        }
        return f;
    }

    /** Newest first. */
    public synchronized JSONArray listJson() {
        File[] files = dir.listFiles();
        JSONArray arr = new JSONArray();
        if (files == null) return arr;
        Arrays.sort(files, new Comparator<File>() {
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        for (File f : files) {
            if (!f.isFile()) continue;
            JSONObject o = new JSONObject();
            try {
                o.put("name", f.getName());
                o.put("size", f.length());
                o.put("time", sdf.format(new Date(f.lastModified())));
                o.put("type", typeOf(f.getName()));
            } catch (Exception e) {
                Log.e(TAG, "json", e);
            }
            arr.put(o);
        }
        return arr;
    }

    public static String typeOf(String name) {
        String n = name.toLowerCase(Locale.US);
        int d = n.lastIndexOf('.');
        String ext = (d >= 0) ? n.substring(d + 1) : "";
        if (ext.equals("apk")) return "APK";
        if (ext.equals("zip") || ext.equals("rar") || ext.equals("7z")) return "ZIP";
        if (ext.equals("pdf")) return "PDF";
        if (ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg") || ext.equals("gif") || ext.equals("webp")) return "PNG";
        if (ext.equals("mp4") || ext.equals("mkv") || ext.equals("avi") || ext.equals("mov")) return "MP4";
        if (ext.equals("doc") || ext.equals("docx") || ext.equals("txt")) return "DOC";
        return "FILE";
    }

    public static String mimeOf(String name) {
        String t = typeOf(name);
        if ("APK".equals(t)) return "application/vnd.android.package-archive";
        if ("ZIP".equals(t)) return "application/zip";
        if ("PDF".equals(t)) return "application/pdf";
        if ("PNG".equals(t)) return "image/*";
        if ("MP4".equals(t)) return "video/*";
        if ("DOC".equals(t)) return "application/msword";
        return "*/*";
    }

    public synchronized int deleteAll() {
        File[] files = dir.listFiles();
        int n = 0;
        if (files == null) return 0;
        for (File f : files) {
            if (f.isFile() && f.delete()) n++;
        }
        return n;
    }

    public synchronized long totalSize() {
        File[] files = dir.listFiles();
        long t = 0;
        if (files == null) return 0;
        for (File f : files) if (f.isFile()) t += f.length();
        return t;
    }

    public File get(String name) {
        File f = new File(dir, sanitize(name));
        return f.exists() ? f : null;
    }
}
