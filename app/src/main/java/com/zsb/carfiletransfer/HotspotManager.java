package com.zsb.carfiletransfer;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Creates a local-only hotspot (Android 8+) so the phone can join the car unit
 * directly, and discovers the IPv4 address to advertise in the QR code.
 */
public class HotspotManager {

    private static final String TAG = "HotspotManager";

    public interface Callback {
        void onStarted(String ssid, String passphrase);
        void onFailed(String reason);
    }

    private final WifiManager wifi;
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private String ssid;
    private String passphrase;

    public HotspotManager(Context ctx) {
        wifi = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    public boolean isActive() {
        return reservation != null;
    }

    public String getSsid() {
        return ssid;
    }

    public String getPassphrase() {
        return passphrase;
    }

    public void startHotspot(final Callback cb) {
        if (wifi == null) {
            cb.onFailed("WIFI_SERVICE 不可用");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            cb.onFailed("系统版本过低(需 Android 8+)");
            return;
        }
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation res) {
                    reservation = res;
                    readConfig(res);
                    Log.i(TAG, "hotspot started ssid=" + ssid);
                    cb.onStarted(ssid, passphrase);
                }

                @Override
                public void onFailed(int reason) {
                    cb.onFailed("热点启动失败(code=" + reason + ")");
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (SecurityException se) {
            cb.onFailed("缺少定位/Wi-Fi 权限");
        } catch (Exception e) {
            cb.onFailed(String.valueOf(e.getMessage()));
        }
    }

    /** Best-effort: the config getter differs across API levels, so never throw. */
    private void readConfig(WifiManager.LocalOnlyHotspotReservation res) {
        try {
            Object cfg = res.getSoftApConfiguration();
            java.lang.reflect.Method mSsid = cfg.getClass().getMethod("getSsid");
            java.lang.reflect.Method mPass = cfg.getClass().getMethod("getPassphrase");
            Object s = mSsid.invoke(cfg);
            Object p = mPass.invoke(cfg);
            if (s != null) ssid = String.valueOf(s);
            if (p != null) passphrase = String.valueOf(p);
        } catch (Throwable t) {
            Log.w(TAG, "cannot read softap config: " + t);
        }
    }

    public void stopHotspot() {
        try {
            if (reservation != null) {
                reservation.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "close reservation", e);
        }
        reservation = null;
        ssid = null;
        passphrase = null;
    }

    /** All usable IPv4 addresses (loopback excluded). */
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

    /** Prefer a private (LAN) address. */
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
