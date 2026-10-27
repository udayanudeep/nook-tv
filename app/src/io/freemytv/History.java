package io.freemytv;

import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Build;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Recent play history, from three places:
 * 1. Android TV's "Watch Next" list (TvContract.WatchNextPrograms), when the TV lets us read it;
 * 2. our own log of what played, written by PlaybackListener from the apps' media sessions (YouTube, Netflix, Prime
 *    Video, JioHotstar and most players publish one);
 * 3. the recently used apps (UsageStatsManager).
 * Access to 2 and 3 is granted over ADB (appops / cmd notification); ensureAccess does it from the TV itself once
 * Free My TV's own ADB key is approved.
 */
final class History {
    private History() {}

    static final int MAX_LOG = 60;
    private static final String LOG = "history.json";
    private static final Object LOCK = new Object();

    static final class Entry {
        String title = "", subtitle = "", pkg = "", intentUri, artUri, artFile;
        long at, position, duration;
        boolean watchNext;

        String key() { return pkg + "|" + title.toLowerCase(Locale.ROOT); }

        /** 0..1, or -1 when unknown. */
        float progress() {
            if (duration <= 0 || position <= 0) return -1;
            return Math.max(0, Math.min(1, position / (float) duration));
        }

        JSONObject json() {
            JSONObject o = new JSONObject();
            try {
                o.put("title", title).put("subtitle", subtitle).put("pkg", pkg).put("at", at)
                        .put("position", position).put("duration", duration);
                if (intentUri != null) o.put("intent", intentUri);
                if (artUri != null) o.put("artUri", artUri);
                if (artFile != null) o.put("artFile", artFile);
            } catch (Exception ignored) {
            }
            return o;
        }

        static Entry of(JSONObject o) {
            Entry e = new Entry();
            e.title = o.optString("title");
            e.subtitle = o.optString("subtitle");
            e.pkg = o.optString("pkg");
            e.at = o.optLong("at");
            e.position = o.optLong("position");
            e.duration = o.optLong("duration");
            e.intentUri = o.optString("intent", null);
            e.artUri = o.optString("artUri", null);
            e.artFile = o.optString("artFile", null);
            return e;
        }
    }

    // MARK: - Continue watching

