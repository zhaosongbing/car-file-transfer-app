package com.zsb.carfiletransfer;

import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * ADB helpers: report whether adbd is running / reachable over TCP, and install
 * APKs through the shell ({@code pm install}), which is how ADB itself installs.
 */
public final class AdbManager {

    private static final String TAG = "AdbManager";
    public static final int DEFAULT_TCP_PORT = 5555;

    private AdbManager() {
    }

    /** Snapshot of the adb state on the device. */
    public static final class AdbStatus {
        public boolean daemonRunning;
        public boolean tcpEnabled;
        public int tcpPort = DEFAULT_TCP_PORT;
        public boolean listening;
        public final List<String> clients = new ArrayList<String>();
        public String detail = "";
        /** True when a wired debugging session looks live (USB gadget + cable). */
        public boolean usbAdb;
        public String usbState = "";

        /**
         * Wireless clients count as connected, and so does a wired session:
         * there is no socket to observe for a USB transport, so the USB state
         * plus an attached cable are the closest signals available to an app.
         */
        public boolean isConnected() {
            return !clients.isEmpty() || usbAdb;
        }

        /** Which transport answered, for display purposes. */
        public String transport() {
            if (usbAdb) return "USB";
            if (!clients.isEmpty()) return "TCP:" + tcpPort;
            return "";
        }
    }

    /** Result of an install attempt. */
    public static final class InstallResult {
        public boolean success;
        public String summary;
        public String raw;
        public String command;

        InstallResult(boolean success, String summary, String raw, String command) {
            this.success = success;
            this.summary = summary;
            this.raw = raw;
            this.command = command;
        }
    }

    // ---------------- status ----------------

    public static AdbStatus query() {
        AdbStatus s = new AdbStatus();

        String svc = prop("init.svc.adbd");
        s.daemonRunning = "running".equalsIgnoreCase(svc);

        String usbConfig = prop("persist.sys.usb.config");
        String port = prop("service.adb.tcp.port");
        if (port == null || port.length() == 0) port = prop("persist.adb.tcp.port");
        if (port != null && port.matches("\\d+")) {
            s.tcpPort = Integer.parseInt(port);
            s.tcpEnabled = s.tcpPort > 0;
        }
        if (usbConfig != null && usbConfig.contains("adb")) {
            s.daemonRunning = s.daemonRunning || true;
        }

        List<String[]> listening = new ArrayList<String[]>();
        List<String[]> established = new ArrayList<String[]>();
        scanSockets("/proc/net/tcp", listening, established);
        scanSockets("/proc/net/tcp6", listening, established);

        String target = portHex(s.tcpPort);
        for (String[] row : listening) {
            if (row[1].equalsIgnoreCase(target)) {
                s.listening = true;
                break;
            }
        }
        for (String[] row : established) {
            if (row[1].equalsIgnoreCase(target)) {
                String peer = decodeIp(row[2]);
                if (peer != null && !s.clients.contains(peer)) s.clients.add(peer);
            }
        }

        // Wired transport: the USB gadget only exposes adb while a host is
        // attached and has negotiated the function, so combine that state with
        // a live supply line. If the sysfs line cannot be read we trust the
        // USB state alone rather than hiding the option.
        String usbState = prop("sys.usb.state");
        if (usbState == null || usbState.length() == 0) {
            usbState = prop("persist.sys.usb.config");
        }
        s.usbState = usbState == null ? "" : usbState;
        boolean adbFunction = s.usbState.contains("adb");
        Boolean attached = usbAttached();
        s.usbAdb = adbFunction && (attached == null || attached.booleanValue());

        StringBuilder d = new StringBuilder();
        d.append(s.daemonRunning ? "adbd 运行中" : "adbd 未运行");
        d.append(" · 端口 ").append(s.tcpPort);
        if (s.listening) d.append(" · 已监听");
        if (s.usbAdb) d.append(" · 有线已连接");
        if (!s.clients.isEmpty()) d.append(" · 无线已连接 ").append(join(s.clients));
        s.detail = d.toString();
        return s;
    }

