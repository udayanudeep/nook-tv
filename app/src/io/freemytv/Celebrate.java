package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Special days. The family's own, added from the phone (PhoneRemote's Special days tab): birthdays, anniversaries,
 * festivals of your own (yearly) and reminders ("Pay electricity bill": once, every month or every year, at a time).
 * And the built-in festivals (Diwali, Sankranti, Christmas, New Year; Settings > Extras > Festival themes).
 * On a celebration the greeting says so and a light effect falls over the wallpaper (HomeView: snow at Christmas,
 * golden sparks for festivals, confetti for birthdays and anniversaries, colours for Sankranti); a built-in festival's
 * own photo set shows if it has photos (Photos.pool). Reminders show as a card at their time (Reminders), on the home
 * screen under the date, and in the morning briefing (Briefing). The days live in a small file the main process
 * writes (the phone page) and the other processes read.
 */
final class Celebrate {
    private Celebrate() {}

    static final int NONE = 0, SNOW = 1, SPARKS = 2, CONFETTI = 3, COLOURS = 4;

    /** Festival photo sets (Photos.SETS), and the hashtag that sends a Telegram photo there is the same word. */
    static final String DIWALI = "diwali", SANKRANTI = "sankranti", CHRISTMAS = "christmas", NEW_YEAR = "newyear";
    static final String[] FESTIVALS = {DIWALI, SANKRANTI, CHRISTMAS, NEW_YEAR};

    /** Diwali by year (the main day, Lakshmi Puja); the day before (Naraka Chaturdashi) counts too. */
    private static final int[][] DIWALI_DAYS = {
        {2026, Calendar.NOVEMBER, 8}, {2027, Calendar.OCTOBER, 29}, {2028, Calendar.OCTOBER, 17},
        {2029, Calendar.NOVEMBER, 5}, {2030, Calendar.OCTOBER, 26}};   // later years: add them here

    /** Today's festival (one of FESTIVALS), or null. Festival themes off: null. */
    static String festival() {
        if (!Prefs.festivals()) return null;
        Calendar now = Calendar.getInstance();
        int y = now.get(Calendar.YEAR), m = now.get(Calendar.MONTH), d = now.get(Calendar.DAY_OF_MONTH);
        if (m == Calendar.JANUARY && d == 1) return NEW_YEAR;
        if (m == Calendar.DECEMBER && d == 31 && now.get(Calendar.HOUR_OF_DAY) >= 18) return NEW_YEAR;
        if (m == Calendar.JANUARY && d >= 13 && d <= 15) return SANKRANTI;   // Bhogi, Sankranti, Kanuma (Pongal)
        if (m == Calendar.DECEMBER && (d == 24 || d == 25)) return CHRISTMAS;
        for (int[] day : DIWALI_DAYS) {
            if (day[0] != y) continue;
            Calendar c = Calendar.getInstance();
            c.set(y, day[1], day[2], 0, 0, 0);
            long diff = startOfDay(now) - c.getTimeInMillis();
            if (diff >= -86_400_000L - 3_600_000L && diff <= 3_600_000L) return DIWALI;   // the day before, and the day
        }
        return null;
    }

