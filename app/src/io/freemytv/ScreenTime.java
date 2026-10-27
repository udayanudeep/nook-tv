package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Screen-time reminder (Settings > Extras): after Prefs.screenTimeHours with the screen on, a gentle card in the
 * corner (AlertOverlay) suggests a break, and again every as many hours after that. Turning the TV off (the screen
 * going dark) starts the count again. Counted once a minute in the main process, which PlaybackListener keeps alive;
 * the same tick checks the volume limit (VolumeLimit) and, with the screen on, your reminders (Reminders).
 */
final class ScreenTime {
    private ScreenTime() {}

    /**
     * Control Center's sleep timer: a card a minute before, then standby. What was done is kept in this process's own
     * file (the settings file is the home screen's to write).
     */
    private static void sleepTimer(Context c) {
        long at = Prefs.sleepAt();
        if (at <= 0) return;
        android.content.SharedPreferences done = c.getSharedPreferences("sleep-timer", Context.MODE_PRIVATE);
        if (done.getLong("handled", 0) == at) return;
        long now = System.currentTimeMillis();
        if (now >= at) {
            done.edit().putLong("handled", at).apply();
            if (now - at < 10 * MINUTE) TvActions.sleepNow(c);   // not hours later (the TV was off, the timer is old)
        } else if (at - now <= 90_000 && done.getLong("warned", 0) != at) {
            done.edit().putLong("warned", at).apply();
            AlertOverlay.Alert a = new AlertOverlay.Alert();
            a.app = Ui.BRAND;
            a.title = Ui.tr("Sleep timer");
            a.text = Ui.tr("The TV turns off in a minute.");
            a.ms = 15_000;
            AlertOverlay.show(c, a);
        }
    }

    private static final long MINUTE = 60_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable tick;
    private static int minutesOn, remindAt;

    static synchronized void start(Context c) {
        if (tick != null) return;
        final Context app = c.getApplicationContext();
        tick = new Runnable() {
            @Override public void run() {
                count(app);
                MAIN.postDelayed(this, MINUTE);
            }
        };
        MAIN.postDelayed(tick, MINUTE);
    }

    private static void count(Context c) {
        VolumeLimit.check(c);
        sleepTimer(c);
        PowerManager power = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (power == null || !power.isInteractive()) {   // the TV is off: a break
            minutesOn = 0;
            remindAt = 0;
            return;
        }
        minutesOn++;
        Reminders.check(c);
        int hours = Prefs.screenTimeHours();
        if (hours <= 0) {
            remindAt = 0;
            return;
        }
        if (remindAt == 0 || remindAt > minutesOn + hours * 60) remindAt = hours * 60;   // turned on (or shortened) just now
        if (minutesOn < remindAt) return;
        remindAt = minutesOn + hours * 60;
        AlertOverlay.Alert a = new AlertOverlay.Alert();
        a.app = Ui.BRAND;
        int watched = minutesOn / 60;
        a.title = Ui.tr("Time for a short break?");
        a.text = watched == 1 ? Ui.tr("The TV has been on for an hour. Rest your eyes for a few minutes.")
                : Ui.trf("The TV has been on for {n} hours. Rest your eyes for a few minutes.", "n", watched);
        AlertOverlay.show(c, a);
        AppLog.w("screen time: reminder after " + minutesOn + " min");
    }
}
