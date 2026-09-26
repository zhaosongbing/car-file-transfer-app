package com.zsb.carfiletransfer;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Wi-Fi hotspot helpers.
 *
 * Two modes are supported:
 * 1. The car unit's own hotspot - its original SSID / password are read from the
 *    system configuration so the QR code matches what the driver already knows.
 * 2. A local-only hotspot created by the app (Android 8+), used as a fallback
 *    when the system configuration cannot be read or the system hotspot is off.
 */
public class SoftApManager {

    private static final String TAG = "SoftApManager";

    /** Access point credentials. */
    public static final class ApConfig {
        public String ssid;
        public String passphrase;
        public String security = "WPA";
        /** true when the credentials come from the car unit's saved configuration. */
        public boolean fromSystem;
        public boolean active;

        public boolean isValid() {
            return ssid != null && ssid.length() > 0;
        }

        public boolean isOpen() {
            return passphrase == null || passphrase.length() == 0;
        }
    }

    public interface Callback {
        void onStarted(ApConfig config);

        void onFailed(String reason);
    }

    private final Context ctx;
    private final WifiManager wifi;
    private WifiManager.LocalOnlyHotspotReservation reservation;

    public SoftApManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.wifi = (WifiManager) this.ctx.getSystemService(Context.WIFI_SERVICE);
    }

    // ---------------- system (car unit) configuration ----------------

    /**
     * Read the hotspot configuration the car unit already uses. Never throws -
     * returns null when the platform hides the API or the device has no stored AP.
     */
    public ApConfig readSystemConfig() {
        ApConfig cfg = readSoftApConfiguration();
        if (cfg == null) cfg = readLegacyWifiApConfiguration();
        if (cfg == null) cfg = readSoftApConfFile();
        return cfg;
    }

    /** Android 8+: WifiManager#getSoftApConfiguration() (hidden API). */
    private ApConfig readSoftApConfiguration() {
        if (wifi == null) return null;
        try {
            Method m = wifi.getClass().getMethod("getSoftApConfiguration");
            Object cfg = m.invoke(wifi);
            if (cfg == null) return null;
            ApConfig out = new ApConfig();
            out.ssid = invokeString(cfg, "getSsid");
            out.passphrase = invokeString(cfg, "getPassphrase");
            try {
                Method ms = cfg.getClass().getMethod("getSecurityType");
                Object v = ms.invoke(cfg);
                if (v instanceof Integer) {
                    int type = ((Integer) v).intValue();
                    out.security = type == 0 ? "nopass" : "WPA";   // 0 = OPEN
                }
            } catch (Throwable ignored) {
            }
            if (out.isOpen()) out.security = "nopass";
            out.fromSystem = true;
            return out.isValid() ? out : null;
        } catch (Throwable t) {
            Log.i(TAG, "getSoftApConfiguration unavailable: " + t);
            return null;
        }
    }

    /** Legacy: WifiManager#getWifiApConfiguration(). */
    private ApConfig readLegacyWifiApConfiguration() {
        if (wifi == null) return null;
        try {
            Method m = wifi.getClass().getMethod("getWifiApConfiguration");
            Object cfg = m.invoke(wifi);
            if (cfg == null) return null;
            ApConfig out = new ApConfig();
            String ssid = fieldString(cfg, "SSID");
            if (ssid != null) out.ssid = ssid.replace("\"", "");
            String key = fieldString(cfg, "preSharedKey");
            if (key != null) out.passphrase = key.replace("\"", "");
            if (out.isOpen()) out.security = "nopass";
            out.fromSystem = true;
            return out.isValid() ? out : null;
        } catch (Throwable t) {
            Log.i(TAG, "getWifiApConfiguration unavailable: " + t);
            return null;
        }
    }

    /** Last resort: parse the on-device softap config file (needs privileges). */
    private ApConfig readSoftApConfFile() {
        String[] paths = {
                "/data/misc/wifi/softap.conf",
                "/data/misc/wifi_hostapd/hostapd.conf",
                "/etc/wifi/softap.conf",
        };
        for (String p : paths) {
            try {
                java.io.File f = new java.io.File(p);
                if (!f.exists()) continue;
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
                ApConfig out = new ApConfig();
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("ssid=")) out.ssid = line.substring(5).trim();
                    else if (line.startsWith("wpa_passphrase=")) {
                        out.passphrase = line.substring(15).trim();
                    }
                }
                r.close();
                if (out.isValid()) {
                    if (out.isOpen()) out.security = "nopass";
                    out.fromSystem = true;
                    return out;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String invokeString(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    private String fieldString(Object target, String fieldName) {
        try {
            java.lang.reflect.Field f = target.getClass().getField(fieldName);
            Object v = f.get(target);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- local-only hotspot ----------------

    public boolean hasReservation() {
        return reservation != null;
    }

    public void startLocalOnly(final Callback cb) {
        if (wifi == null) {
            cb.onFailed("WIFI_SERVICE 不可用");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            cb.onFailed("系统版本过低（需 Android 8+）");
            return;
        }
        final Handler h = new Handler(Looper.getMainLooper());
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation res) {
                    reservation = res;
                    ApConfig cfg = new ApConfig();
                    try {
                        Object c = res.getSoftApConfiguration();
                        cfg.ssid = invokeString(c, "getSsid");
                        cfg.passphrase = invokeString(c, "getPassphrase");
                    } catch (Throwable t) {
                        Log.w(TAG, "read reservation config", t);
                    }
                    if (!cfg.isValid()) {
                        ApConfig sys = readSystemConfig();
                        if (sys != null) {
                            cfg.ssid = sys.ssid;
                            cfg.passphrase = sys.passphrase;
                            cfg.fromSystem = true;
                        }
                    }
                    if (cfg.isOpen()) cfg.security = "nopass";
                    cfg.active = true;
                    cb.onStarted(cfg);
                }

                @Override
                public void onFailed(int reason) {
                    cb.onFailed("热点启动失败（code=" + reason + "）");
                }
            }, h);
        } catch (SecurityException se) {
            cb.onFailed("缺少定位 / Wi-Fi 权限");
        } catch (Exception e) {
            cb.onFailed(String.valueOf(e.getMessage()));
        }
    }

    public void stopLocalOnly() {
        try {
            if (reservation != null) reservation.close();
        } catch (Exception e) {
            Log.w(TAG, "close reservation", e);
        }
        reservation = null;
    }

    // ---------------- Wi-Fi QR payload ----------------

    /** Build a {@code WIFI:T:..;S:..;P:..;;} payload scannable by camera apps. */
    public static String wifiQrPayload(ApConfig cfg) {
        if (cfg == null || !cfg.isValid()) return null;
        String type = cfg.isOpen() ? "nopass" : cfg.security;
        StringBuilder sb = new StringBuilder("WIFI:");
        sb.append("T:").append(type).append(';');
        sb.append("S:").append(escape(cfg.ssid)).append(';');
        if (!cfg.isOpen()) {
            sb.append("P:").append(escape(cfg.passphrase)).append(';');
        }
        sb.append(';');
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    // ---------------- addresses ----------------

    public static List<String> getIpAddresses() {
        List<String> out = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a.isLoopbackAddress() || !(a instanceof Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    if (ip != null && !out.contains(ip)) out.add(ip);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "enumerate interfaces", e);
        }
        return out;
    }

    public static String getPreferredIp() {
        List<String> all = getIpAddresses();
        for (String ip : all) {
            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                return ip;
            }
        }
        return all.isEmpty() ? null : all.get(0);
    }
}
