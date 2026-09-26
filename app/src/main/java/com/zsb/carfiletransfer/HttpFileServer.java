package com.zsb.carfiletransfer;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal dependency-free HTTP server.
 *   GET  /            -> the phone-side upload page (H5)
 *   GET  /info        -> {"device":..., "free":...}
 *   GET  /files       -> JSON array of received files
 *   POST /upload?name -> raw body saved to storage
 */
public class HttpFileServer {

    private static final String TAG = "HttpFileServer";

    public interface ReceiveListener {
        void onFileReceived(String name, long size);
    }

    private final int port;
    private final FileRepository repo;
    private final String uploadPage;
    private final String deviceName;
    private final CopyOnWriteArrayList<ReceiveListener> listeners = new CopyOnWriteArrayList<ReceiveListener>();

    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running = false;

    public HttpFileServer(int port, FileRepository repo, String uploadPage, String deviceName) {
        this.port = port;
        this.repo = repo;
        this.uploadPage = uploadPage;
        this.deviceName = deviceName;
    }

    public void addListener(ReceiveListener l) {
        listeners.add(l);
    }

    public synchronized void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        running = true;
        pool = Executors.newFixedThreadPool(8);
        Thread t = new Thread(new Runnable() {
            public void run() {
                acceptLoop();
            }
        }, "accept-loop");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "server started on " + port);
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        if (pool != null) pool.shutdownNow();
        Log.i(TAG, "server stopped");
    }

    public boolean isRunning() {
        return running;
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = serverSocket.accept();
                pool.execute(new Runnable() {
                    public void run() {
                        handle(s);
                    }
                });
            } catch (IOException e) {
                if (running) Log.e(TAG, "accept", e);
            }
        }
    }

    private void handle(Socket sock) {
        try {
            sock.setSoTimeout(60000);
            InputStream raw = sock.getInputStream();
            String headerBlock = readHeaderBlock(raw);
            if (headerBlock.isEmpty()) return;

            String[] lines = headerBlock.split("\r\n");
            String[] req = lines[0].split(" ");
            String method = req.length > 0 ? req[0] : "GET";
            String target = req.length > 1 ? req[1] : "/";

            Map<String, String> headers = new HashMap<String, String>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) {
                    headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.US),
                            lines[i].substring(c + 1).trim());
                }
            }

            String path = target;
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }

            if ("GET".equalsIgnoreCase(method)) {
                if (path.equals("/info")) {
                    JSONObject o = new JSONObject();
                    try {
                        o.put("device", deviceName);
                        o.put("free", repo.getDir().getFreeSpace());
                    } catch (Exception ignored) {
                    }
                    json(sock, o.toString());
                } else if (path.equals("/files")) {
                    json(sock, repo.listJson().toString());
                } else {
                    html(sock, uploadPage);
                }
            } else if ("POST".equalsIgnoreCase(method) && path.equals("/upload")) {
                String name = null;
                for (String kv : query.split("&")) {
                    if (kv.startsWith("name=")) {
                        name = URLDecoder.decode(kv.substring(5), "UTF-8");
                    }
                }
                int len = 0;
                try {
                    len = Integer.parseInt(headers.get("content-length"));
                } catch (Exception ignored) {
                }
                saveUpload(raw, len, name, sock);
            } else {
                respond(sock, 404, "Not Found", "text/plain", "404");
            }
        } catch (Exception e) {
            Log.e(TAG, "handle", e);
            try {
                respond(sock, 500, "Server Error", "text/plain", "500");
            } catch (Exception ignored) {
            }
        } finally {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void saveUpload(InputStream raw, int contentLength, String name, Socket sock) throws IOException {
        File out = repo.target(name);
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[16384];
        long total = 0;
        try {
            if (contentLength > 0) {
                int remaining = contentLength;
                while (remaining > 0) {
                    int n = raw.read(buf, 0, Math.min(buf.length, remaining));
                    if (n < 0) break;
                    fos.write(buf, 0, n);
                    total += n;
                    remaining -= n;
                }
            } else {
                int n;
                while ((n = raw.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    total += n;
                }
            }
        } finally {
            fos.flush();
            fos.close();
        }
        Log.i(TAG, "received " + out.getName() + " (" + total + " bytes)");
        for (ReceiveListener l : listeners) {
            l.onFileReceived(out.getName(), total);
        }
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("name", out.getName());
            o.put("size", total);
        } catch (Exception ignored) {
        }
        json(sock, o.toString());
    }

    /** Read until the blank line; byte-by-byte so we never consume body bytes. */
    private static String readHeaderBlock(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int b3 = 0, b2 = 0, b1 = 0, cur;
        while ((cur = in.read()) != -1) {
            bos.write(cur);
            if (b3 == '\r' && b2 == '\n' && b1 == '\r' && cur == '\n') break;
            b3 = b2;
            b2 = b1;
            b1 = cur;
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static void json(Socket s, String body) throws IOException {
        respond(s, 200, "OK", "application/json; charset=utf-8", body);
    }

    private static void html(Socket s, String body) throws IOException {
        respond(s, 200, "OK", "text/html; charset=utf-8", body);
    }

    private static void respond(Socket s, int code, String status, String type, String body) throws IOException {
        byte[] payload = body.getBytes("UTF-8");
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(" ").append(status).append("\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        sb.append("Content-Length: ").append(payload.length).append("\r\n");
        sb.append("Connection: close\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("\r\n");
        OutputStream os = s.getOutputStream();
        os.write(sb.toString().getBytes("UTF-8"));
        os.write(payload);
        os.flush();
    }
}
