package io.freemytv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** The little HTTP the TV's home-network servers need (PhotoServer, NotifyServer): one request per connection. */
final class Http {
    private Http() {}

    private static final SecureRandom RANDOM = new SecureRandom();

    /** One request's line, path, query parameters and headers (lower-case names). */
    static final class Request {
        String method = "", path = "";
        final Map<String, String> params = new HashMap<>(), headers = new HashMap<>();

        long length() {
            try {
                String v = headers.get("content-length");
                return v == null ? -1 : Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    static Request read(InputStream in) throws IOException {
        Request r = new Request();
        String[] first = line(in).split(" ");
        if (first.length < 2) return r;
        r.method = first[0];
        String path = first[1];
        int q = path.indexOf('?');
        if (q >= 0) {
            for (String pair : path.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) r.params.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
            path = path.substring(0, q);
        }
        r.path = path;
        for (String h; !(h = line(in)).isEmpty(); ) {
            int colon = h.indexOf(':');
            if (colon > 0) r.headers.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
        }
        return r;
    }

    /** One header line (ASCII, up to CRLF). */
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1; ) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("header too long");
        }
        return b.toString("US-ASCII");
    }

    static void reply(OutputStream out, int status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        head(out, status, type, b.length, null);
        out.write(b);
        out.flush();
    }

    /** A file download (the phone app): `name` is what the phone saves it as. */
    static void send(OutputStream out, String type, String name, InputStream body, long length) throws IOException {
        head(out, 200, type, length, name == null ? null : "Content-Disposition: attachment; filename=\"" + name + "\"\r\n");
        byte[] buf = new byte[32 * 1024];
        for (int n; (n = body.read(buf)) > 0; ) out.write(buf, 0, n);
        out.flush();
    }

    private static void head(OutputStream out, int status, String type, long length, String extra) throws IOException {
        String head = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Error") + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + length + "\r\nCache-Control: no-store\r\nConnection: close\r\n"
                + (extra == null ? "" : extra) + "\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
    }

    /** Lower-case letters and digits without look-alikes (l, 1, 0, o). */
    static String randomText(int n) {
        String abc = "abcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(abc.charAt(RANDOM.nextInt(abc.length())));
        return sb.toString();
    }

    static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
