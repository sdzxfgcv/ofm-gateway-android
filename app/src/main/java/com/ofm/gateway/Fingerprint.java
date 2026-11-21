package com.ofm.gateway;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Transport-layer client fingerprint matching the OpenCode v2 / opencode2api v1.4.x shape.
 * No opencode binary is installed or required: we only reproduce the bytes the
 * official CLI would put on the wire.
 */
public final class Fingerprint {
    public static final String BASE = "https://opencode.ai";
    public static final String CHAT_PATH = "/inference/openai/v1/chat/completions";
    public static final String MODELS_PATH = "/inference/v1/models";
    public static final String CLIENT_UA = "opencode/latest/2.2.3/cli";
    public static final String ANON = "public";

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final BigInteger MASK48 = new BigInteger("ffffffffffff", 16);
    private static final SecureRandom RNG = new SecureRandom();
    private static final byte[] SALT = randomBytes(16);

    private Fingerprint() {}

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    public static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        return s.toString();
    }

    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** ses_ + 12 hex (descending slow-clock phase) + 14 base62 chars. */
    public static String canonicalSessionID(String signal) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update("ses\0".getBytes(StandardCharsets.UTF_8));
            md.update(SALT);
            md.update("\0".getBytes(StandardCharsets.UTF_8));
            md.update(signal.getBytes(StandardCharsets.UTF_8));
            byte[] d = md.digest();
            return "ses_" + descendingTimePart(System.currentTimeMillis(), d)
                    + base62Fixed(Arrays.copyOfRange(d, 6, 16), 14);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String descendingTimePart(long now, byte[] hash) {
        long prefix = ((long) (hash[0] & 0xff) << 24) | ((hash[1] & 0xff) << 16)
                | ((hash[2] & 0xff) << 8) | (hash[3] & 0xff);
        long day = Math.floorDiv(now, 86400000L) * 86400000L;
        long timestamp = day - (prefix % 2592000000L);
        BigInteger encoded = BigInteger.valueOf(timestamp).multiply(BigInteger.valueOf(4096))
                .add(BigInteger.ONE).and(MASK48);
        String out = MASK48.subtract(encoded).toString(16);
        while (out.length() < 12) out = "0" + out;
        return out;
    }

    private static String base62Fixed(byte[] bytes, int width) {
        BigInteger value = new BigInteger(1, bytes);
        BigInteger base = BigInteger.valueOf(62);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < width; i++) {
            result.insert(0, ALPHABET.charAt(value.mod(base).intValue()));
            value = value.divide(base);
        }
        return result.toString();
    }

    public static String randomProjectID() {
        return hex(randomBytes(20));
    }

    /** The full v2 header set. Note: no x-opencode-request header (v1 only -> 403). */
    public static Map<String, String> gatewayHeaders(String session) {
        LinkedHashMap<String, String> h = new LinkedHashMap<>();
        h.put("content-type", "application/json");
        h.put("authorization", "Bearer " + ANON);
        h.put("user-agent", CLIENT_UA);
        h.put("x-opencode-client", "cli");
        h.put("x-opencode-session-id", session);
        h.put("x-opencode-project", randomProjectID());
        String e = hex(randomBytes(32));
        String trace = e.substring(0, 32);
        String span = e.substring(32, 48);
        String parent = e.substring(48);
        h.put("traceparent", "00-" + trace + "-" + span + "-01");
        h.put("b3", trace + "-" + span + "-1-" + parent);
        h.put("x-session-affinity", session);
        h.put("x-session-id", session);
        h.put("x-opencode-session", session);
        h.put("accept", "*/*");
        return h;
    }

    public static String utcDate() {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        String s = f.format(new Date());
        return s.substring(0, Math.min(16, s.length())).replace(",", "");
    }
}
