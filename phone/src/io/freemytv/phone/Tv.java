package io.freemytv.phone;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** The paired TV (its address and key, from the TV's pairing page) and what to send it. */
final class Tv {
    private Tv() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("tv", Context.MODE_PRIVATE);
    }

    static String address(Context c) { return sp(c).getString("address", ""); }
    static String key(Context c) { return sp(c).getString("key", ""); }
    static boolean paired(Context c) { return !address(c).isEmpty() && !key(c).isEmpty(); }

    static void pair(Context c, String address, String key) {
        sp(c).edit().putString("address", address).putString("key", key).apply();
    }

    /** Apps whose alerts stay on the phone (everything else goes to the TV). */
    static Set<String> muted(Context c) {
        return new HashSet<>(sp(c).getStringSet("muted", new HashSet<>()));
    }

    static void setMuted(Context c, Set<String> pkgs) {
        sp(c).edit().putStringSet("muted", new HashSet<>(pkgs)).apply();
    }

    /** Off: only the app's name and the title (who wrote), not the message. */
    static boolean showText(Context c) { return sp(c).getBoolean("text", true); }
    static void setShowText(Context c, boolean on) { sp(c).edit().putBoolean("text", on).apply(); }

    /** Sends one alert; false if the TV didn't take it. Call off the main thread. */
    static boolean send(Context c, JSONObject alert) {
        if (!paired(c)) return false;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(address(c) + "/notify").openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(4000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-Key", key(c));
            byte[] body = alert.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