    /** Watch Next and our own log, newest first, one per title. */
    static List<Entry> continueWatching(Context c, int max) {
        List<Entry> all = new ArrayList<>(watchNext(c));
        all.addAll(log(c));
        Collections.sort(all, (a, b) -> Long.compare(b.at, a.at));
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Entry e : all) {
            if (e.title.isEmpty() || !seen.add(e.key())) continue;
            out.add(e);
            if (out.size() >= max) break;
        }
        return out;
    }

    private static final String[] WATCH_NEXT_COLUMNS = {
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
    };

    /** The TV's Watch Next rows (other apps' rows only if the TV allows it; otherwise empty). */
    static List<Entry> watchNext(Context c) {
        List<Entry> out = new ArrayList<>();
        try (Cursor cur = c.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, WATCH_NEXT_COLUMNS,
                null, null, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC")) {
            while (cur != null && cur.moveToNext()) {
                Entry e = new Entry();
                e.watchNext = true;
                e.title = str(cur, 0);
                String episode = str(cur, 1), season = str(cur, 8), number = str(cur, 9);
                StringBuilder sub = new StringBuilder();
                if (!season.isEmpty()) sub.append('S').append(season);
                if (!number.isEmpty()) sub.append(sub.length() > 0 ? " · " : "").append('E').append(number);
                if (!episode.isEmpty()) sub.append(sub.length() > 0 ? " · " : "").append(episode);
                e.subtitle = sub.toString();
                e.pkg = str(cur, 2);
                e.intentUri = emptyToNull(str(cur, 3));
                e.artUri = emptyToNull(str(cur, 4));
                e.at = cur.getLong(5);
                e.position = cur.getLong(6);
                e.duration = cur.getLong(7);
                if (!e.pkg.equals(c.getPackageName())) out.add(e);
            }
        } catch (Exception e) {
            AppLog.w("watch next: " + e.getMessage());
        }
        return out;
    }

    private static String str(Cursor c, int i) {
        String s = c.isNull(i) ? null : c.getString(i);
        return s == null ? "" : s.trim();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    // MARK: - Our own log (written by PlaybackListener, read by any process)

    static List<Entry> log(Context c) {
        List<Entry> out = new ArrayList<>();
        synchronized (LOCK) {
            File f = new File(c.getFilesDir(), LOG);
            if (!f.exists()) return out;
            try {
                byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
                JSONArray a = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) out.add(Entry.of(o));
                }
            } catch (Exception e) {
                AppLog.w("history log: " + e.getMessage());
            }
        }
        return out;
    }

    /** Adds or refreshes a title in the log (same app and title: one entry, moved to the top). */
    static void record(Context c, Entry e, Bitmap art) {
        if (e.title == null || e.title.trim().isEmpty() || e.pkg.equals(c.getPackageName())) return;
        synchronized (LOCK) {
            List<Entry> list = log(c);
            Entry old = null;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).key().equals(e.key())) {
                    old = list.remove(i);
                    break;
                }
            }
            if (art != null) e.artFile = saveArt(c, e.key(), art);
            else if (old != null && e.artFile == null) e.artFile = old.artFile;
            if (old != null && e.artUri == null) e.artUri = old.artUri;
            if (old != null && e.duration <= 0) e.duration = old.duration;
            list.add(0, e);
            while (list.size() > MAX_LOG) {
                Entry gone = list.remove(list.size() - 1);
                if (gone.artFile != null) new File(gone.artFile).delete();
            }
            write(c, list);
        }
    }

    static void clear(Context c) {
        synchronized (LOCK) {
            new File(c.getFilesDir(), LOG).delete();
            File dir = new File(c.getFilesDir(), "history");
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) f.delete();
        }
    }

    private static void write(Context c, List<Entry> list) {
        JSONArray a = new JSONArray();
        for (Entry e : list) a.put(e.json());
        File f = new File(c.getFilesDir(), LOG), tmp = new File(c.getFilesDir(), LOG + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception e) {
            AppLog.w("history write: " + e.getMessage());
            return;
        }
        if (!tmp.renameTo(f)) AppLog.w("history: couldn't save");
    }

    private static String saveArt(Context c, String key, Bitmap art) {
        File dir = new File(c.getFilesDir(), "history");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File f = new File(dir, Integer.toHexString(key.hashCode()) + ".jpg");
        try (FileOutputStream out = new FileOutputStream(f)) {
            Bitmap b = art;
            int w = art.getWidth(), h = art.getHeight();
            if (w > 640) b = Bitmap.createScaledBitmap(art, 640, Math.max(1, Math.round(640f * h / w)), true);
            b.compress(Bitmap.CompressFormat.JPEG, 85, out);
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    // MARK: - Pictures

    /** The entry's picture: our saved copy, or its address (content:, file:, http(s):). Call off the main thread. */
    static Bitmap art(Context c, Entry e, int maxWidth) {
        try {
            if (e.artFile != null && new File(e.artFile).isFile()) return decode(new java.io.FileInputStream(e.artFile), maxWidth);
            if (e.artUri == null) return null;
            Uri u = Uri.parse(e.artUri);
            String scheme = u.getScheme() == null ? "" : u.getScheme();
            if (scheme.equals("http") || scheme.equals("https")) {
                HttpURLConnection conn = (HttpURLConnection) new URL(e.artUri).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(10000);
                try {
                    return decode(conn.getInputStream(), maxWidth);
                } finally {
                    conn.disconnect();
                }
            }
            if (scheme.equals(ContentResolver.SCHEME_CONTENT) || scheme.equals(ContentResolver.SCHEME_FILE)
                    || scheme.equals(ContentResolver.SCHEME_ANDROID_RESOURCE)) {
                return decode(c.getContentResolver().openInputStream(u), maxWidth);
            }
        } catch (Exception ex) {
            AppLog.w("history art: " + ex.getMessage());
        }
        return null;
    }

    private static Bitmap decode(InputStream in, int maxWidth) throws java.io.IOException {
        try {
            byte[] bytes = readAll(in);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        } finally {
            in.close();
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    // MARK: - Recently used apps

    /** Launchable apps, most recently used first, as {package, name}. */
    static List<String[]> recentApps(Context c, int max) {
        List<String[]> out = new ArrayList<>();
        UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return out;
        long now = System.currentTimeMillis();
        List<UsageStats> stats;
        try {
            stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 30L * 24 * 3600_000L, now);
        } catch (Exception e) {
            return out;
        }
        if (stats == null) return out;
        Map<String, Long> last = new HashMap<>();
        for (UsageStats s : stats) {
            if (s.getTotalTimeInForeground() <= 0) continue;
            Long old = last.get(s.getPackageName());
            if (old == null || s.getLastTimeUsed() > old) last.put(s.getPackageName(), s.getLastTimeUsed());
        }
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(last.entrySet());
        Collections.sort(sorted, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        PackageManager pm = c.getPackageManager();
        Set<String> homes = new HashSet<>();
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
            homes.add(ri.activityInfo.packageName);
        }
        for (Map.Entry<String, Long> e : sorted) {
            String pkg = e.getKey();
            if (pkg.equals(c.getPackageName()) || homes.contains(pkg) || pkg.startsWith("com.android.")) continue;
            if (HomeApps.launchIntent(pm, pkg) == null) continue;
            out.add(new String[] {pkg, label(pm, pkg)});
            if (out.size() >= max) break;
        }
        return out;
    }

    static String label(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    // MARK: - Access

    static boolean hasUsageAccess(Context c) {
        AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
        if (ops == null) return false;
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                == AppOpsManager.MODE_ALLOWED;
    }

    static boolean hasListenerAccess(Context c) {
        if (Build.VERSION.SDK_INT < 27) {
            String flat = android.provider.Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(c.getPackageName());
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.isNotificationListenerAccessGranted(new ComponentName(c, PlaybackListener.class));
    }

    /**
     * Grants the two accesses through the TV's own ADB, the way the home screen switch works. Only once Free My TV's
     * key is approved (after the first switch), so it never pops up the "Allow USB debugging?" prompt by itself.
     * Call off the main thread.
     */
    static void ensureAccess(Context c) {
        boolean usage = hasUsageAccess(c), listener = hasListenerAccess(c);
        boolean overlay = android.provider.Settings.canDrawOverlays(c);   // the phone's alerts (AlertOverlay)
        boolean logs = !RemoteKeys.SONY   // holding Volume (VolumeHold reads Sony's key log)
                || c.checkSelfPermission("android.permission.READ_LOGS") == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (usage && listener && overlay && logs) return;
        if (!HomeMode.FREE.equals(HomeMode.state(c).optString("mode"))) return;
        try (Adb adb = Adb.connect(c, 5_000, null)) {
            String pkg = c.getPackageName();
            if (!usage) adb.shell("appops set " + pkg + " GET_USAGE_STATS allow");
            if (!listener) adb.shell("cmd notification allow_listener " + pkg + "/" + PlaybackListener.class.getName());
            if (!overlay) adb.shell("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow");
            if (!logs) adb.shell("pm grant " + pkg + " android.permission.READ_LOGS");   // VolumeHold starts with the key service
            AppLog.w("history: access granted over adb");
        } catch (Exception e) {
            AppLog.w("history access: " + e.getMessage());
        }
    }
}
