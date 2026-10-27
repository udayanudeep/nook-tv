package io.freemytv.phone;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends the phone's alerts to the TV: those that would make a sound or pop up (not silent ones, ongoing ones like
 * music, or group summaries), from apps that aren't muted, once each (apps re-post the same alert as they update).
 */
public class Forwarder extends NotificationListenerService {
    private static final long REPEAT_MS = 15_000;
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private final Map<String, Long> recent = new HashMap<>();
    private final Map<String, String> icons = new HashMap<>();
    private final Ranking ranking = new Ranking();

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (pkg.equals(getPackageName()) || !Tv.paired(this) || Tv.muted(this).contains(pkg)) return;
        Notification n = sbn.getNotification();
        boolean call = Notification.CATEGORY_CALL.equals(n.category);
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if (!call && (n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
        RankingMap map = getCurrentRanking();
        if (!call && map != null && map.getRanking(sbn.getKey(), ranking)
                && ranking.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) return;   // silent
        Bundle x = n.extras;
        String title = text(x.getCharSequence(Notification.EXTRA_TITLE));
        String body = text(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (body.isEmpty()) body = text(x.getCharSequence(Notification.EXTRA_TEXT));
        if (title.isEmpty() && body.isEmpty()) return;
        String id = pkg + "\n" + title + "\n" + body;
        long now = System.currentTimeMillis();
        synchronized (recent) {
            Long last = recent.get(id);
            if (last != null && now - last < REPEAT_MS) return;
            recent.put(id, now);
            if (recent.size() > 50) recent.clear();
        }
        final String t = title, b = Tv.showText(this) ? body : "";
        sender.execute(() -> {
            try {
                JSONObject alert = new JSONObject().put("app", label(pkg)).put("title", t).put("text", b)
                        .put("call", call).put("icon", icon(pkg));
                Tv.send(this, alert);
            } catch (Exception ignored) {
                // the TV is off or away: the alert stays on the phone only
            }
        });
    }

    private static String text(CharSequence s) {
        return s == null ? "" : s.toString().trim();
    }

    private String label(String pkg) {
        PackageManager pm = getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** The app's icon at 96 px, as base64 PNG (made once per app). */
    private String icon(String pkg) {
        synchronized (icons) {
            String cached = icons.get(pkg);
            if (cached != null) return cached;
        }
        String out = "";
        try {
            Drawable d = getPackageManager().getApplicationIcon(pkg);
            Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 96, 96);
            d.draw(new Canvas(b));
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, png);
            out = Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP);
        } catch (Exception ignored) {
        }
        synchronized (icons) {
            icons.put(pkg, out);
        }
        return out;
    }
}