    private static long startOfDay(Calendar now) {
        Calendar c = (Calendar) now.clone();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** The greeting for today if it's a special day (birthdays, then anniversaries, own festivals, built-in ones), else null. */
    static String greeting(Context c) {
        List<String> born = new ArrayList<>(), married = new ArrayList<>(), feasts = new ArrayList<>();
        for (Day d : today(c)) {
            if (d.kind.equals(BIRTHDAY)) born.add(d.name);
            else if (d.kind.equals(ANNIVERSARY)) married.add(d.name);
            else if (d.kind.equals(FESTIVAL)) feasts.add(d.name);
        }
        if (!born.isEmpty()) return Ui.trf("Happy birthday, {name}!", "name", String.join(" & ", born));
        if (!married.isEmpty()) return Ui.trf("Happy anniversary, {name}!", "name", String.join(" & ", married));
        if (!feasts.isEmpty()) return Ui.trf("Happy {name}!", "name", feasts.get(0));
        String f = festival();
        if (f == null) return null;
        switch (f) {
            case DIWALI: return Ui.tr("Happy Diwali");
            case SANKRANTI: return Ui.tr("Happy Sankranti");
            case CHRISTMAS: return Ui.tr("Merry Christmas");
            default: return Ui.tr("Happy New Year");
        }
    }

    /** The effect over the wallpaper today (NONE most days). */
    static int effect(Context c) {
        boolean own = false;
        for (Day d : today(c)) {
            if (d.kind.equals(BIRTHDAY) || d.kind.equals(ANNIVERSARY)) return CONFETTI;
            own |= d.kind.equals(FESTIVAL);
        }
        if (own) return SPARKS;
        String f = festival();
        if (f == null) return NONE;
        switch (f) {
            case CHRISTMAS: return SNOW;
            case SANKRANTI: return COLOURS;
            default: return SPARKS;
        }
    }

    // MARK: - The family's days

    static final String BIRTHDAY = "birthday", ANNIVERSARY = "anniversary", FESTIVAL = "festival", REMINDER = "reminder";
    static final String YEARLY = "yearly", MONTHLY = "monthly", ONCE = "once";

    static final class Day {
        final String kind, name, repeat;
        final int year, month, day;   // year only for ONCE; month 1 to 12 (unused for MONTHLY), day 1 to 31
        final int minute;             // a reminder's time of day, in minutes (9:00 = 540)

        Day(String kind, String name, String repeat, int year, int month, int day, int minute) {
            this.kind = kind;
            this.name = name;
            this.repeat = repeat;
            this.year = year;
            this.month = month;
            this.day = day;
            this.minute = minute;
        }

        boolean reminder() {
            return kind.equals(REMINDER);
        }

        /** Whether it falls on this date (month 1 to 12). */
        boolean on(int y, int m, int d) {
            if (repeat.equals(ONCE)) return year == y && month == m && day == d;
            int last = daysIn(y, m);
            if (repeat.equals(MONTHLY)) return Math.min(day, last) == d;   // the 31st: the month's last day
            if (month != m) return false;
            return Math.min(day, last) == d;   // 29 February: the 28th in other years
        }

        JSONObject json() throws Exception {
            return new JSONObject().put("kind", kind).put("name", name).put("repeat", repeat).put("y", year)
                    .put("m", month).put("d", day).put("min", minute);
        }
    }

    private static int daysIn(int y, int m) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, m - 1, 1);
        return c.getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    private static final int MAX = 80;
    private static List<Day> cached = Collections.emptyList();
    private static long cachedStamp = Long.MIN_VALUE;

    private static File file(Context c) {
        return new File(c.getFilesDir(), "days.json");
    }

