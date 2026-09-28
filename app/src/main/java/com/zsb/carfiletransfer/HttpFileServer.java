package com.zsb.carfiletransfer;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal dependency-free HTTP server. Both transfer directions are served from
 * the same end point that the QR code points at:
 *
 * <pre>
 *   GET  /              -> the phone-side portal page (H5)
 *   GET  /info          -> {"device":..., "free":...}
 *   GET  /files         -> JSON array of the files stored on this device
 *   GET  /download?name -> the file itself (accepts Range)
 *   POST /upload?name   -> raw body saved to storage
 * </pre>
 */
public class HttpFileServer {

    private static final String TAG = "HttpFileServer";

    public static final int DEFAULT_PORT = 8899;

    private static final int BUFFER = 32768;

    /** Report upload progress at most every 128 KB. */
    private static final long PROGRESS_STEP = 131072L;

    public interface ReceiveListener {
        void onFileReceived(String name, long size);

        /** An upload has just started - lets the UI jump to the receive page. */
        void onTransferStart(String name, long size);

        /** Periodic progress while the body is being written to storage. */
        void onTransferProgress(String name, long received, long size);
    }

    /** Fired on the car side when the phone pushes a text snippet. */
    public interface TextListener {
        void onTextReceived(String text);
    }

    /** How far to walk up when the preferred port is already taken. */
    private static final int PORT_SCAN = 20;

    /** Header phase timeout - a peer that never finishes its request line. */
    private static final int TIMEOUT_HEADER_MS = 30000;

    private final int port;
    /** The port actually bound, which may differ when {@code port} was taken. */
    private int boundPort;
    private final FileRepository repo;
    private final String portalPage;
    private final String deviceName;
    private final CopyOnWriteArrayList<ReceiveListener> listeners = new CopyOnWriteArrayList<ReceiveListener>();
    private final CopyOnWriteArrayList<TextListener> textListeners = new CopyOnWriteArrayList<TextListener>();

    /** car -> phone: the latest text the car pushed, polled by the phone portal. */
    private volatile String textToPhone = "";
    /** phone -> car: the latest text the phone pushed, surfaced to the car UI. */
    private volatile String textToCar = "";

    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running = false;

    /** Most recent client IP that reached the server, plus when it did. A hit on
     *  the portal is a much faster "a device joined" signal than waiting for the
     *  ARP table to populate, so the car UI can flip to CONNECTED at once. */
    private volatile String lastClientIp = null;
    private volatile long lastClientTime = 0L;

    public HttpFileServer(int port, FileRepository repo, String portalPage, String deviceName) {
        this.port = port;
        this.boundPort = port;
        this.repo = repo;
        this.portalPage = portalPage;
        this.deviceName = deviceName;
    }

    public void addListener(ReceiveListener l) {
        listeners.add(l);
    }

    public void addTextListener(TextListener l) {
        textListeners.add(l);
    }

    // ---------------------------------------------------------- text relay

    /** Car pushes text destined for the phone; the phone portal polls it. */
    public void pushTextToPhone(String text) {
        textToPhone = text == null ? "" : text;
    }

    /** Phone pushes text destined for the car; fires the car UI listener. */
    public void pushTextToCar(String text) {
        textToCar = text == null ? "" : text;
        for (TextListener l : textListeners) {
            try {
                l.onTextReceived(text == null ? "" : text);
            } catch (Throwable t) {
                Log.w(TAG, "text listener: " + t.getMessage());
            }
        }
    }

    /** Read the latest car -> phone text; idempotent for the portal poll. */
    public String peekTextToPhone() {
        return textToPhone == null ? "" : textToPhone;
    }

