package com.zsb.carfiletransfer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int PORT = 8899;
    private static final int REQ_PERM = 1001;

    private WebView webView;
    private FileRepository repo;
    private HotspotManager hotspot;
    private HttpFileServer server;

    private boolean hotspotActive = false;
    private String lastError = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        repo = new FileRepository(this);
        hotspot = new HotspotManager(this);

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new JsBridge(), "Android");

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");

        requestRuntimePermissions();
    }

    private void requestRuntimePermissions() {
        String[] perms;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms = new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.NEARBY_WIFI_DEVICES};
        } else {
            perms = new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        }
        requestPermissions(perms, REQ_PERM);
    }

    // ---------- status ----------

    private String deviceName() {
        String m = Build.MODEL;
        return (m == null || m.trim().isEmpty()) ? "车机" : m.trim();
    }

    private synchronized String statusJson() {
        JSONObject o = new JSONObject();
        try {
            String ip = HotspotManager.getPreferredIp();
            o.put("running", server != null && server.isRunning());
            o.put("ip", ip == null ? "" : ip);
            o.put("port", PORT);
            o.put("url", ip == null ? "" : ("http://" + ip + ":" + PORT));
            o.put("ssid", hotspot.getSsid() == null ? "" : hotspot.getSsid());
            o.put("pass", hotspot.getPassphrase() == null ? "" : hotspot.getPassphrase());
            o.put("device", deviceName());
            o.put("hotspot", hotspotActive);
            o.put("error", lastError);
        } catch (Exception e) {
            Log.e(TAG, "status", e);
        }
        return o.toString();
    }

    /** Push the current status into the page (called from any thread). */
    private void pushStatus() {
        final String json = statusJson();
        runOnUiThread(new Runnable() {
            public void run() {
                webView.evaluateJavascript("(function(j){try{window.__onStatus&&window.__onStatus(JSON.parse(j));}catch(e){}})(\'"
                        + json.replace("'", "\\'") + "\')", null);
            }
        });
    }

    // ---------- server lifecycle ----------

    private String readAsset(String name) {
        try {
            InputStream in = getAssets().open(name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "read asset " + name, e);
            return "<html><body>资源缺失: " + name + "</body></html>";
        }
    }

    private synchronized void startServerAndHotspot() {
        if (server != null && server.isRunning()) {
            pushStatus();
            return;
        }
        try {
            String page = readAsset("upload.html");
            server = new HttpFileServer(PORT, repo, page, deviceName());
            server.addListener(new HttpFileServer.ReceiveListener() {
                public void onFileReceived(String name, long size) {
                    pushStatus();
                }
            });
            server.start();
            lastError = "";
        } catch (Exception e) {
            lastError = "服务启动失败: " + e.getMessage();
            Log.e(TAG, "start server", e);
        }

        hotspot.startHotspot(new HotspotManager.Callback() {
            public void onStarted(String ssid, String passphrase) {
                hotspotActive = true;
                lastError = "";
                // the hotspot interface needs a moment to get its address
                webView.postDelayed(new Runnable() {
                    public void run() {
                        pushStatus();
                    }
                }, 1500);
                pushStatus();
            }

            public void onFailed(String reason) {
                hotspotActive = false;
                lastError = reason + "（仍可使用同一 Wi-Fi 下的地址传输）";
                pushStatus();
            }
        });

        pushStatus();
    }

    private synchronized void stopAll() {
        if (server != null) server.stop();
        server = null;
        hotspot.stopHotspot();
        hotspotActive = false;
        pushStatus();
    }

    // ---------- file actions ----------

    private void openFile(String name) {
        final File f = repo.get(name);
        if (f == null) {
            toast("文件不存在");
            return;
        }
        runOnUiThread(new Runnable() {
            public void run() {
                Uri uri = LocalFileProvider.uriFor(f.getName());
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, FileRepository.mimeOf(f.getName()));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(Intent.createChooser(i, "打开文件"));
                } catch (Exception e) {
                    toast("没有可打开此文件的应用");
                }
            }
        });
    }

    private void installApk(String name) {
        final File f = repo.get(name);
        if (f == null) {
            toast("文件不存在");
            return;
        }
        runOnUiThread(new Runnable() {
            public void run() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        && !getPackageManager().canRequestPackageInstalls()) {
                    toast("请先允许安装未知应用");
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())));
                    return;
                }
                Uri uri = LocalFileProvider.uriFor(f.getName());
                Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE);
                i.setData(uri);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                i.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
                try {
                    startActivity(i);
                } catch (Exception e) {
                    toast("无法启动安装");
                }
            }
        });
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---------- JS bridge ----------

    private class JsBridge {

        @JavascriptInterface
        public String startServer() {
            startServerAndHotspot();
            return statusJson();
        }

        @JavascriptInterface
        public void stopServer() {
            stopAll();
        }

        @JavascriptInterface
        public String getStatus() {
            return statusJson();
        }

        @JavascriptInterface
        public String getFiles() {
            return repo.listJson().toString();
        }

        @JavascriptInterface
        public void openFile(String name) {
            MainActivity.this.openFile(name);
        }

        @JavascriptInterface
        public void installApk(String name) {
            MainActivity.this.installApk(name);
        }

        @JavascriptInterface
        public int cleanAll() {
            int n = repo.deleteAll();
            pushStatus();
            return n;
        }

        @JavascriptInterface
        public String getDeviceName() {
            return deviceName();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        stopAll();
        super.onDestroy();
    }
}
