package com.ofm.gateway;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal OpenAI-compatible loopback HTTP server. */
public final class GatewayServer {
    private final int port;
    private final String apiKey;
    private final String session;
    private final String agentContext;
    private final String toolContracts;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket server;
    private volatile boolean running = false;

    public GatewayServer(int port, String apiKey, String session, String agentContext, String toolContracts) {
        this.port = port;
        this.apiKey = apiKey;
        this.session = session;
        this.agentContext = agentContext;
        this.toolContracts = toolContracts;
    }

    public int port() { return port; }
    public String key() { return apiKey; }

    public void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("127.0.0.1", port));
        running = true;
        pool.execute(() -> {
            while (running) {
                try {
                    Socket client = server.accept();
                    pool.execute(() -> handle(client));
                } catch (IOException e) {
                    if (!running) break;
                }
            }
        });
    }

    public void stop() {
        running = false;
        pool.shutdownNow();
        try { if (server != null) server.close(); } catch (IOException ignored) {}
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(120000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";

            int contentLength = 0;
            String line;
            boolean authorized = true;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                String low = line.toLowerCase(Locale.ROOT);
                if (low.startsWith("content-length:")) {
                    try { contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim()); }
                    catch (NumberFormatException ignored) {}
                }
                if (low.startsWith("authorization:")) {
                    String value = line.substring(line.indexOf(':') + 1).trim();
                    authorized = ("Bearer " + apiKey).equals(value);
                }
            }
            if (!authorized) {
                writeStatus(out, 401, "Unauthorized");
                return;
            }

            byte[] body = new byte[0];
            if (contentLength > 0) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] tmp = new byte[8192];
                int remaining = contentLength;
                while (remaining > 0) {
                    int read = in.read(tmp, 0, Math.min(tmp.length, remaining));
                    if (read < 0) break;
                    buf.write(tmp, 0, read);
                    remaining -= read;
                }
                body = buf.toByteArray();
            }

            Map<String, String> headers = Fingerprint.gatewayHeaders(session);
            if ("/v1/models".equals(path) || "/models".equals(path)) {
                Upstream.getJson(Fingerprint.MODELS_PATH, headers, sink(out));
                return;
            }
            if ("/v1/chat/completions".equals(path) || "/chat/completions".equals(path)) {
                String raw = new String(body, StandardCharsets.UTF_8);
                String model = extractModel(raw);
                String shaped = WireShaper.shape(raw, agentContext, toolContracts, "/sdcard", model);
                byte[] payload = shaped.getBytes(StandardCharsets.UTF_8);
                Upstream.postJson(Fingerprint.CHAT_PATH, payload, headers, sink(out));
                return;
            }
            if ("/health".equals(path)) {
                byte[] b = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                writeStatus(out, 200, "application/json");
                out.write(b);
                out.flush();
                return;
            }

            writeStatus(out, 404, "Not Found");
        } catch (Exception ignored) {
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private Upstream.Sink sink(OutputStream out) {
        return new Upstream.Sink() {
            @Override public void status(int code, String contentType) throws IOException {
                writeStatus(out, code, contentType);
            }
            @Override public void chunk(byte[] data, int len) throws IOException {
                out.write(data, 0, len);
                out.flush();
            }
            @Override public void done() throws IOException {
                out.flush();
            }
        };
    }

    private static void writeStatus(OutputStream out, int code, String contentType) throws IOException {
        String reason = code == 200 ? "OK" : (code == 401 ? "Unauthorized" : (code == 429 ? "Too Many Requests" : "Error"));
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String extractModel(String raw) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(raw);
            return o.optString("model", "");
        } catch (Exception e) {
            return "";
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
        }
        if (c == -1 && buf.size() == 0) return null;
        return buf.toString("UTF-8");
    }
}
