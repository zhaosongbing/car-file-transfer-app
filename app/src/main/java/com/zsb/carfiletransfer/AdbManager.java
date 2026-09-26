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

        public boolean isConnected() {
            return !clients.isEmpty();
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

        StringBuilder d = new StringBuilder();
        d.append(s.daemonRunning ? "adbd 运行中" : "adbd 未运行");
        d.append(" · 端口 ").append(s.tcpPort);
        if (s.listening) d.append(" · 已监听");
        if (s.isConnected()) d.append(" · 已连接 ").append(join(s.clients));
        s.detail = d.toString();
        return s;
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
