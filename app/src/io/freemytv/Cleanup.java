package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TV Cleanup (Settings > Extras): switching off ads, tracking, shop-demo and unwanted preloaded apps, the way Clean
 * Your TV does from a phone (github.com/Gathin23/cleanyourtv, MIT; its Sony BRAVIA BF1 list, tested on Android 12),
 * here from the TV itself through its own ADB. Nothing is uninstalled: `pm disable-user --user 0`, so every app can
 * come back (one at a time, or all at once; a factory reset brings them all back too). What Cleanup switched off is
 * recorded (files/cleanup.json), so "turn everything back on" and the Original home screen (HomeMode) bring back
 * exactly those. Google TV's home screen, its setup app and its ambient screensaver are Nook mode's (HomeMode), not
 * here, nor YouTube Music and Play Games (Nook mode switches those off too). The TV's inputs, tuners, settings,
 * store, casting and remote are never in the list.
 */
final class Cleanup {
    private Cleanup() {}

    static final String ADS = "ads", DEMO = "demo", APPS = "apps", VOICE = "voice";

    /** One app in the list. */
    static final class Item {
        final String pkg, group, name, what;
        final boolean recommended;   // switched off by "Clean up"
        boolean installed, off;

        Item(String pkg, String group, String name, String what, boolean recommended) {
            this.pkg = pkg;
            this.group = group;
            this.name = name;
            this.what = what;
            this.recommended = recommended;
        }
    }

    /** The list, in the order shown (only the installed ones show). The names and descriptions in the TV's language. */
    static List<Item> catalogue(Context c) {
        // Sony Pictures Core is what the remote's BRAVIA CORE button opens: not recommended while the button does.
        boolean coreButton = RemoteKeys.SONY && "com.sonypicturescore".equals(Prefs.keyApp(Prefs.KEY_SONY_CORE));
        return new ArrayList<>(Arrays.asList(
            new Item("tv.samba.ssm", ADS, "Samba TV", Ui.tr("Recognises what's on screen to target ads. Always running."), true),
            new Item("com.sony.dtv.promos", ADS, Ui.tr("Sony promotions"), Ui.tr("Promotional banners and offers."), true),
            new Item("com.sony.dtv.sonyselect", ADS, "Sony Select", Ui.tr("Promoted app picks."), true),
            new Item("com.google.android.feedback", ADS, Ui.tr("Google feedback"), Ui.tr("Sends reports to Google."), true),
            new Item("com.sony.dtv.demosupport", DEMO, Ui.tr("Shop demo mode"), Ui.tr("For TVs on display in shops."), true),
            new Item("com.sony.dtv.demosystemsupport", DEMO, Ui.tr("Shop demo system"), Ui.tr("For TVs on display in shops."), true),
            new Item("com.sony.dtv.multiscreendemo", DEMO, Ui.tr("Multi-screen demo"), Ui.tr("For TVs on display in shops."), true),
            new Item("com.google.android.videos", APPS, Ui.tr("Google TV (Play Movies)"), Ui.tr("Google's movie store."), true),
            new Item("com.sonypicturescore", APPS, "Sony Pictures Core",
                    coreButton ? Ui.tr("Kept: your BRAVIA CORE button opens it.") : Ui.tr("Preloaded app."), !coreButton),
            new Item("com.google.android.katniss", VOICE, Ui.tr("Voice search"),
                    Ui.tr("Frees about 115 MB, but the remote's mic button stops working."), false),
            new Item("com.sony.dtv.voicesearch", VOICE, Ui.tr("Sony voice search"), Ui.tr("Goes with voice search."), false)));
    }

    // MARK: - On the TV

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Done {
        void done(String error);   // null when it worked
    }

    /** Reads which ones are installed and which are off. */
    static void read(Context c, List<Item> items, Done done) {
        Context app = c.getApplicationContext();
        IO.execute(() -> {
            String error = null;
            try (Adb adb = Adb.connect(app, 5_000, null)) {
                Set<String> all = packages(adb.shell("pm list packages")), off = packages(adb.shell("pm list packages -d"));
                for (Item it : items) {
                    it.installed = all.contains(it.pkg);
                    it.off = off.contains(it.pkg);
                }
            } catch (Exception e) {
                error = e.getMessage();
            }
            String err = error;
            MAIN.post(() -> done.done(err));
        });
    }

    /** Switches these off (`on` false) or back on; records what Cleanup switched off. */
    static void set(Context c, List<Item> items, boolean on, Done done) {
        Context app = c.getApplicationContext();
        IO.execute(() -> {
            String error = null;
            try (Adb adb = Adb.connect(app, 5_000, null)) {
                Set<String> record = recorded(app);
                for (Item it : items) {
                    if (!it.installed) continue;
                    String out = adb.shell((on ? "pm enable " : "pm disable-user --user 0 ") + it.pkg);
                    boolean worked = on ? out.contains("new state: enabled") : out.contains("disabled-user");
                    if (!worked) {
                        error = it.name + ": " + out.trim();
                        continue;
                    }
                    it.off = !on;
                    if (on) record.remove(it.pkg); else record.add(it.pkg);
                    save(app, record);
                }
            } catch (Exception e) {
                error = e.getMessage();
            }
            String err = error;
            MAIN.post(() -> done.done(err));
        });
    }

    private static Set<String> packages(String list) {
        Set<String> out = new HashSet<>();
        for (String line : list.split("\n")) {
            line = line.trim();
            if (line.startsWith("package:")) out.add(line.substring(8));
        }
        return out;
    }

    // MARK: - The record

    private static File file(Context c) {
        return new File(c.getFilesDir(), "cleanup.json");
    }

    /** What Cleanup switched off (HomeMode's Original brings these back too). */
    static synchronized Set<String> recorded(Context c) {
        Set<String> out = new HashSet<>();
        File f = file(c);
        if (!f.exists()) return out;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 64 * 1024)];
            int n = 0;
            for (int r; n < b.length && (r = in.read(b, n, b.length - n)) > 0; ) n += r;
            JSONArray a = new JSONArray(new String(b, 0, n, StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        } catch (Exception e) {
            AppLog.w("cleanup record: " + e.getMessage());
        }
        return out;
    }

    private static synchronized void save(Context c, Set<String> record) {
        File tmp = new File(c.getFilesDir(), "cleanup.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(new JSONArray(record).toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception e) {
            AppLog.w("cleanup record: " + e.getMessage());
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(file(c));
    }

    /** After the Original home screen turned them back on: nothing of Cleanup's is off any more. */
    static void forget(Context c) {
        save(c, new HashSet<>());
    }
}
