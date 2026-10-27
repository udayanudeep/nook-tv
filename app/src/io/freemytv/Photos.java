package io.freemytv;

import android.content.Context;
import android.graphics.BitmapFactory;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Your own photos, picked in Google Photos and copied to the TV by tools/google_photos.py (Google lets no app read your
 * albums, and its TV sign-in can't ask for Photos). With any there, they replace the picks' images: the home screen
 * background (one at a time, the next every Prefs.photoHomeMinutes) and the screensaver (Prefs.photoDreamSeconds).
 * Named "yyyy-MM-dd_<id>.jpg": the date the photo was taken, shown under it in the screensaver.
 */
final class Photos {
    private Photos() {}

    /** Sets, as sub-folders of the photos folder; photos without a set are in the folder itself ("every day"). */
    static final String[] SETS = {"morning", "afternoon", "evening", "night", "favourites",
            Celebrate.DIWALI, Celebrate.SANKRANTI, Celebrate.CHRISTMAS, Celebrate.NEW_YEAR};

    private static boolean festive(String set) {
        for (String f : Celebrate.FESTIVALS) if (f.equals(set)) return true;
        return false;
    }

    private static List<File> cached = Collections.emptyList(), pool = Collections.emptyList();
    private static long cachedStamp = Long.MIN_VALUE;
    private static String poolKey = "";

    /** A photo whose longer side is under this many pixels shows soft on a 1080p screen: left out of the rotation. */
    private static final int MIN_LONG_SIDE = 1600;
    private static final java.util.Set<String> small = new java.util.HashSet<>();

    private static boolean isPhoto(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp");
    }

    /** All the photos, every set, by name (read again only when a folder changes: the home screen asks every 15 s). */
    static synchronized List<File> list(Context c) {
        File dir = c.getExternalFilesDir("photos");
        if (dir == null) return Collections.emptyList();   // shared storage not mounted yet
        long stamp = dir.lastModified();
        for (String set : SETS) stamp += new File(dir, set).lastModified() * 31;
        if (stamp == cachedStamp) return cached;
        cachedStamp = stamp;
        List<File> out = new ArrayList<>();
        addPhotos(out, dir);
        for (String set : SETS) addPhotos(out, new File(dir, set));
        Collections.sort(out, (a, b) -> a.getName().compareTo(b.getName()));
        small.clear();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        for (File f : out) {
            BitmapFactory.decodeFile(f.getPath(), bounds);
            if (bounds.outWidth > 0 && Math.max(bounds.outWidth, bounds.outHeight) < MIN_LONG_SIDE) small.add(f.getAbsolutePath());
        }
        if (out.size() != cached.size()) AppLog.w("your photos: " + out.size());
        cached = Collections.unmodifiableList(out);
        poolKey = "";
        return cached;
    }

    private static void addPhotos(List<File> out, File dir) {
        File[] files = dir.listFiles((d, name) -> isPhoto(name));
        if (files != null) out.addAll(Arrays.asList(files));
    }

    /** How many photos are too small to look sharp (they stay on the TV, but aren't shown as wallpaper). */
    static synchronized int smallCount(Context c) {
        list(c);
        return small.size();
    }

    static boolean any(Context c) {
        return !list(c).isEmpty();
    }

    /** The set a photo is in ("" = every day). */
    static String setOf(File f) {
        String parent = f.getParentFile() == null ? "" : f.getParentFile().getName();
        for (String set : SETS) if (set.equals(parent)) return set;
        return "";
    }

    /** morning (5 to 12), afternoon (12 to 17), evening (17 to 21) or night, by the TV's clock. */
    static String period() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? "morning" : hour < 17 && hour >= 12 ? "afternoon" : hour >= 17 && hour < 21 ? "evening" : "night";
    }

    /**
     * The photos to show now (Prefs.wallpaperMode): all of them; the ones for this time of day and the everyday
     * ones (so a morning with no morning photos still has something); or only the favourites. Never empty when
     * there are photos: a mode with nothing in it falls back to all of them. The same list object while nothing changes.
     */
    static synchronized List<File> pool(Context c) {
        List<File> every = list(c), all = new ArrayList<>();
        for (File f : every) if (!small.contains(f.getAbsolutePath())) all.add(f);   // sharp ones only...
        if (all.isEmpty()) all = every;                                               // ...unless they are all small
        String mode = Prefs.wallpaperMode(), period = period(), festival = Celebrate.festival();
        String key = mode + "|" + (mode.equals("time") ? period : "") + "|" + festival + "|" + System.identityHashCode(every);
        if (key.equals(poolKey)) return pool;
        poolKey = key;
        List<File> out = new ArrayList<>();
        // A festival's photos show on its day (all day, whatever the mode); the rest of the year they stay away.
        List<File> rest = new ArrayList<>();
        for (File f : all) {
            String set = setOf(f);
            if (festival != null && set.equals(festival)) out.add(f);
            else if (!festive(set)) rest.add(f);
        }
        if (!out.isEmpty()) {
            pool = Collections.unmodifiableList(out);
            return pool;
        }
        if (!rest.isEmpty()) all = rest;
        if (mode.equals("fav")) {
            for (File f : all) if (setOf(f).equals("favourites")) out.add(f);
        } else if (mode.equals("time")) {
            for (File f : all) {
                String set = setOf(f);
                if (set.equals(period)) out.add(f);
            }
            if (out.isEmpty()) for (File f : all) if (setOf(f).isEmpty()) out.add(f);
        }
        pool = Collections.unmodifiableList(out.isEmpty() ? all : out);
        return pool;
    }

    /** The home screen background right now: the same one for Prefs.photoHomeMinutes, then the next. */
    static File forHome(Context c) {
        List<File> all = pool(c);
        if (all.isEmpty()) return null;
        long every = Prefs.photoHomeMinutes() * 60_000L;
        // Local time, so "once a day" changes at midnight.
        long now = System.currentTimeMillis() + java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis());
        return all.get((int) ((now / every) % all.size()));
    }

    /** "March 2024", in the TV's language, from the name's date; empty when the name has none. */
    static String caption(File f) {
        String name = f.getName();
        if (name.length() < 10) return "";
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            in.setLenient(false);
            java.util.Date d = in.parse(name.substring(0, 10));
            String pattern = android.text.format.DateFormat.getBestDateTimePattern(Ui.locale(), "MMMMyyyy");
            return new SimpleDateFormat(pattern, Ui.locale()).format(d);
        } catch (ParseException | IllegalArgumentException e) {
            return "";
        }
    }
}
