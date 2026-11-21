package com.ofm.gateway;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Direct HTTPS client to the public gateway. No third-party libs, no opencode binary. */
public final class Upstream {

    public interface Sink {
        void status(int code, String contentType) throws IOException;
        void chunk(byte[] data, int len) throws IOException;
        void done() throws IOException;
    }

    private Upstream() {}

    public static void postJson(String path, byte[] payload, Map<String, String> headers, Sink sink) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(Fingerprint.BASE + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setFixedLengthStreamingMode(payload.length);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
                os.flush();
            }
            int code = conn.getResponseCode();
            String ct = conn.getContentType();
            sink.status(code, ct == null ? "application/json" : ct);
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in != null) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) sink.chunk(buf, n);
                in.close();
            }
            sink.done();
        } catch (IOException e) {
            try {
                sink.status(502, "application/json");
                String msg = "{\"error\":{\"message\":\"" + jsonEscape(e.getMessage()) + "\"}}";
                byte[] b = msg.getBytes(StandardCharsets.UTF_8);
                sink.chunk(b, b.length);
                sink.done();
            } catch (IOException ignored) {
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static void getJson(String path, Map<String, String> headers, Sink sink) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(Fingerprint.BASE + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            int code = conn.getResponseCode();
            sink.status(code, conn.getContentType() == null ? "application/json" : conn.getContentType());
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in != null) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) sink.chunk(buf, n);
                in.close();
            }
            sink.done();
        } catch (IOException e) {
            try {
                sink.status(502, "application/json");
                byte[] b = ("{\"error\":{\"message\":\"" + jsonEscape(e.getMessage()) + "\"}}")
                        .getBytes(StandardCharsets.UTF_8);
                sink.chunk(b, b.length);
                sink.done();
            } catch (IOException ignored) {
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) return "upstream error";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}
