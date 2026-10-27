package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedInputStream;
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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Your phone's alerts on the TV. Free My TV Phone (phone/, served from here) posts each one to /notify with the
 * pairing key, and AlertOverlay shows it. The pairing page (/pair, the QR code in PhoneActivity) offers the phone app
 * and opens it with this TV's address and key. Runs in the main process, which PlaybackListener keeps alive.
 */
final class NotifyServer {
    private NotifyServer() {}

    static final int PORT = 8766;
    private static final int MAX_BYTES = 512 * 1024;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static ServerSocket socket;

    static synchronized void start(Context c) {
        if (socket != null && !socket.isClosed()) return;
        final Context app = c.getApplicationContext();
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(PORT));
            socket = s;
        } catch (IOException e) {
            AppLog.w("phone alerts: port " + PORT + " busy: " + e.getMessage());
            return;
        }
        Thread accept = new Thread(() -> {
            while (!socket.isClosed()) {
                try {
                    Socket s = socket.accept();
                    POOL.execute(() -> handle(app, s));
                } catch (IOException e) {
                    return;
                }
            }
        }, "notify-server");
        accept.setDaemon(true);
        accept.start();
        AppLog.w("phone alerts: listening on port " + PORT);
    }

    /** The pairing key, made once (a file both processes read: PhoneActivity shows it in the QR code). */
    static synchronized String key(Context c) {
        File f = new File(c.getFilesDir(), "phone-key.txt");
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[64];
            int n = in.read(b);
            if (n >= 12) return new String(b, 0, n, StandardCharsets.US_ASCII).trim();
        } catch (IOException ignored) {
            // not made yet
        }
        String key = Http.randomText(16);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(key.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            AppLog.w("phone alerts: couldn't save the key: " + e.getMessage());
        }
        return key;
    }

    /** The phone remote's address (RemoteActivity's QR code), or null off the home network. */
    static String remoteAddress(Context c) {
        String ip = PhotoServer.localAddress();
        return ip == null ? null : "http://" + ip + ":" + PORT + "/remote?k=" + key(c);
    }

    /** The QR code's address (the pairing page), or null off the home network. */
    static String pairAddress(Context c) {
        String ip = PhotoServer.localAddress();
        return ip == null ? null : "http://" + ip + ":" + PORT + "/pair?k=" + key(c);
    }

    private static void handle(Context c, Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(15_000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            Http.Request r = Http.read(in);
            String key = key(c), given = r.headers.containsKey("x-key") ? r.headers.get("x-key") : r.params.get("k");
            if (!key.equals(given)) {
                Http.reply(out, 403, "text/plain", "Not paired with this TV.");
                return;
            }
            if (PhoneRemote.handle(c, r, in, out, key)) return;   // the phone remote (/remote...)
            if (r.method.equals("GET") && r.path.equals("/pair")) {
                String ip = PhotoServer.localAddress();
                Http.reply(out, 200, "text/html; charset=utf-8", page("http://" + ip + ":" + PORT, key));
            } else if (r.method.equals("GET") && r.path.equals("/phone.apk")) {
                try (InputStream apk = c.getAssets().open("phone.apk")) {
                    Http.send(out, "application/vnd.android.package-archive", "Nook-Phone.apk", apk, apk.available());
                }
            } else if (r.method.equals("GET") && r.path.equals("/ping")) {
                Http.reply(out, 200, "application/json", "{\"ok\":true}");
            } else if (r.method.equals("POST") && r.path.equals("/notify")) {
                long length = r.length();
                if (length <= 0 || length > MAX_BYTES) {
                    Http.reply(out, 413, "application/json", "{\"ok\":false}");
                    return;
                }
                byte[] body = new byte[(int) length];
                for (int got = 0; got < body.length; ) {
                    int n = in.read(body, got, body.length - got);
                    if (n < 0) throw new IOException("the alert stopped");
                    got += n;
                }
                Http.reply(out, 200, "application/json", "{\"ok\":true}");
                if (Prefs.phoneAlerts()) AlertOverlay.show(c, alert(new String(body, StandardCharsets.UTF_8)));
            } else {
                Http.reply(out, 404, "text/plain", "Not found");
            }
        } catch (Exception e) {
            AppLog.w("phone alerts: " + e.getMessage());
        }
    }

    private static AlertOverlay.Alert alert(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        AlertOverlay.Alert a = new AlertOverlay.Alert();
        a.app = o.optString("app", "");
        a.title = o.optString("title", "");
        a.text = o.optString("text", "");
        a.call = o.optBoolean("call", false);
        String icon = o.optString("icon", "");
        if (!icon.isEmpty()) {
            byte[] png = Base64.decode(icon, Base64.DEFAULT);
            Bitmap b = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (b != null && b.getWidth() <= 256 && b.getHeight() <= 256) a.icon = b;
        }
        return a;
    }

    /** The phone's pairing page: download the phone app, then open it with this TV's address and key. */
    private static String page(String address, String key) throws IOException {
        String query = "u=" + URLEncoder.encode(address, "UTF-8") + "&k=" + key;
        String intent = "intent://pair?" + query + "#Intent;scheme=freemytv;package=io.freemytv.phone;end";
        return "<!doctype html><html><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'><title>" + Ui.BRAND + " Phone</title><style>"
                + "body{margin:0;font-family:system-ui,sans-serif;background:#141218;color:#E6E0E9;display:flex;"
                + "min-height:100vh;align-items:center;justify-content:center;text-align:center}"
                + "main{padding:32px 24px;max-width:420px}h1{font-size:26px;font-weight:600;margin:0 0 8px}"
                + "p{color:#CAC4D0;font-size:16px;line-height:1.5;margin:0 0 20px}"
                + "a.b{display:block;background:#D0BCFF;color:#381E72;font-size:18px;font-weight:600;text-decoration:none;"
                + "padding:16px 24px;border-radius:40px;margin:8px 0 6px}a.s{background:#4A4458;color:#E8DEF8}"
                + "small{display:block;color:#938F99;font-size:14px;line-height:1.5;margin:0 0 28px}</style></head><body><main>"
                + "<h1>Your phone's alerts on the TV</h1>"
                + "<p>Messages, calls and other alerts show in the corner of the TV for a few seconds.</p>"
                + "<a class=b href='/phone.apk?k=" + key + "'>1. Download the app</a>"
                + "<small>Open the downloaded file to install " + Ui.BRAND + " Phone (allow installing from your browser if "
                + "Android asks).</small>"
                + "<a class='b s' href='" + Http.html(intent) + "'>2. Connect to this TV</a>"
                + "<small>Then, in the app, allow notification access. Keep the phone on this Wi-Fi.</small>"
                + "<small><a style='color:#CAC4D0' href='freemytv://pair?" + Http.html(query) + "'>Connect doesn't open "
                + "the app? Try this link.</a></small>"
                + "</main></body></html>";
    }
}