    /**
     * Whether something is plugged into USB. Returns null when the sysfs line is
     * unreadable, letting the caller fall back to the USB state alone.
     */
    private static Boolean usbAttached() {
        String[] paths = {
                "/sys/class/power_supply/usb/online",
                "/sys/class/power_supply/usb/present",
        };
        for (String p : paths) {
            String v = readFirstLine(p);
            if (v != null && v.length() > 0) return Boolean.valueOf("1".equals(v.trim()));
        }
        return null;
    }

    private static String readFirstLine(String path) {
        BufferedReader r = null;
        try {
            File f = new File(path);
            if (!f.exists()) return null;
            r = new BufferedReader(new FileReader(f));
            return r.readLine();
        } catch (Exception e) {
            return null;
        } finally {
            close(r);
        }
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static String portHex(int port) {
        String h = Integer.toHexString(port).toUpperCase();
        while (h.length() < 4) h = "0" + h;
        return h;
    }

    /** Parse /proc/net/tcp* rows, collecting [localIp, localPort, remoteIp]. */
    private static void scanSockets(String path, List<String[]> listening,
                                    List<String[]> established) {
        BufferedReader r = null;
        try {
            File f = new File(path);
            if (!f.exists()) return;
            r = new BufferedReader(new FileReader(f));
            String line = r.readLine();   // header
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 4) continue;
                int idx = p[1].indexOf(':');
                if (idx < 0) continue;
                String localIp = decodeIp(p[1].substring(0, idx));
                String localPort = p[1].substring(idx + 1);
                int ridx = p[2].indexOf(':');
                String remIp = ridx > 0 ? decodeIp(p[2].substring(0, ridx)) : null;
                if ("0A".equalsIgnoreCase(p[3])) {
                    listening.add(new String[]{localIp, localPort, remIp});
                } else if ("01".equalsIgnoreCase(p[3])) {
                    established.add(new String[]{localIp, localPort, remIp});
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "scan " + path, e);
        } finally {
            close(r);
        }
    }