    /**
     * Bind the listener.
     *
     * <p>{@code SO_REUSEADDR} has to be set <em>before</em> {@code bind()} - the
     * old code called the constructor first (which binds immediately) and then
     * set the flag, so a port left in TIME_WAIT by a previous service instance
     * still failed to bind. If {@code port} really is taken we walk up to the
     * next free one instead of dying silently, which used to leave the UI
     * advertising an address nothing was listening on.</p>
     */
    public synchronized void start() throws IOException {
        if (running) return;
        IOException last = null;
        for (int p = port; p < port + PORT_SCAN; p++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(p));
                serverSocket = ss;
                boundPort = p;
                running = true;
                pool = Executors.newFixedThreadPool(8);
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        acceptLoop();
                    }
                }, "accept-loop");
                t.setDaemon(true);
                t.start();
                Log.i(TAG, "server started on " + p);
                return;
            } catch (IOException e) {
                last = e;
                Log.w(TAG, "port " + p + " unavailable: " + e.getMessage());
            }
        }
        throw last != null ? last : new IOException("no free port");
    }

    public synchronized void stop() {
        running = false;
        boundPort = port;
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

    /** The port the listener is actually bound to (may differ from the request). */
    public int getPort() {
        return boundPort;
    }

    /** True if any peer reached the server within the last {@code withinMs} ms. */
    public boolean wasClientSeenRecently(long withinMs) {
        return lastClientTime > 0
                && (System.currentTimeMillis() - lastClientTime) < withinMs;
    }

    /** The most recent client IP, or null when no one has connected yet. */
    public String getLastClientIp() {
        return lastClientIp;
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

    // ---------------------------------------------------------------- routing

    private void handle(Socket sock) {
        try {
            // Every HTTP hit from the phone is a strong "a client joined" signal;
            // record it so the UI can flip to CONNECTED immediately instead of
            // waiting for the ARP table to populate.
            if (sock.getInetAddress() != null) {
                lastClientIp = sock.getInetAddress().getHostAddress();
                if (lastClientIp != null) lastClientTime = System.currentTimeMillis();
            }
            // Only the request phase is time-boxed. A phone that locks its screen
            // mid-upload stalls TCP for minutes; the old fixed 60 s read timeout
            // then threw in the middle of the body, the request was answered with
            // 500 and the file was lost - which is exactly the "cannot transfer"
            // symptom. Once the headers are in, reading blocks until the peer
            // either finishes or drops.
            sock.setSoTimeout(TIMEOUT_HEADER_MS);
            InputStream raw = sock.getInputStream();
            String headerBlock = readHeaderBlock(raw);
            if (headerBlock.isEmpty()) return;
            sock.setSoTimeout(0);
            try {
                sock.setReceiveBufferSize(262144);
            } catch (Exception ignored) {
            }

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

            boolean head = "HEAD".equalsIgnoreCase(method);

            if ("GET".equalsIgnoreCase(method) || head) {
                if (path.equals("/info")) {
                    JSONObject o = new JSONObject();
                    try {
                        o.put("device", deviceName);
                        o.put("free", repo.getDir().getFreeSpace());
                        o.put("direction", "bidirectional");
                    } catch (Exception ignored) {
                    }
                    writeResponse(sock, 200, "OK", "application/json; charset=utf-8",
                            o.toString().getBytes("UTF-8"), head);
                } else if (path.equals("/files")) {
                    writeResponse(sock, 200, "OK", "application/json; charset=utf-8",
                            repo.listJson().toString().getBytes("UTF-8"), head);
                } else if (path.equals("/download")) {
                    serveFile(sock, repo.get(queryValue(query, "name")), headers, head);
                } else if (path.equals("/text")) {
                    handleTextGet(query, head, sock);
                } else {
                    writeResponse(sock, 200, "OK", "text/html; charset=utf-8",
                            portalPage.getBytes("UTF-8"), head);
                }
            } else if ("POST".equalsIgnoreCase(method) && path.equals("/upload")) {
                // some clients stream an unknown-size body as chunked instead of
                // sending Content-Length - decode it before it hits the disk
                String te = headers.get("transfer-encoding");
                boolean chunked = te != null
                        && te.toLowerCase(Locale.US).contains("chunked");
                InputStream body = chunked ? new ChunkedInputStream(raw) : raw;
                // long, not int: a 3 GB file used to overflow to a negative
                // length and the upload was then written as zero bytes
                long len = -1L;
                try {
                    len = Long.parseLong(headers.get("content-length"));
                } catch (Exception ignored) {
                }
                saveUpload(body, chunked ? -1L : len, queryValue(query, "name"), sock);
            } else if ("POST".equalsIgnoreCase(method) && path.equals("/text")) {
                handleTextPost(raw, headers, query, sock);
            } else {
                writeResponse(sock, 404, "Not Found", "text/plain",
                        "404".getBytes("UTF-8"), false);
            }
        } catch (Exception e) {
            Log.e(TAG, "handle", e);
            try {
                writeResponse(sock, 500, "Server Error", "text/plain",
                        "500".getBytes("UTF-8"), false);
            } catch (Exception ignored) {
            }
        } finally {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String queryValue(String query, String key) {
        if (query == null || query.length() == 0) return null;
        for (String kv : query.split("&")) {
            if (kv.startsWith(key + "=")) {
                try {
                    return URLDecoder.decode(kv.substring(key.length() + 1), "UTF-8");
                } catch (Exception e) {
                    return kv.substring(key.length() + 1);
                }
            }
        }
        return null;
    }

    private void handleTextGet(String query, boolean head, Socket sock) throws IOException {
        String to = queryValue(query, "to");
        String body;
        if ("phone".equals(to)) body = peekTextToPhone();
        else if ("car".equals(to)) body = textToCar == null ? "" : textToCar;
        else body = "";
        writeResponse(sock, 200, "OK", "text/plain; charset=utf-8",
                body.getBytes("UTF-8"), head);
    }

    private void handleTextPost(InputStream raw, Map<String, String> headers,
                                String query, Socket sock) throws IOException {
        String to = queryValue(query, "to");
        String te = headers.get("transfer-encoding");
        boolean chunked = te != null && te.toLowerCase(Locale.US).contains("chunked");
        InputStream body = chunked ? new ChunkedInputStream(raw) : raw;
        long len = -1L;
        try {
            len = Long.parseLong(headers.get("content-length"));
        } catch (Exception ignored) {
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        long total = 0L;
        if (len > 0L) {
            while (total < len) {
                int n = body.read(buf, 0, (int) Math.min(buf.length, len - total));
                if (n < 0) break;
                bos.write(buf, 0, n);
                total += n;
            }
        } else {
            int n;
            while ((n = body.read(buf)) > 0 && total < 262144L) {
                bos.write(buf, 0, n);
                total += n;
            }
        }
        String text = new String(bos.toByteArray(), "UTF-8");
        if ("phone".equals(to)) pushTextToPhone(text);
        else pushTextToCar(text);
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
        } catch (Exception ignored) {
        }
        writeResponse(sock, 200, "OK", "application/json; charset=utf-8",
                o.toString().getBytes("UTF-8"), false);
    }

    // ---------------------------------------------------------------- download

    /**
     * Stream a file back to the phone. Supports single-part {@code Range}
     * requests so large files can be resumed instead of restarted.
     */
    private void serveFile(Socket sock, File f, Map<String, String> headers, boolean head)
            throws IOException {
        if (f == null || !f.exists() || !f.isFile()) {
            writeResponse(sock, 404, "Not Found", "text/plain",
                    "404".getBytes("UTF-8"), head);
            return;
        }

        long total = f.length();
        long start = 0;
        long end = total - 1;
        int code = 200;
        String status = "OK";

        String range = headers.get("range");
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring("bytes=".length());
            int dash = spec.indexOf('-');
            try {
                long rs = dash > 0 ? Long.parseLong(spec.substring(0, dash).trim()) : 0;
                String tail = dash >= 0 ? spec.substring(dash + 1).trim() : "";
                long re = tail.length() > 0 ? Long.parseLong(tail) : total - 1;
                if (rs < 0 || rs > re || rs >= total) {
                    writeResponse(sock, 416, "Range Not Satisfiable", "text/plain",
                            "416".getBytes("UTF-8"), head);
                    return;
                }
                start = rs;
                end = Math.min(re, total - 1);
                code = 206;
                status = "Partial Content";
            } catch (NumberFormatException ignored) {
                // fall through to a full 200 response
            }
        }

        long length = end - start + 1;
        String type = FileRepository.mimeOf(f.getName());
        String encoded = URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20");

        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(" ").append(status).append("\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        sb.append("Content-Length: ").append(length).append("\r\n");
        sb.append("Accept-Ranges: bytes\r\n");
        if (code == 206) {
            sb.append("Content-Range: bytes ").append(start).append("-").append(end)
                    .append("/").append(total).append("\r\n");
        }
        sb.append("Content-Disposition: attachment; filename=\"").append(encoded)
                .append("\"; filename*=UTF-8''").append(encoded).append("\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("Connection: close\r\n");
        sb.append("\r\n");

        OutputStream os = sock.getOutputStream();
        os.write(sb.toString().getBytes("UTF-8"));
        if (head) {
            os.flush();
            return;
        }

        FileInputStream fis = new FileInputStream(f);
        try {
            long skipped = 0;
            while (skipped < start) {
                long s = fis.skip(start - skipped);
                if (s <= 0) break;
                skipped += s;
            }
            byte[] buf = new byte[BUFFER];
            long remaining = length;
            while (remaining > 0) {
                int n = fis.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) break;
                os.write(buf, 0, n);
                remaining -= n;
            }
            os.flush();
            Log.i(TAG, "served " + f.getName() + " bytes=" + start + "-" + end);
        } finally {
            try {
                fis.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- upload

    /**
     * @param contentLength declared body size, or -1 when unknown (chunked)
     */
    private void saveUpload(InputStream raw, long contentLength, String name, Socket sock)
            throws IOException {
        File out = repo.target(name);
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[16384];
        long total = 0;
        fireStart(out.getName(), contentLength > 0 ? contentLength : 0L);
        long lastReport = 0;
        try {
            if (contentLength > 0) {
                long remaining = contentLength;
                while (remaining > 0) {
                    int n = raw.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n < 0) break;
                    fos.write(buf, 0, n);
                    total += n;
                    remaining -= n;
                    if (total - lastReport >= PROGRESS_STEP || remaining == 0) {
                        lastReport = total;
                        fireProgress(out.getName(), total, contentLength);
                    }
                }
                fos.flush();
                try {
                    fos.getFD().sync();
                } catch (Exception ignored) {
                }
            } else {
                int n;
                while ((n = raw.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    total += n;
                    if (total - lastReport >= PROGRESS_STEP) {
                        lastReport = total;
                        fireProgress(out.getName(), total, 0L);
                    }
                }
                fireProgress(out.getName(), total, total);
            }
        } finally {
            fos.flush();
            fos.close();
        }
        Log.i(TAG, "received " + out.getName() + " (" + total + " bytes)");
        for (ReceiveListener l : listeners) {
            try {
                l.onFileReceived(out.getName(), total);
            } catch (Throwable t) {
                Log.w(TAG, "listener received: " + t.getMessage());
            }
        }
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("name", out.getName());
            o.put("size", total);
        } catch (Exception ignored) {
        }
        writeResponse(sock, 200, "OK", "application/json; charset=utf-8",
                o.toString().getBytes("UTF-8"), false);
    }

    // ---------------------------------------------------------------- events

    private void fireStart(String name, long size) {
        for (ReceiveListener l : listeners) {
            try {
                l.onTransferStart(name, size);
            } catch (Throwable t) {
                Log.w(TAG, "listener start: " + t.getMessage());
            }
        }
    }

    private void fireProgress(String name, long received, long size) {
        for (ReceiveListener l : listeners) {
            try {
                l.onTransferProgress(name, received, size);
            } catch (Throwable t) {
                Log.w(TAG, "listener progress: " + t.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- chunked

    /** Minimal HTTP/1.1 chunked transfer decoder (no trailers). */
    private static final class ChunkedInputStream extends InputStream {

        private final InputStream in;
        private long remaining = 0L;
        private boolean eof = false;

        ChunkedInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (eof) return -1;
            if (remaining == 0L) {
                String line = readLine();
                if (line == null) {
                    eof = true;
                    return -1;
                }
                int semi = line.indexOf(';');
                if (semi >= 0) line = line.substring(0, semi);
                line = line.trim();
                if (line.length() == 0) {
                    line = readLine();
                    if (line == null) {
                        eof = true;
                        return -1;
                    }
                    line = line.trim();
                }
                long size;
                try {
                    size = Long.parseLong(line, 16);
                } catch (NumberFormatException e) {
                    eof = true;
                    return -1;
                }
                if (size == 0L) {
                    readLine(); // trailing CRLF after the last chunk
                    eof = true;
                    return -1;
                }
                remaining = size;
            }
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n < 0) {
                eof = true;
                return -1;
            }
            remaining -= n;
            if (remaining == 0L) readLine();
            return n;
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\n') return bos.toString("UTF-8");
                if (c != '\r') bos.write(c);
            }
            return bos.size() > 0 ? bos.toString("UTF-8") : null;
        }
    }

    // ---------------------------------------------------------------- plumbing

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

    private static void writeResponse(Socket s, int code, String status, String type,
                                      byte[] payload, boolean headOnly) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(" ").append(status).append("\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        sb.append("Content-Length: ").append(headOnly ? 0 : payload.length).append("\r\n");
        sb.append("Accept-Ranges: bytes\r\n");
        sb.append("Connection: close\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("\r\n");
        OutputStream os = s.getOutputStream();
        os.write(sb.toString().getBytes("UTF-8"));
        if (!headOnly) os.write(payload);
        os.flush();
    }
}