    /** All the days: celebrations by date, then reminders (read again only when the file changes). */
    static synchronized List<Day> days(Context c) {
        File f = file(c);
        if (!f.exists()) migrateBirthdays(c);
        long stamp = f.lastModified() * 31 + f.length();
        if (stamp == cachedStamp) return cached;
        cachedStamp = stamp;
        List<Day> out = new ArrayList<>();
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] b = new byte[(int) Math.min(f.length(), 256 * 1024)];
                int n = 0;
                for (int r; n < b.length && (r = in.read(b, n, b.length - n)) > 0; ) n += r;
                JSONArray a = new JSONArray(new String(b, 0, n, StandardCharsets.UTF_8));
                for (int i = 0; i < a.length(); i++) {
                    Day d = parse(a.optJSONObject(i));
                    if (d != null) out.add(d);
                }
            } catch (Exception e) {
                AppLog.w("special days: " + e.getMessage());
            }
        }
        Collections.sort(out, (a, b) -> a.reminder() != b.reminder() ? (a.reminder() ? 1 : -1)
                : a.month != b.month ? a.month - b.month : a.day != b.day ? a.day - b.day : a.minute - b.minute);
        cached = Collections.unmodifiableList(out);
        return cached;
    }

    private static Day parse(JSONObject o) {
        if (o == null) return null;
        String kind = o.optString("kind"), name = clean(o.optString("name")), repeat = o.optString("repeat", YEARLY);
        if (!(kind.equals(BIRTHDAY) || kind.equals(ANNIVERSARY) || kind.equals(FESTIVAL) || kind.equals(REMINDER))) return null;
        if (!kind.equals(REMINDER)) repeat = YEARLY;   // celebrations come back every year
        if (!(repeat.equals(YEARLY) || repeat.equals(MONTHLY) || repeat.equals(ONCE))) return null;
        int y = o.optInt("y"), m = o.optInt("m"), d = o.optInt("d"), min = o.optInt("min", 9 * 60);
        if (name.isEmpty() || d < 1 || d > 31 || (!repeat.equals(MONTHLY) && (m < 1 || m > 12))) return null;
        if (repeat.equals(ONCE) && y < 2000) return null;
        return new Day(kind, name, repeat, y, m, d, Math.max(0, Math.min(24 * 60 - 1, min)));
    }

    private static String clean(String s) {
        s = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 40 ? s.substring(0, 40).trim() : s;
    }

    /** Today's days (celebrations and reminders). */
    static List<Day> today(Context c) {
        Calendar now = Calendar.getInstance();
        int y = now.get(Calendar.YEAR), m = now.get(Calendar.MONTH) + 1, d = now.get(Calendar.DAY_OF_MONTH);
        List<Day> out = new ArrayList<>();
        for (Day day : days(c)) if (day.on(y, m, d)) out.add(day);
        return out;
    }

    /** Today's reminders, by time. */
    static List<Day> remindersToday(Context c) {
        List<Day> out = new ArrayList<>();
        for (Day d : today(c)) if (d.reminder()) out.add(d);
        return out;
    }

    /** Adds one from the phone page; false when the list is full or something is wrong. */
    static synchronized boolean add(Context c, JSONObject o) {
        Day d = parse(o);
        if (d == null) return false;
        List<Day> all = new ArrayList<>(days(c));
        pruneOnce(all);
        if (all.size() >= MAX) return false;
        all.add(d);
        return save(c, all);
    }

    /** Removes the one at this place in days(). */
    static synchronized boolean remove(Context c, int index) {
        List<Day> all = new ArrayList<>(days(c));
        if (index < 0 || index >= all.size()) return false;
        all.remove(index);
        pruneOnce(all);
        return save(c, all);
    }

    /** One-time reminders whose day has passed go. */
    private static void pruneOnce(List<Day> all) {
        Calendar now = Calendar.getInstance();
        int today = now.get(Calendar.YEAR) * 10_000 + (now.get(Calendar.MONTH) + 1) * 100 + now.get(Calendar.DAY_OF_MONTH);
        all.removeIf(d -> d.repeat.equals(ONCE) && d.year * 10_000 + d.month * 100 + d.day < today);
    }

    private static boolean save(Context c, List<Day> all) {
        JSONArray a = new JSONArray();
        try {
            for (Day d : all) a.put(d.json());
        } catch (Exception e) {
            return false;
        }
        File tmp = new File(c.getFilesDir(), "days.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            AppLog.w("special days: " + e.getMessage());
            return false;
        }
        return tmp.renameTo(file(c));
    }

    /** The birthdays file of the first version ("MM-dd<tab>Name"), moved into days.json once. */
    private static void migrateBirthdays(Context c) {
        File old = new File(c.getFilesDir(), "birthdays.txt");
        if (!old.exists()) return;
        List<Day> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(old))) {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab != 5 || line.charAt(2) != '-') continue;
                try {
                    out.add(new Day(BIRTHDAY, clean(line.substring(tab + 1)), YEARLY, 0,
                            Integer.parseInt(line.substring(0, 2)), Integer.parseInt(line.substring(3, 5)), 9 * 60));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException e) {
            return;
        }
        if (save(c, out)) {
            //noinspection ResultOfMethodCallIgnored
            old.delete();
        }
    }

    /** "9:00 AM" in the TV's language. */
    static String time(int minute) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, minute / 60);
        c.set(Calendar.MINUTE, minute % 60);
        return Ui.clock(c.getTimeInMillis()) + (Ui.tr("format|h:mm").indexOf('h') >= 0
                ? " " + new java.text.SimpleDateFormat("a", Ui.locale()).format(c.getTime()) : "");
    }
}
