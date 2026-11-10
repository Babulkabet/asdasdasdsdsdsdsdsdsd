package dev.packetsender;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Минимальный HTTP-сервер только на 127.0.0.1 (без внешних зависимостей).
 * Защита: проверка Host (DNS rebinding), проверка Origin у POST, токен X-Token для всех /api/*.
 */
final class WebServer {
    final int port;
    private final String token;
    private final ServerSocket ss;
    private final byte[] page;
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "packetsender-http");
        t.setDaemon(true);
        return t;
    });

    WebServer(int port, String token) throws IOException {
        this.port = port;
        this.token = token;
        try (InputStream in = WebServer.class.getResourceAsStream("/assets/packetsender/web/index.html")) {
            if (in == null) throw new IOException("index.html не найден в jar");
            this.page = in.readAllBytes();
        }
        this.ss = new ServerSocket(port, 32, InetAddress.getByName("127.0.0.1"));
    }

    void start() {
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    Socket s = ss.accept();
                    pool.execute(() -> serve(s));
                } catch (IOException e) {
                    if (ss.isClosed()) return;
                }
            }
        }, "packetsender-accept");
        t.setDaemon(true);
        t.start();
    }

    private void serve(Socket s) {
        try (s) {
            s.setSoTimeout(8000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String line = readLine(in);
            if (line == null || line.isEmpty()) return;
            String[] p = line.split(" ");
            if (p.length < 2) { send(out, 400, "text/plain", "bad request".getBytes()); return; }
            Map<String, String> h = new HashMap<>();
            String l;
            int n = 0;
            while ((l = readLine(in)) != null && !l.isEmpty()) {
                if (++n > 64) return;
                int i = l.indexOf(':');
                if (i > 0) h.put(l.substring(0, i).trim().toLowerCase(Locale.ROOT), l.substring(i + 1).trim());
            }
            String host = h.getOrDefault("host", "");
            if (!host.equals("127.0.0.1:" + port) && !host.equals("localhost:" + port)) {
                send(out, 403, "text/plain", "forbidden".getBytes());
                return;
            }
            String method = p[0];
            String path = p[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            int len = 0;
            try { len = Integer.parseInt(h.getOrDefault("content-length", "0")); } catch (NumberFormatException ignore) { }
            if (len < 0 || len > 4_000_000) { send(out, 413, "text/plain", "too large".getBytes()); return; }
            byte[] body = in.readNBytes(len);
            if (!method.equals("GET")) {
                String origin = h.get("origin");
                if (origin != null && !origin.equals("http://127.0.0.1:" + port) && !origin.equals("http://localhost:" + port)) {
                    send(out, 403, "text/plain", "forbidden".getBytes());
                    return;
                }
            }
            if (path.startsWith("/api/")) {
                byte[] given = h.getOrDefault("x-token", "").getBytes(StandardCharsets.UTF_8);
                if (!MessageDigest.isEqual(given, token.getBytes(StandardCharsets.UTF_8))) {
                    Api.Resp r = Api.error(401, "нужен токен: откройте панель командой /psend");
                    send(out, 401, "application/json; charset=utf-8", r.body().getBytes(StandardCharsets.UTF_8));
                    return;
                }
                Api.Resp r = Api.handle(method, path.substring(5), new String(body, StandardCharsets.UTF_8));
                send(out, r.code(), "application/json; charset=utf-8", r.body().getBytes(StandardCharsets.UTF_8));
            } else if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
                send(out, 200, "text/html; charset=utf-8", page);
            } else {
                send(out, 404, "text/plain", "not found".getBytes());
            }
        } catch (IOException ignore) {
        } catch (RuntimeException e) {
            PacketSenderMod.LOG.warn("HTTP error", e);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("line too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString(StandardCharsets.ISO_8859_1);
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String status = switch (code) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 413 -> "Payload Too Large";
            case 500 -> "Internal Server Error";
            case 504 -> "Gateway Timeout";
            default -> "Error";
        };
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }
}
