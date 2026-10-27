package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Morning briefing (Settings > Extras): the first time the TV comes on in the morning (5:00 to 12:00), a card at the
 * top of the screen for a few seconds: the weather, today's calendar (your Google Calendar's secret iCal address,
 * saved from the phone page, plus your reminders and special days) and one headline (Google News, India). Over
 * whatever is on screen, never taking the remote. Runs in :home (KeepWarm hears the screen come on).
 */
final class Briefing {
    private Briefing() {}

    private static final long SHOW_MS = 15_000, AFTER_SCREEN_ON_MS = 3_000;
    private static final String NEWS = "https://news.google.com/rss?hl=en-IN&gl=IN&ceid=IN:en";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean busy;

    /** The screen came on: the briefing, if it's the morning's first and it's on. */
    static void screenOn(Context c) {
        final Context app = c.getApplicationContext();
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (busy || !Prefs.morningBriefing() || hour < 5 || hour >= 12 || shownToday(app)) return;
        busy = true;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(AFTER_SCREEN_ON_MS);   // the picture comes up first
                Content content = gather(app);
                MAIN.post(() -> show(app, content));
            } catch (Exception e) {
                AppLog.w("briefing: " + e.getMessage());
                busy = false;
            }
        }, "briefing");
        t.setDaemon(true);
        t.start();
    }

    /** A test from Settings: now, whatever the time. */
    static void preview(Context c) {
        final Context app = c.getApplicationContext();
        if (busy) return;
        busy = true;
        Thread t = new Thread(() -> {
            Content content = gather(app);
            MAIN.post(() -> show(app, content));
        }, "briefing");
        t.setDaemon(true);
        t.start();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("briefing", Context.MODE_PRIVATE);
    }

    private static String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }

    private static boolean shownToday(Context c) {
        return todayKey().equals(prefs(c).getString("shown", ""));
    }

    // MARK: - What it says

    static final class Content {
        String weather = "", headline = "";
        final List<String> today = new ArrayList<>();
    }

    private static Content gather(Context c) {
        Content out = new Content();
        HomeData data = HomeData.get(c);
        long stale = System.currentTimeMillis() - data.weatherAt;
        if (stale > 60 * 60_000 || stale < 0) {
            data.refreshIfStale();
            long until = System.currentTimeMillis() + 4_000;   // a fresh forecast if it comes quickly
            while (System.currentTimeMillis() < until && System.currentTimeMillis() - data.weatherAt > 60 * 60_000) {
                try { Thread.sleep(200); } catch (InterruptedException e) { break; }
            }
        }
        HomeData.Weather wx = data.weather;
        if (!Double.isNaN(wx.temp)) {
            out.weather = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, true)
                    + "   " + HomeData.highLow(wx, "  ");
        }
        // Today: the calendar's events by time, then reminders, then the family's celebrations.
        List<String[]> timed = new ArrayList<>();   // {sort key, line}
        try {
            String url = calendarUrl(c);
            if (!url.isEmpty()) for (Event e : events(get(url, 3 * 1024 * 1024))) timed.add(new String[] {e.sort, e.line});
        } catch (Exception e) {
            AppLog.w("briefing calendar: " + e.getMessage());
        }
        for (Celebrate.Day d : Celebrate.today(c)) {
            if (d.reminder()) {
                timed.add(new String[] {String.format(Locale.ROOT, "%04d", d.minute), Celebrate.time(d.minute) + "   " + d.name});
            }
        }
        Collections.sort(timed, (a, b) -> a[0].compareTo(b[0]));
        for (String[] t : timed) out.today.add(t[1]);
        for (Celebrate.Day d : Celebrate.today(c)) {
            if (d.kind.equals(Celebrate.BIRTHDAY)) out.today.add(Ui.trf("{name}'s birthday", "name", d.name));
            else if (d.kind.equals(Celebrate.ANNIVERSARY)) out.today.add(Ui.trf("{name}'s anniversary", "name", d.name));
            else if (d.kind.equals(Celebrate.FESTIVAL)) out.today.add(d.name);
        }
        try {
            out.headline = headline(get(NEWS, 512 * 1024));
        } catch (Exception e) {
            AppLog.w("briefing news: " + e.getMessage());
        }
        return out;
    }

    private static String get(String url, int max) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) Nook");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) > 0 && out.size() < max; ) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    /** The first story's title, without its " - Source". */
    static String headline(String rss) {
        int item = rss.indexOf("<item>");
        if (item < 0) return "";
        Matcher m = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL).matcher(rss);
        if (!m.find(item)) return "";
        String t = m.group(1).replace("<![CDATA[", "").replace("]]>", "");
        t = t.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").trim();
        int dash = t.lastIndexOf(" - ");
        return dash > 20 ? t.substring(0, dash) : t;
    }

    // MARK: - The calendar (iCal)

    static final class Event {
        String sort, line;
    }

    /** The secret iCal address saved from the phone page ("" = none). Kept in a file both processes read. */
    static String calendarUrl(Context c) {
        File f = new File(c.getFilesDir(), "calendar-url.txt");
        if (!f.exists()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[2048];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** Saves (or with "" removes) the address; only https addresses. False when it isn't one. */
    static boolean setCalendarUrl(Context c, String url) {
        url = url.trim();
        if (url.startsWith("webcal://")) url = "https://" + url.substring("webcal://".length());
        File f = new File(c.getFilesDir(), "calendar-url.txt");
        if (url.isEmpty()) return !f.exists() || f.delete();
        if (!url.startsWith("https://") || url.length() > 1500 || url.contains(" ")) return false;
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(url.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Today's events in an iCal file: single ones, and the common repeats (daily, weekly on days, monthly, yearly,
     * with an interval and an end date); cancelled ones and excluded dates are left out.
     */
    static List<Event> events(String ics) {
        List<Event> out = new ArrayList<>();
        String unfolded = ics.replace("\r\n ", "").replace("\r\n\t", "").replace("\n ", "").replace("\n\t", "");
        Calendar today = Calendar.getInstance();
        int y = today.get(Calendar.YEAR), m = today.get(Calendar.MONTH), d = today.get(Calendar.DAY_OF_MONTH);
        String summary = null, start = null, rule = null, status = null;
        List<String> excluded = new ArrayList<>();
        boolean inEvent = false;
        for (String raw : unfolded.split("\r?\n")) {
            if (raw.equals("BEGIN:VEVENT")) {
                inEvent = true;
                summary = start = rule = status = null;
                excluded.clear();
            } else if (raw.equals("END:VEVENT") && inEvent) {
                inEvent = false;
                if (start == null || "CANCELLED".equals(status)) continue;
                Calendar s = parseDate(start);
                if (s == null) continue;
                boolean allDay = start.indexOf('T') < 0;
                boolean onToday = sameDay(s, y, m, d) || (rule != null && repeatsOn(rule, s, today));
                for (String ex : excluded) {
                    Calendar e = parseDate(ex);
                    if (e != null && sameDay(e, y, m, d)) onToday = false;
                }
                if (!onToday) continue;
                Event e = new Event();
                String name = summary == null || summary.isEmpty() ? Ui.tr("Event") : unescape(summary);
                int minute = s.get(Calendar.HOUR_OF_DAY) * 60 + s.get(Calendar.MINUTE);
                e.sort = allDay ? "0000" : String.format(Locale.ROOT, "%04d", minute);
                e.line = allDay ? Ui.trf("All day   {name}", "name", name) : Celebrate.time(minute) + "   " + name;
                out.add(e);
            } else if (inEvent) {
                int colon = raw.indexOf(':');
                if (colon < 0) continue;
                String key = raw.substring(0, colon), value = raw.substring(colon + 1);
                String name = key.contains(";") ? key.substring(0, key.indexOf(';')) : key;
                switch (name) {
                    case "SUMMARY": summary = value; break;
                    case "DTSTART": start = withZone(key, value); break;
                    case "RRULE": rule = value; break;
                    case "STATUS": status = value; break;
                    case "EXDATE": for (String v : value.split(",")) excluded.add(withZone(key, v)); break;
                    default: break;
                }
            }
        }
        return out;
    }

    /** "20261025T093000" with its TZID kept as "...|Asia/Kolkata", so parseDate can place it. */
    private static String withZone(String key, String value) {
        int tz = key.indexOf("TZID=");
        if (tz < 0) return value;
        String zone = key.substring(tz + 5);
        int semi = zone.indexOf(';');
        if (semi >= 0) zone = zone.substring(0, semi);
        return value + "|" + zone.replace("\"", "");
    }

    /** The date (and time) in the TV's time zone: UTC ("Z"), a given zone, a floating time, or a whole day. */
    private static Calendar parseDate(String v) {
        try {
            String zone = null;
            int bar = v.indexOf('|');
            if (bar >= 0) {
                zone = v.substring(bar + 1);
                v = v.substring(0, bar);
            }
            SimpleDateFormat f;
            if (v.length() >= 15 && v.charAt(8) == 'T') {
                f = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.ROOT);
                if (v.endsWith("Z")) f.setTimeZone(TimeZone.getTimeZone("UTC"));
                else if (zone != null) f.setTimeZone(TimeZone.getTimeZone(zone));
                v = v.substring(0, 15);
            } else if (v.length() >= 8) {
                f = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
                v = v.substring(0, 8);
            } else {
                return null;
            }
            Calendar c = Calendar.getInstance();
            c.setTime(f.parse(v));
            return c;
        } catch (ParseException | RuntimeException e) {
            return null;
        }
    }

    private static boolean sameDay(Calendar c, int y, int m, int d) {
        return c.get(Calendar.YEAR) == y && c.get(Calendar.MONTH) == m && c.get(Calendar.DAY_OF_MONTH) == d;
    }

    private static boolean repeatsOn(String rule, Calendar start, Calendar today) {
        String freq = "", byDay = null, until = null;
        int interval = 1;
        for (String part : rule.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String k = part.substring(0, eq), v = part.substring(eq + 1);
            if (k.equals("FREQ")) freq = v;
            else if (k.equals("INTERVAL")) try { interval = Math.max(1, Integer.parseInt(v)); } catch (NumberFormatException ignored) {}
            else if (k.equals("BYDAY")) byDay = v;
            else if (k.equals("UNTIL")) until = v;
        }
        Calendar day0 = (Calendar) start.clone(), now = (Calendar) today.clone();
        for (Calendar c : new Calendar[] {day0, now}) {
            c.set(Calendar.HOUR_OF_DAY, 12);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
        }
        if (now.before(day0)) return false;
        if (until != null) {
            Calendar u = parseDate(until);
            if (u != null) {
                u.set(Calendar.HOUR_OF_DAY, 23);
                u.set(Calendar.MINUTE, 59);
                if (now.after(u)) return false;
            }
        }
        long days = Math.round((now.getTimeInMillis() - day0.getTimeInMillis()) / 86_400_000.0);
        switch (freq) {
            case "DAILY":
                return days % interval == 0;
            case "WEEKLY": {
                // The week's place counts from the start's week (Monday-based, as iCal's default).
                long startMonday = days + ((day0.get(Calendar.DAY_OF_WEEK) + 5) % 7);
                if ((startMonday / 7) % interval != 0) return false;
                String[] codes = {"SU", "MO", "TU", "WE", "TH", "FR", "SA"};
                String code = codes[now.get(Calendar.DAY_OF_WEEK) - 1];
                if (byDay == null) return now.get(Calendar.DAY_OF_WEEK) == day0.get(Calendar.DAY_OF_WEEK);
                for (String b : byDay.split(",")) if (b.endsWith(code)) return true;
                return false;
            }
            case "MONTHLY": {
                if (byDay != null) return false;   // "the second Tuesday": not handled
                int months = (now.get(Calendar.YEAR) - day0.get(Calendar.YEAR)) * 12 + now.get(Calendar.MONTH) - day0.get(Calendar.MONTH);
                return months % interval == 0 && now.get(Calendar.DAY_OF_MONTH) == day0.get(Calendar.DAY_OF_MONTH);
            }
            case "YEARLY":
                return (now.get(Calendar.YEAR) - day0.get(Calendar.YEAR)) % interval == 0
                        && now.get(Calendar.MONTH) == day0.get(Calendar.MONTH)
                        && now.get(Calendar.DAY_OF_MONTH) == day0.get(Calendar.DAY_OF_MONTH);
            default:
                return false;
        }
    }

    private static String unescape(String s) {
        return s.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").trim();
    }

    // MARK: - The card

    private static void show(Context c, Content content) {
        if (!Settings.canDrawOverlays(c)) {
            AppLog.w("briefing: no permission to draw over other apps");
            busy = false;
            return;
        }
        Ui.init(c);
        float k = c.getResources().getDisplayMetrics().widthPixels / 1920f;
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(30 * k);
        col.setPadding(pad, Math.round(26 * k), pad, Math.round(28 * k));
        col.setBackground(GlassDrawable.smoked(k, 30 * k));   // the home screen's glass
        col.setElevation(10 * k);

        String hello = Celebrate.greeting(c);
        col.addView(text(c, hello != null ? hello : Ui.tr("Good morning"), 34 * k, 0xFFFFFFFF, Ui.semibold, 1, 0));
        col.addView(text(c, Ui.date(System.currentTimeMillis()), 20 * k, 0xB3FFFFFF, Ui.regular, 1, 4 * k));
        if (!content.weather.isEmpty()) col.addView(text(c, content.weather, 24 * k, 0xFFFFFFFF, Ui.medium, 1, 20 * k));
        col.addView(text(c, Ui.tr("Today"), 17 * k, 0x99FFFFFF, Ui.semibold, 1, 22 * k));
        if (content.today.isEmpty()) {
            col.addView(text(c, Ui.tr("Nothing planned"), 22 * k, 0xD9FFFFFF, Ui.regular, 1, 6 * k));
        } else {
            int shown = Math.min(4, content.today.size());
            for (int i = 0; i < shown; i++) col.addView(text(c, content.today.get(i), 22 * k, 0xFFFFFFFF, Ui.regular, 1, 6 * k));
            if (content.today.size() > shown) {
                col.addView(text(c, Ui.trf("and {n} more", "n", content.today.size() - shown), 19 * k, 0xB3FFFFFF, Ui.regular, 1, 4 * k));
            }
        }
        if (!content.headline.isEmpty()) {
            col.addView(text(c, Ui.tr("Headline"), 17 * k, 0x99FFFFFF, Ui.semibold, 1, 22 * k));
            col.addView(text(c, content.headline, 22 * k, 0xFFFFFFFF, Ui.regular, 2, 6 * k));
        }

        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(Math.round(640 * k),
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        p.y = Math.round(72 * k);
        try {
            wm.addView(col, p);
        } catch (Exception e) {
            AppLog.w("briefing: " + e.getMessage());
            busy = false;
            return;
        }
        prefs(c).edit().putString("shown", todayKey()).apply();
        col.setAlpha(0);
        col.setTranslationY(-40 * k);
        col.animate().alpha(1).translationY(0).setDuration(320).start();
        MAIN.postDelayed(() -> col.animate().alpha(0).translationY(-40 * k).setDuration(260).withEndAction(() -> {
            try {
                wm.removeView(col);
            } catch (Exception ignored) {
            }
            busy = false;
        }).start(), SHOW_MS);
    }

    private static TextView text(Context c, String s, float px, int color, android.graphics.Typeface face, int lines, float top) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        if (face != null) t.setTypeface(face);
        t.setMaxLines(lines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(0, Math.round(top), 0, 0);
        return t;
    }
}
