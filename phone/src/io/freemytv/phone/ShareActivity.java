package io.freemytv.phone;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Share → Nook, from any app: a link opens on the TV (a YouTube link in YouTube), other text is typed on the TV, and
 * photos go to the TV's wallpaper photos. No screen of its own: a short message, and back to where you were.
 */
public class ShareActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Intent in = getIntent();
        if (!Tv.paired(this)) {
            Toast.makeText(this, "Pair Nook Phone with the TV first.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        List<Uri> images = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(in.getAction())) {
            ArrayList<Uri> list = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) images.addAll(list);
        } else if (in.getType() != null && in.getType().startsWith("image/")) {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) images.add(u);
        }
        String text = in.getStringExtra(Intent.EXTRA_TEXT);
        Executors.newSingleThreadExecutor().execute(() -> {
            String result;
            if (!images.isEmpty()) {
                int sent = 0;
                for (Uri u : images) if (sendPhoto(u)) sent++;
                result = sent == images.size() ? (sent == 1 ? "Photo sent to the TV" : sent + " photos sent to the TV")
                        : "Sent " + sent + " of " + images.size() + " photos";
            } else if (text != null && !text.trim().isEmpty()) {
                String t = text.trim(), link = firstLink(t);
                boolean ok;
                try {
                    ok = link != null ? post("open", new JSONObject().put("url", link)) : post("text", new JSONObject().put("t", t));
                } catch (org.json.JSONException e) {
                    ok = false;
                }
                result = !ok ? "The TV didn't answer. Same Wi-Fi?" : link != null ? "Opening on the TV" : "Typed on the TV";
            } else {
                result = "Nothing to send";
            }
            main.post(() -> {
                Toast.makeText(this, result, Toast.LENGTH_SHORT).show();
                finish();
            });
        });
    }

    /** The first http(s) link in shared text ("Watch this https://youtu.be/…"). */
    private static String firstLink(String t) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://\\S+").matcher(t);
        return m.find() ? m.group() : null;
    }

    private boolean post(String what, JSONObject body) {
        return send("/remote/" + what, "application/json", body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private boolean sendPhoto(Uri u) {
        try (InputStream in = getContentResolver().openInputStream(u)) {
            if (in == null) return false;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                b.write(buf, 0, n);
                if (b.size() > 30 * 1024 * 1024) return false;
            }
            return send("/remote/photo", "application/octet-stream", b.toByteArray());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean send(String path, String type, byte[] body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(Tv.address(this) + path + "?k=" + Tv.key(this)).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(30000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", type);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
