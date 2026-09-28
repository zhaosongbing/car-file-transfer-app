package com.zsb.carfiletransfer;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Wi-Fi hotspot helpers.
 *
 * <p>The car unit's <em>own</em> hotspot is the primary path: its saved SSID /
 * password are read from the system configuration so the generated QR code
 * matches the access point the driver already knows. Opening it requires
 * privileged APIs, therefore {@link #openHotspot} walks a strategy chain from
 * the most native to the least:</p>
 *
 * <ol>
 *   <li>{@code ConnectivityManager#startTethering} - real system tethering</li>
 *   <li>{@code WifiManager#startSoftAp} - system soft AP (Android 11+)</li>
 *   <li>{@code WifiManager#setWifiApEnabled} - legacy soft AP</li>
 *   <li>{@code WifiManager#startLocalOnlyHotspotWithConfiguration} - app owned
 *       AP that still uses the car unit's credentials (Android 13+)</li>
 *   <li>{@code WifiManager#startLocalOnlyHotspot} - app owned AP, random SSID</li>
 * </ol>
 *
 * <p>Everything outside the public SDK is reached reflectively and every step
 * is guarded, so a missing permission degrades to the next strategy instead of
 * crashing.</p>
 */
public class SoftApManager {

    private static final String TAG = "SoftApManager";

    /** How the access point was brought up. */
    public static final String MODE_SYSTEM = "system";
    public static final String MODE_LOCAL = "local";

    /** WifiManager soft AP states. */
    public static final int AP_STATE_DISABLING = 10;
    public static final int AP_STATE_DISABLED = 11;
    public static final int AP_STATE_ENABLING = 12;
    public static final int AP_STATE_ENABLED = 13;
    public static final int AP_STATE_FAILED = 14;

    /** ConnectivityManager#TETHERING_WIFI. */
    private static final int TETHERING_WIFI = 0;
    /** SoftApConfiguration#SECURITY_TYPE_WPA2_PSK. */
    private static final int SECURITY_WPA2_PSK = 1;

    /** Access point credentials. */
    public static final class ApConfig {
        public String ssid;
        public String passphrase;
        public String security = "WPA";
        /** true when the credentials come from the car unit's saved configuration. */
        public boolean fromSystem;
        public boolean active;
        /** how the AP was started. */
        public String mode = MODE_SYSTEM;

        public boolean isValid() {
            return ssid != null && ssid.length() > 0;
        }

        public boolean isOpen() {
            return passphrase == null || passphrase.length() == 0;
        }

        public ApConfig copy() {
            ApConfig c = new ApConfig();
            c.ssid = ssid;
            c.passphrase = passphrase;
            c.security = security;
            c.fromSystem = fromSystem;
            c.active = active;
            c.mode = mode;
            return c;
        }
    }

    public interface Callback {
        void onStarted(ApConfig config, String mode);

        void onFailed(String reason);
    }

    private final Context ctx;
    private final WifiManager wifi;
    private final Handler main = new Handler(Looper.getMainLooper());
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

    // ---------------- state ----------------

    /** WifiManager#getWifiApState() (hidden). */
    public int getApState() {
        if (wifi == null) return AP_STATE_DISABLED;
        try {
            Method m = wifi.getClass().getMethod("getWifiApState");
            Object v = m.invoke(wifi);
            return v instanceof Integer ? ((Integer) v).intValue() : AP_STATE_DISABLED;
        } catch (Throwable t) {
            Log.i(TAG, "getWifiApState unavailable: " + t);
            return AP_STATE_DISABLED;
        }
    }

    public boolean isSystemApEnabled() {
        return getApState() == AP_STATE_ENABLED;
    }

    public boolean hasReservation() {
        return reservation != null;
    }

    /**
     * Interfaces that only carry a soft AP (never the station interface), so an
     * up interface with an IPv4 address is a strong signal that a hotspot is
     * running - even when the platform hides {@code getWifiApState()} behind
     * the non-SDK interface ban.
     */
    private static final String[] AP_ONLY_INTERFACES = {
            "ap0", "ap1", "swlan0", "softap0", "wlan1", "wl0.1"
    };

    /** Permission-free, reflection-free hotspot detection via the AP interface. */
    public static boolean isApInterfaceUp() {
        for (String name : AP_ONLY_INTERFACES) {
            try {
                NetworkInterface ni = NetworkInterface.getByName(name);
                if (ni == null || !ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a.isLoopbackAddress() || !(a instanceof Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    if (ip != null && ip.length() > 0) return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /**
     * True when any access point owned by the device is up. Combines the three
     * independent signals so a hotspot started by the user in system settings is
     * detected as well as one this app owns.
     */
    public boolean isHotspotUp() {
        return hasReservation() || isSystemApEnabled() || isApInterfaceUp();
    }

    /** Live credentials, preferring the running reservation. */
    public ApConfig liveConfig() {
        if (reservation != null) {
            try {
                Object c = reservation.getSoftApConfiguration();
                if (c != null) {
                    ApConfig out = new ApConfig();
                    out.ssid = invokeString(c, "getSsid");
                    out.passphrase = invokeString(c, "getPassphrase");
                    out.mode = MODE_LOCAL;
                    out.active = true;
                    if (out.isValid()) {
                        if (out.isOpen()) out.security = "nopass";
                        return out;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "read reservation config", t);
            }
        }
        ApConfig sys = readSystemConfig();
        if (sys != null) {
            sys.active = isSystemApEnabled();
            sys.mode = MODE_SYSTEM;
            return sys;
        }
        return null;
    }

    // ---------------- opening the device hotspot ----------------

    /**
     * Open the device hotspot with the given credentials. Always reports through
     * the callback, on the calling (main) thread.
     */
    public void openHotspot(final ApConfig desired, final Callback cb) {
        if (isHotspotUp()) {
            ApConfig live = liveConfig();
            if (live != null && live.isValid()) {
                reportStarted(cb, live, live.mode);
                return;
            }
            reportStarted(cb, desired, MODE_SYSTEM);
            return;
        }

        boolean hasCreds = desired != null && desired.isValid();

        // 1 - real system tethering (privileged, works on system-signed builds)
        if (tryStartTethering()) {
            waitForAp(desired, cb, MODE_SYSTEM);
            return;
        }
        // 2 - system soft AP with the car unit's own credentials
        if (hasCreds && tryStartSoftAp(desired)) {
            waitForAp(desired, cb, MODE_SYSTEM);
            return;
        }
        // 3 - legacy soft AP
        if (hasCreds && tryLegacySetWifiApEnabled(desired)) {
            waitForAp(desired, cb, MODE_SYSTEM);
            return;
        }
        // 4 - app owned hotspot: still uses the car unit's credentials when the
        //     platform allows it, otherwise a generated pair (reported back so
        //     the QR code always matches the live access point)
        startLocalOnlyWithConfig(desired, cb);
    }

    private void reportStarted(final Callback cb, final ApConfig cfg, final String mode) {
        main.post(new Runnable() {
            public void run() {
                if (cfg != null) {
                    cfg.active = true;
                    cfg.mode = mode;
                }
                cb.onStarted(cfg, mode);
            }
        });
    }

    private void reportFailed(final Callback cb, final String reason) {
        main.post(new Runnable() {
            public void run() {
                cb.onFailed(reason);
            }
        });
    }

    /** ConnectivityManager#startTethering - the real system hotspot. */
    private boolean tryStartTethering() {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Class<?> cbCls = null;
        try {
            cbCls = Class.forName("android.net.ConnectivityManager$OnStartTetheringCallback");
        } catch (Throwable t) {
            Log.i(TAG, "OnStartTetheringCallback missing: " + t);
        }
        // Android 10+: startTethering(int, boolean, Executor, OnStartTetheringCallback)
        if (cbCls != null) {
            try {
                Method m = cm.getClass().getMethod("startTethering", int.class, boolean.class,
                        Executor.class, cbCls);
                m.invoke(cm, TETHERING_WIFI, false, directExecutor(), tetheringCallback(cbCls));
                Log.i(TAG, "startTethering (executor) invoked");
                return true;
            } catch (Throwable t) {
                Log.i(TAG, "startTethering(executor) unavailable: " + t);
            }
        }
        // Android 7-9: startTethering(int, boolean, OnStartTetheringCallback, Handler)
        if (cbCls != null) {
            try {
                Method m = cm.getClass().getMethod("startTethering", int.class, boolean.class,
                        cbCls, Handler.class);
                m.invoke(cm, TETHERING_WIFI, false, tetheringCallback(cbCls), main);
                Log.i(TAG, "startTethering (handler) invoked");
                return true;
            } catch (Throwable t) {
                Log.i(TAG, "startTethering(handler) unavailable: " + t);
            }
        }
        return false;
    }

    private Object tetheringCallback(Class<?> cbCls) {
        if (!cbCls.isInterface()) return null;
        try {
            return Proxy.newProxyInstance(cbCls.getClassLoader(), new Class<?>[]{cbCls},
                    new InvocationHandler() {
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            Log.i(TAG, "tethering callback: " + method.getName());
                            return null;
                        }
                    });
        } catch (Throwable t) {
            return null;
        }
    }

    private Executor directExecutor() {
        return new Executor() {
            public void execute(Runnable command) {
                command.run();
            }
        };
    }

    /** WifiManager#startSoftAp(SoftApConfiguration) - Android 11+, hidden. */
    private boolean tryStartSoftAp(ApConfig desired) {
        if (wifi == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        Object sac = buildSoftApConfiguration(desired);
        if (sac == null) return false;
        try {
            Method m = wifi.getClass().getMethod("startSoftAp",
                    Class.forName("android.net.wifi.SoftApConfiguration"));
            m.invoke(wifi, sac);
            Log.i(TAG, "startSoftAp invoked");
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "startSoftAp unavailable: " + t);
            return false;
        }
    }

    /** Legacy WifiManager#setWifiApEnabled(WifiConfiguration, boolean). */
    private boolean tryLegacySetWifiApEnabled(ApConfig desired) {
        if (wifi == null || desired == null || !desired.isValid()) return false;
        try {
            Class<?> wcCls = Class.forName("android.net.wifi.WifiConfiguration");
            Object wc = wcCls.newInstance();
            setField(wc, "SSID", "\"" + desired.ssid + "\"");
            if (!desired.isOpen()) {
                setField(wc, "preSharedKey", "\"" + desired.passphrase + "\"");
            }
            setField(wc, "hiddenSSID", Boolean.FALSE);
            Method m = wifi.getClass().getMethod("setWifiApEnabled", wcCls, boolean.class);
            Object r = m.invoke(wifi, wc, Boolean.TRUE);
            if (r instanceof Boolean && !((Boolean) r).booleanValue()) return false;
            Log.i(TAG, "setWifiApEnabled invoked");
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "setWifiApEnabled unavailable: " + t);
            return false;
        }
    }

    private void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getField(name);
            f.set(target, value);
        } catch (Throwable ignored) {
        }
    }

    /** Build a public {@code SoftApConfiguration} from an {@link ApConfig}. */
    private Object buildSoftApConfiguration(ApConfig cfg) {
        if (cfg == null || !cfg.isValid()) return null;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        try {
            Class<?> bCls = Class.forName("android.net.wifi.SoftApConfiguration$Builder");
            Object b = bCls.getConstructor().newInstance();
            Object r = bCls.getMethod("setSsid", String.class).invoke(b, cfg.ssid);
            if (r != null) b = r;
            if (!cfg.isOpen() && cfg.passphrase != null && cfg.passphrase.length() >= 8) {
                r = bCls.getMethod("setPassphrase", String.class, int.class)
                        .invoke(b, cfg.passphrase, SECURITY_WPA2_PSK);
                if (r != null) b = r;
            }
            return b.getClass().getMethod("build").invoke(b);
        } catch (Throwable t) {
            Log.i(TAG, "build SoftApConfiguration failed: " + t);
            return null;
        }
    }

    /**
     * Poll until any hotspot signal turns on. Uses all three detection paths so
     * a hotspot brought up outside this app is recognised too.
     */
    private void waitForAp(final ApConfig desired, final Callback cb, final String mode) {
        new Thread(new Runnable() {
            public void run() {
                boolean up = false;
                for (int i = 0; i < 24; i++) {
                    if (isSystemApEnabled() || isApInterfaceUp()) {
                        up = true;
                        break;
                    }
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                if (up) {
                    ApConfig live = liveConfig();
                    if (live == null || !live.isValid()) {
                        live = desired != null ? desired.copy() : new ApConfig();
                    }
                    final ApConfig out = live;
                    reportStarted(cb, out, mode);
                } else {
                    reportFailed(cb, "系统热点未开启（缺少系统权限）");
                }
            }
        }).start();
    }

    // ---------------- local-only hotspot ----------------

    /** App owned AP that keeps the car unit's credentials when possible. */
    private void startLocalOnlyWithConfig(final ApConfig desired, final Callback cb) {
        if (wifi == null) {
            reportFailed(cb, "WIFI_SERVICE 不可用");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            reportFailed(cb, "系统版本过低（需 Android 8+）");
            return;
        }
        Object sac = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sac = buildSoftApConfiguration(desired);
        }
        if (sac != null) {
            try {
                Method m = wifi.getClass().getMethod("startLocalOnlyHotspotWithConfiguration",
                        Class.forName("android.net.wifi.SoftApConfiguration"), Executor.class,
                        WifiManager.LocalOnlyHotspotCallback.class);
                m.invoke(wifi, sac, directExecutor(), localCallback(cb));
                Log.i(TAG, "startLocalOnlyHotspotWithConfiguration invoked");
                return;
            } catch (Throwable t) {
                Log.i(TAG, "startLocalOnlyHotspotWithConfiguration unavailable: " + t);
            }
        }
        startLocalOnly(cb);
    }

    public void startLocalOnly(final Callback cb) {
        if (wifi == null) {
            reportFailed(cb, "WIFI_SERVICE 不可用");
            return;
        }
        try {
            wifi.startLocalOnlyHotspot(localCallback(cb), main);
        } catch (SecurityException se) {
            reportFailed(cb, "缺少定位 / Wi-Fi 权限");
        } catch (Throwable t) {
            reportFailed(cb, String.valueOf(t.getMessage()));
        }
    }

    private WifiManager.LocalOnlyHotspotCallback localCallback(final Callback cb) {
        return new WifiManager.LocalOnlyHotspotCallback() {
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
                cfg.mode = MODE_LOCAL;
                reportStarted(cb, cfg, MODE_LOCAL);
            }

            @Override
            public void onFailed(int reason) {
                String hint;
                switch (reason) {
                    case 1:      // ERROR_GENERIC
                        hint = "热点启动失败：系统拒绝创建接入点，请确认已授予定位权限并打开位置信息";
                        break;
                    case 2:      // ERROR_INCOMPATIBLE_MODE
                        hint = "热点启动失败：当前 Wi-Fi 模式不兼容（请先关闭 Wi-Fi 或已有的网络共享）";
                        break;
                    case 3:      // ERROR_TETHERING_DISALLOWED
                        hint = "热点启动失败：系统策略禁止创建热点";
                        break;
                    default:
                        hint = "热点启动失败（code=" + reason + "）";
                        break;
                }
                reportFailed(cb, hint);
            }

            @Override
            public void onStopped() {
                reservation = null;
            }
        };
    }

    // ---------------- closing ----------------

    /**
     * Best-effort close of every hotspot this process can reach: the app owned
     * reservation first, then the system soft AP, then system tethering.
     * Never throws.
     */
    public void closeHotspot() {
        if (reservation != null) {
            stopLocalOnly();
        }
        boolean handled = false;
        if (wifi != null) {
            try {
                Method m = wifi.getClass().getMethod("stopSoftAp");
                m.invoke(wifi);
                Log.i(TAG, "stopSoftAp invoked");
                handled = true;
            } catch (Throwable t) {
                Log.i(TAG, "stopSoftAp unavailable: " + t);
            }
            if (!handled) {
                try {
                    Method m = wifi.getClass().getMethod("setWifiApEnabled",
                            Class.forName("android.net.wifi.WifiConfiguration"), boolean.class);
                    m.invoke(wifi, null, Boolean.FALSE);
                    Log.i(TAG, "setWifiApEnabled(false) invoked");
                    handled = true;
                } catch (Throwable t) {
                    Log.i(TAG, "setWifiApEnabled(false) unavailable: " + t);
                }
            }
        }
        if (!handled) {
            try {
                ConnectivityManager cm =
                        (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    Method m = cm.getClass().getMethod("stopTethering", int.class);
                    m.invoke(cm, TETHERING_WIFI);
                    Log.i(TAG, "stopTethering invoked");
                }
            } catch (Throwable t) {
                Log.i(TAG, "stopTethering unavailable: " + t);
            }
        }
    }

    /**
     * Close on a worker thread, then re-check with every detection path and
     * report the outcome. Returns through {@code onStarted} when the hotspot is
     * really down and {@code onFailed} when the platform keeps it up (a hotspot
     * owned by the system cannot always be stopped by a third party app).
     */
    public void closeHotspotAsync(final Callback cb) {
        new Thread(new Runnable() {
            public void run() {
                closeHotspot();
                boolean down = false;
                for (int i = 0; i < 8; i++) {
                    if (!isHotspotUp()) {
                        down = true;
                        break;
                    }
                    try {
                        Thread.sleep(400L);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                if (down) {
                    reportStarted(cb, null, MODE_SYSTEM);
                } else {
                    reportFailed(cb, "系统热点由系统托管，应用无法直接关闭");
                }
            }
        }).start();
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

    /** Interfaces that usually carry a soft AP. */
    private static final String[] AP_INTERFACES = {
            "ap0", "ap1", "swlan0", "softap0", "wlan1", "wl0.1", "wlan0"
    };

    /** Subnets the platform hands out for tethering / local-only hotspots. */
    private static final String[] AP_SUBNETS = {
            "192.168.43.", "192.168.49.", "192.168.137."
    };

    /**
     * The address a phone should use to reach this device. Prefers the address
     * bound to the access point interface so the QR stays correct when the unit
     * is simultaneously joined to another Wi-Fi network.
     */
    public static String getApIp() {
        List<String> all = getIpAddresses();
        // 1 - the classic Android soft AP subnets win outright, no matter which
        //     interface name the board happens to use
        for (String p : AP_SUBNETS) {
            for (String ip : all) {
                if (ip.startsWith(p)) return ip;
            }
        }
        // 2 - otherwise trust the well known access point interface names
        for (String name : AP_INTERFACES) {
            try {
                NetworkInterface ni = NetworkInterface.getByName(name);
                if (ni == null || !ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a.isLoopbackAddress() || !(a instanceof Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    if (ip != null && ip.length() > 0) return ip;
                }
            } catch (Exception ignored) {
            }
        }
        // 3 - any private address is better than none
        for (String ip : all) {
            if (isPrivate(ip)) return ip;
        }
        return all.isEmpty() ? null : all.get(0);
    }

    private static boolean isPrivate(String ip) {
        return ip.startsWith("192.168.") || ip.startsWith("10.")
                || ip.startsWith("172.16.") || ip.startsWith("172.17.")
                || ip.startsWith("172.18.") || ip.startsWith("172.19.")
                || ip.startsWith("172.20.") || ip.startsWith("172.21.")
                || ip.startsWith("172.22.") || ip.startsWith("172.23.")
                || ip.startsWith("172.24.") || ip.startsWith("172.25.")
                || ip.startsWith("172.26.") || ip.startsWith("172.27.")
                || ip.startsWith("172.28.") || ip.startsWith("172.29.")
                || ip.startsWith("172.30.") || ip.startsWith("172.31.");
    }

    public static String getPreferredIp() {
        String ap = getApIp();
        if (ap != null) return ap;
        List<String> all = getIpAddresses();
        for (String ip : all) {
            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                return ip;
            }
        }
        return all.isEmpty() ? null : all.get(0);
    }
}
