package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The phone's Home tab (PhoneRemote's page and Nook Phone): the same home screen as the TV's. This gives it what to
 * draw, from the main process: the greeting and weather, the shelf's apps as tiles (drawn as the TV draws them: brand
 * colours and logo, or the app's banner), Continue watching with its pictures, the Cricket row's matches (from the
 * home screen's cached lookup: no extra calls), and the current wallpaper photo. Opening things goes to PhoneRemote.
 */
final class PhoneHome {
    private PhoneHome() {}

    private static final Map<String, byte[]> ICONS = new HashMap<>();
    private static String wallpaperName;
    private static byte[] wallpaper;

    static JSONObject home(Context c) {
        JSONObject o = new JSONObject();
        try {
            String period = Photos.period();
            String special = Celebrate.greeting(c);
            o.put("greeting", special != null ? special : period.equals("morning") ? Ui.tr("Good morning")
                    : period.equals("afternoon") ? Ui.tr("Good afternoon") : period.equals("evening") ? Ui.tr("Good evening")
                    : Ui.tr("Good night"));
            o.put("effect", Celebrate.effect(c));
            HomeData.Weather wx = HomeData.get(c).weather;
            if (Prefs.weather() && !Double.isNaN(wx.temp)) {
                o.put("weather", HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay));
                o.put("highLow", HomeData.highLow(wx, "  "));
            }
            List<Celebrate.Day> reminders = Celebrate.remindersToday(c);
            if (!reminders.isEmpty()) o.put("reminder", reminders.get(0).name + " · " + Celebrate.time(reminders.get(0).minute));
            JSONArray shelf = new JSONArray();
            for (HomeApps.Item it : HomeApps.shelf(c, false)) shelf.put(new JSONObject().put("p", it.pkg).put("n", it.label));
            o.put("shelf", shelf);
            JSONArray row = new JSONArray();
            List<History.Entry> entries = History.continueWatching(c, 8);
            for (int i = 0; i < entries.size(); i++) {
                History.Entry e = entries.get(i);
                row.put(new JSONObject().put("i", i).put("title", e.title).put("app", History.label(c.getPackageManager(), e.pkg))
                        .put("progress", e.progress()));
            }
            o.put("row", row);
            JSONArray cricket = new JSONArray();
            if (Cricket.hasKey()) {
                @SuppressWarnings("deprecation")
                String data = c.getSharedPreferences("cricket", Context.MODE_MULTI_PROCESS).getString("data", null);
                if (data != null) {
                    for (Cricket.Match m : Cricket.matchesFrom(new JSONArray(data))) {
                        cricket.put(new JSONObject().put("name", m.name).put("type", m.type).put("status", m.status)
                                .put("live", m.live).put("ended", m.ended).put("score", m.score).put("at", m.startsAt)
                                .put("t1", m.shorts[0]).put("t2", m.shorts[1]).put("f1", m.flagUrls[0]).put("f2", m.flagUrls[1]));
                    }
                }
            }
            o.put("cricket", cricket);
            File photo = Photos.forHome(c);
            o.put("wallpaper", photo == null ? "" : photo.getName());
        } catch (Exception e) {
            AppLog.w("phone home: " + e.getMessage());
        }
        return o;
    }

    /** The home screen's photo now, as a JPEG for a phone (1280 px wide; made once per photo). */
    static synchronized byte[] wallpaper(Context c) {
        File f = Photos.forHome(c);
        if (f == null) return null;
        if (f.getName().equals(wallpaperName) && wallpaper != null) return wallpaper;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= 1280) opts.inSampleSize *= 2;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
        if (b == null) return null;
        if (b.getWidth() > 1280) {
            Bitmap s = Bitmap.createScaledBitmap(b, 1280, Math.round(b.getHeight() * 1280f / b.getWidth()), true);
            b.recycle();
            b = s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 84, out);
        b.recycle();
        wallpaperName = f.getName();
        wallpaper = out.toByteArray();
        return wallpaper;
    }

    /** A shelf app's tile, as on the TV (384x216 PNG): brand colours and logo, or its banner. */
    static synchronized byte[] icon(Context c, String pkg) {
        if (ICONS.containsKey(pkg)) return ICONS.get(pkg);
        HomeApps.Item it = null;
        for (HomeApps.Item i : HomeApps.shelf(c, false)) if (i.pkg.equals(pkg)) it = i;
        if (it == null) return null;
        int w = 384, h = 216;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        int[] style = HomeView.tileStyle(it.key);
        if (it.logo != null) {
            if (style != null) p.setShader(new LinearGradient(0, 0, 0, h, style[0], style[1], Shader.TileMode.CLAMP));
            else p.setColor(0xFF161618);
            cv.drawRect(0, 0, w, h, p);
            p.setShader(null);
            if (style != null && style[2] == 1) {
                p.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN));
            }
            Bitmap logo = style != null && style[2] == 2 && it.logoDark != null ? it.logoDark : it.logo;
            float fit = Math.min(w * 0.6f / logo.getWidth(), h * 0.5f / logo.getHeight()) * it.logoScale;
            float lw = logo.getWidth() * fit, lh = logo.getHeight() * fit;
            cv.drawBitmap(logo, null, new RectF((w - lw) / 2, (h - lh) / 2, (w + lw) / 2, (h + lh) / 2), p);
        } else if (it.tile != null) {
            cv.drawBitmap(it.tile, null, new RectF(0, 0, w, h), p);
        } else {
            cv.drawColor(0xFF2C2C2E);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.PNG, 100, bytes);
        out.recycle();
        ICONS.put(pkg, bytes.toByteArray());
        return ICONS.get(pkg);
    }

    /** A Continue watching card's picture (JPEG), or null. */
    static byte[] art(Context c, int i) {
        List<History.Entry> entries = History.continueWatching(c, 8);
        if (i < 0 || i >= entries.size()) return null;
        Bitmap b = History.art(c, entries.get(i), 640);
        if (b == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 85, out);
        return out.toByteArray();
    }

    /** Continue watching: the shell command that opens it on the TV (its deep link, else its app), or null. */
    static String watchCommand(Context c, int i) {
        List<History.Entry> entries = History.continueWatching(c, 8);
        if (i < 0 || i >= entries.size()) return null;
        History.Entry e = entries.get(i);
        if (e.intentUri != null && !e.intentUri.contains("'")) return "am start '" + e.intentUri + "'";
        android.content.Intent l = HomeApps.launchIntent(c.getPackageManager(), e.pkg);
        return l == null || l.getComponent() == null ? null : "am start -n " + l.getComponent().flattenToShortString();
    }
}