    /** /proc/net/tcp stores addresses as little-endian hex. */
    private static String decodeIp(String hex) {
        if (hex == null) return null;
        try {
            int len = hex.length();
            if (len == 8) {
                long v = Long.parseLong(hex, 16);
                return (v & 0xFF) + "." + ((v >> 8) & 0xFF) + "." + ((v >> 16) & 0xFF)
                        + "." + ((v >> 24) & 0xFF);
            }
            if (len > 8) {
                // IPv6: hex groups are little-endian 32-bit words
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i + 8 <= len; i += 8) {
                    String w = hex.substring(i, i + 8);
                    long v = Long.parseLong(w, 16);
                    int a = (int) (v & 0xFFFF);
                    int b = (int) ((v >> 16) & 0xFFFF);
                    if (sb.length() > 0) sb.append(':');
                    sb.append(Integer.toHexString(((a & 0xFF) << 8) | ((a >> 8) & 0xFF)));
                    sb.append(':');
                    sb.append(Integer.toHexString(((b & 0xFF) << 8) | ((b >> 8) & 0xFF)));
                }
                return sb.toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String prop(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Method m = c.getDeclaredMethod("get", String.class, String.class);
            Object v = m.invoke(null, key, "");
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable t) {
            return execGetProp(key);
        }
    }

    private static String execGetProp(String key) {
        BufferedReader r = null;
        try {
            Process p = new ProcessBuilder("getprop", key).start();
            r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String v = r.readLine();
            p.destroy();
            return v == null ? "" : v.trim();
        } catch (Exception e) {
            return "";
        } finally {
            close(r);
        }
    }

    // ---------------- hotspot credentials ----------------

    /** The hotspot credentials as stored by the system. */
    public static final class ApCredentialResult {
        public boolean ok;
        public String ssid;
        public String passphrase;
        public boolean open;
        public String source = "";
        public String detail = "";
    }

    /** Where hostapd keeps the running / saved soft AP setup, by Android era. */
    private static final String[] HOSTAPD_PATHS = {
            "/data/misc/wifi/hostapd/hostapd.conf",
            "/data/misc/apexdata/com.android.wifi/hostapd/hostapd.conf",
            "/data/misc/wifi/hostapd.conf",
            "/data/misc/wifi/softap.conf",
            "/etc/wifi/softap.conf",
    };

    /** Persistent Wi-Fi / soft AP configuration stores. */
    private static final String[] STORE_PATHS = {
            "/data/misc/wifi/WifiConfigStore.xml",
            "/data/misc/wifi/WifiConfigStoreSoftAp.xml",
            "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml",
            "/data/misc/apexdata/com.android.wifi/WifiConfigStoreSoftAp.xml",
    };

    /**
     * Read the unit's own hotspot name and password.
     *
     * <p>Those files live under {@code /data/misc/wifi}, which a normal third
     * party app cannot open - reading them by {@code File} silently fails. Going
     * through the shell instead lets an elevated context (ADB debugging units
     * are usually rooted, or at least grant {@code su}) answer the request,
     * which is exactly what running the same command over {@code adb shell}
     * would return. Falls back to the unprivileged command, so it also works on
     * permissive builds.</p>
     */
    public static ApCredentialResult readApCredentials() {
        ApCredentialResult r = new ApCredentialResult();
        for (String p : HOSTAPD_PATHS) {
            String text = dumpFile(p);
            if (text == null) continue;
            ApCredentialResult c = parseHostapd(text, p);
            if (c != null && c.ok) return c;
        }
        for (String p : STORE_PATHS) {
            String text = dumpFile(p);
            if (text == null) continue;
            ApCredentialResult c = parseConfigStore(text, p);
            if (c != null && c.ok) return c;
        }
        r.ok = false;
        r.detail = "无法读取系统热点配置（需要 root / 系统权限）";
        return r;
    }

    /** Read one protected file, trying an elevated context before giving up. */
    private static String dumpFile(String path) {
        String[] forms = {"su 0 cat " + path, "su -c cat " + path, "cat " + path};
        for (String cmd : forms) {
            String out = exec(cmd);
            if (out == null || out.length() == 0) continue;
            if (out.contains("Permission denied") || out.contains("No such file")
                    || out.contains("not found") || out.contains("su: ")) {
                continue;
            }
            return out;
        }
        return null;
    }

    private static ApCredentialResult parseHostapd(String text, String source) {
        String ssid = null;
        String psk = null;
        boolean wpa = false;
        String[] lines = text.split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("ssid=")) {
                ssid = line.substring(5).trim();
            } else if (line.startsWith("wpa_passphrase=")) {
                psk = line.substring(15).trim();
                wpa = true;
            } else if (line.startsWith("wpa=")) {
                wpa = wpa || !"0".equals(line.substring(4).trim());
            } else if (line.startsWith("wpa_key_mgmt=")) {
                wpa = true;
            }
        }
        if (ssid == null || ssid.length() == 0) return null;
        ApCredentialResult r = new ApCredentialResult();
        r.ok = true;
        r.ssid = ssid;
        r.passphrase = psk;
        r.open = !wpa || psk == null || psk.length() == 0;
        r.source = source;
        return r;
    }

    /** Pull SSID / PreSharedKey out of a WifiConfigStore document. */
    private static ApCredentialResult parseConfigStore(String text, String source) {
        int from = text.indexOf("<SoftAp");
        if (from < 0) from = 0;
        java.util.regex.Matcher mSsid =
                java.util.regex.Pattern.compile("name=\"SSID\"[^>]*>([^<]*)<")
                        .matcher(text);
        java.util.regex.Matcher mPsk =
                java.util.regex.Pattern.compile("name=\"PreSharedKey\"[^>]*>([^<]*)<")
                        .matcher(text);
        String ssid = firstAfter(mSsid, from);
        if (ssid == null) return null;
        String psk = firstAfter(mPsk, from);
        ApCredentialResult r = new ApCredentialResult();
        r.ok = true;
        r.ssid = unescape(ssid);
        r.passphrase = psk == null ? null : unescape(psk);
        r.open = r.passphrase == null || r.passphrase.length() == 0;
        r.source = source;
        return r;
    }

    /** First match at or after {@code from}, ignoring anything before it. */
    private static String firstAfter(java.util.regex.Matcher m, int from) {
        m.reset();
        while (m.find()) {
            if (m.start() >= from) return m.group(1).trim();
        }
        return null;
    }

    private static String unescape(String s) {
        if (s == null) return null;
        String v = s.replace("&quot;", "\"").replace("&amp;", "&");
        return v.replace("\"", "");
    }

    private static String exec(String cmd) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd + " 2>/dev/null");
            pb.redirectErrorStream(true);
            p = pb.start();
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            close(r);
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    // ---------------- install ----------------

    /** Install an APK through the shell, the same path ADB uses. */
    public static InstallResult install(File apk) {
        final String path = apk.getAbsolutePath();
        String[] commands = new String[]{
                "pm install -r -t --user 0 " + quote(path),
                "pm install -r " + quote(path),
                "/system/bin/adb install -r " + quote(path),
        };
        String lastRaw = "";
        for (String cmd : commands) {
            try {
                ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                StringBuilder out = new StringBuilder();
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
                int code = p.waitFor();
                close(r);
                lastRaw = out.toString();
                if (code == 0 || lastRaw.contains("Success")) {
                    return new InstallResult(true, "安装成功", lastRaw.trim(), cmd);
                }
                if (lastRaw.contains("INSTALL_FAILED")) {
                    return new InstallResult(false, parseFailure(lastRaw), lastRaw.trim(), cmd);
                }
            } catch (Exception e) {
                lastRaw = String.valueOf(e.getMessage());
            }
        }
        boolean denied = lastRaw.contains("SecurityException")
                || lastRaw.contains("Permission denial")
                || lastRaw.contains("not permitted");
        String summary = denied
                ? "无 shell 安装权限，请改用电脑 adb 安装或系统安装界面"
                : (lastRaw.length() == 0 ? "无法执行安装命令" : "安装失败");
        return new InstallResult(false, summary, lastRaw.trim(), commands[0]);
    }

    private static String parseFailure(String raw) {
        int s = raw.indexOf("INSTALL_FAILED");
        if (s < 0) return raw.trim();
        int e = raw.indexOf(']', s);
        String code = e > s ? raw.substring(s, e) : raw.substring(s);
        String friendly;
        if (code.contains("ALREADY_EXISTS")) friendly = "已存在同名应用";
        else if (code.contains("INVALID_APK")) friendly = "APK 文件无效";
        else if (code.contains("INSUFFICIENT_STORAGE")) friendly = "存储空间不足";
        else if (code.contains("VERSION_DOWNGRADE")) friendly = "版本低于已安装版本";
        else if (code.contains("NO_MATCHING_SIGNATURES")) friendly = "签名不一致";
        else if (code.contains("ABORTED")) friendly = "安装被中断";
        else friendly = code;
        return "安装失败：" + friendly;
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Try to switch adbd to TCP mode (needs root / system privileges). */
    public static InstallResult enableTcp(int port) {
        String cmd = "setprop service.adb.tcp.port " + port
                + "; setprop persist.adb.tcp.port " + port
                + "; stop adbd; start adbd";
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
            int code = p.waitFor();
            close(r);
            String raw = out.toString().trim();
            boolean ok = code == 0 && !raw.contains("Permission denial");
            return new InstallResult(ok,
                    ok ? ("已设置 adb 端口 " + port) : "无权限，需在系统设置中开启网络调试",
                    raw, cmd);
        } catch (Exception e) {
            return new InstallResult(false, "执行失败：" + e.getMessage(), "", cmd);
        }
    }

    public static String connectCommand(String ip, int port, String apkPath) {
        return "adb connect " + ip + ":" + port + "\n"
                + "adb install -r " + apkPath + "\n"
                + "# 设备型号: " + Build.MODEL;
    }

    private static void close(BufferedReader r) {
        try {
            if (r != null) r.close();
        } catch (Exception ignored) {
        }
    }
}
