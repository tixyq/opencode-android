package com.opencode;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Minimal static file server for the bundled web UI. Localhost only. */
public class StaticServer {
    private final File root;
    private final File boot;
    private final File version;
    private final int port;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    private static final Map<String, String> MIME = new HashMap<String, String>();

    static {
        MIME.put("html", "text/html; charset=utf-8");
        MIME.put("js", "text/javascript; charset=utf-8");
        MIME.put("css", "text/css; charset=utf-8");
        MIME.put("json", "application/json; charset=utf-8");
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("jpeg", "image/jpeg");
        MIME.put("svg", "image/svg+xml");
        MIME.put("ico", "image/x-icon");
        MIME.put("woff", "font/woff");
        MIME.put("woff2", "font/woff2");
        MIME.put("ttf", "font/ttf");
        MIME.put("webmanifest", "application/manifest+json");
    }

    public StaticServer(File root, File boot, File version, int port) {
        this.root = root;
        this.boot = boot;
        this.version = version;
        this.port = port;
    }

    /** Self-diagnostics for the shell environment (localhost only). */







    public synchronized void start() throws IOException {
        if (acceptThread != null) {
            return;
        }
        serverSocket = new ServerSocket(port, 16,
                java.net.InetAddress.getByName("127.0.0.1"));
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "oc-static");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        acceptThread = null;
    }

    private void acceptLoop() {
        while (true) {
            final Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                return;
            }
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        serve(socket);
                    } catch (IOException ignored) {
                    } finally {
                        try {
                            socket.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            });
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void serve(Socket socket) throws IOException {
        InputStream in = new BufferedInputStream(socket.getInputStream());
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());
        String requestLine = readLine(in);
        if (requestLine == null) {
            return;
        }
        String[] parts = requestLine.split(" ");
        // Drain headers.
        while (true) {
            String header = readLine(in);
            if (header == null || header.isEmpty()) {
                break;
            }
        }
        if (parts.length < 2
                || (!parts[0].equals("GET") && !parts[0].equals("HEAD"))) {
            writeStatus(out, 405, "text/plain; charset=utf-8",
                    "method not allowed".getBytes("UTF-8"), false);
            out.flush();
            return;
        }
        String path = parts[1];
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        try {
            path = URLDecoder.decode(path, "UTF-8");
        } catch (IllegalArgumentException e) {
            writeStatus(out, 400, "text/plain; charset=utf-8",
                    "bad request".getBytes("UTF-8"), false);
            out.flush();
            return;
        }
        if (path.equals("/")) {
            path = "/index.html";
        }
        if (path.equals("/boot.json")) {
            serveBoot(out, parts[0].equals("HEAD"));
            out.flush();
            return;
        }
        if (path.equals("/oc-version")) {
            serveTextFile(version, "text/plain; charset=utf-8",
                    out, parts[0].equals("HEAD"));
            out.flush();
            return;
        }
        File file;
        try {
            file = new File(root, path).getCanonicalFile();
            if (!file.getPath().startsWith(root.getCanonicalPath())) {
                writeStatus(out, 403, "text/plain; charset=utf-8",
                        "forbidden".getBytes("UTF-8"), false);
                out.flush();
                return;
            }
        } catch (IOException e) {
            writeStatus(out, 400, "text/plain; charset=utf-8",
                    "bad request".getBytes("UTF-8"), false);
            out.flush();
            return;
        }
        if (!file.isFile()) {
            byte[] body = "not found".getBytes("UTF-8");
            writeStatus(out, 404, "text/plain; charset=utf-8", body,
                    parts[0].equals("HEAD"));
            out.flush();
            return;
        }
        String name = file.getName();
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            ext = name.substring(dot + 1).toLowerCase(Locale.US);
        }
        String mime = MIME.get(ext);
        if (mime == null) {
            mime = "application/octet-stream";
        }
        writeHeaders(out, 200, mime, file.length());
        if (parts[0].equals("GET")) {
            FileInputStream fis = new FileInputStream(file);
            try {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = fis.read(buffer)) >= 0) {
                    out.write(buffer, 0, n);
                }
            } finally {
                fis.close();
            }
        }
        out.flush();
    }

    /** Boot progress for the loader (localhost only). */
    private void serveBoot(OutputStream out, boolean headOnly) throws IOException {
        String body = "{\"phase\":\"starting\",\"detail\":\"preparing\"}";
        try {
            FileInputStream fis = new FileInputStream(boot);
            try {
                byte[] buffer = new byte[512];
                int n = fis.read(buffer);
                if (n > 0) {
                    body = new String(buffer, 0, n, "UTF-8");
                }
            } finally {
                fis.close();
            }
        } catch (IOException ignored) {
        }
        byte[] bytes = body.getBytes("UTF-8");
        StringBuilder head = new StringBuilder(256);
        head.append("HTTP/1.1 200 OK\r\n");
        head.append("Content-Type: application/json; charset=utf-8\r\n");
        head.append("Content-Length: ").append(headOnly ? 0 : bytes.length).append("\r\n");
        head.append("Access-Control-Allow-Origin: *\r\n");
        head.append("Connection: close\r\n");
        head.append("Cache-Control: no-cache\r\n");
        head.append("\r\n");
        out.write(head.toString().getBytes("UTF-8"));
        if (!headOnly) {
            out.write(bytes);
        }
    }

    /** Small text file endpoint (version string, localhost only). */
    private void serveTextFile(File file, String contentType,
            OutputStream out, boolean headOnly) throws IOException {
        byte[] bytes = "unknown".getBytes("UTF-8");
        try {
            FileInputStream fis = new FileInputStream(file);
            try {
                byte[] buffer = new byte[64];
                int n = fis.read(buffer);
                if (n > 0) {
                    bytes = new String(buffer, 0, n, "UTF-8").trim()
                            .getBytes("UTF-8");
                }
            } finally {
                fis.close();
            }
        } catch (IOException ignored) {
        }
        StringBuilder head = new StringBuilder(256);
        head.append("HTTP/1.1 200 OK\r\n");
        head.append("Content-Type: ").append(contentType).append("\r\n");
        head.append("Content-Length: ").append(headOnly ? 0 : bytes.length).append("\r\n");
        head.append("Access-Control-Allow-Origin: *\r\n");
        head.append("Connection: close\r\n");
        head.append("Cache-Control: no-cache\r\n");
        head.append("\r\n");
        out.write(head.toString().getBytes("UTF-8"));
        if (!headOnly) {
            out.write(bytes);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(256);
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                line.write(b);
            }
            if (line.size() > 8192) {
                return null;
            }
        }
        if (b < 0 && line.size() == 0) {
            return null;
        }
        return line.toString("UTF-8");
    }

    private static void writeStatus(OutputStream out, int code,
            String contentType, byte[] body, boolean headOnly)
            throws IOException {
        writeHeaders(out, code, contentType, headOnly ? 0 : body.length);
        if (!headOnly) {
            out.write(body);
        }
    }

    private static void writeHeaders(OutputStream out, int code,
            String contentType, long length) throws IOException {
        String reason;
        switch (code) {
            case 200: reason = "OK"; break;
            case 400: reason = "Bad Request"; break;
            case 403: reason = "Forbidden"; break;
            case 404: reason = "Not Found"; break;
            case 405: reason = "Method Not Allowed"; break;
            default: reason = "Error"; break;
        }
        StringBuilder head = new StringBuilder(256);
        head.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        head.append("Content-Type: ").append(contentType).append("\r\n");
        head.append("Content-Length: ").append(length).append("\r\n");
        head.append("Connection: close\r\n");
        head.append("Cache-Control: no-cache\r\n");
        head.append("\r\n");
        out.write(head.toString().getBytes("UTF-8"));
    }
}
