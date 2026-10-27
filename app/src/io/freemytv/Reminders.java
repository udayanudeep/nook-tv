package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

/**
 * Your reminders (Celebrate's REMINDER days, added from the phone) as a card in the corner at their time, over
 * whatever is on screen, once that day. With the TV off at that time, it shows when the TV is next on that day.
 * Checked once a minute by ScreenTime's tick, in the main process.
 */
final class Reminders {
    private Reminders() {}

    private static final long SHOW_MS = 20_000;

    static void check(Context c) {
        Calendar now = Calendar.getInstance();
        int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        String day = String.format(java.util.Locale.ROOT, "%tY%<tm%<td", now);
        SharedPreferences sp = c.getSharedPreferences("reminders", Context.MODE_PRIVATE);
        Set<String> shown = new HashSet<>(sp.getStringSet(day, new HashSet<>()));
        boolean changed = false;
        for (Celebrate.Day d : Celebrate.remindersToday(c)) {
            if (d.minute > minute) continue;
            String key = d.name + "|" + d.minute;
            if (!shown.add(key)) continue;
            changed = true;
            AlertOverlay.Alert a = new AlertOverlay.Alert();
            a.app = Ui.trf("Reminder · {time}", "time", Celebrate.time(d.minute));
            a.title = d.name;
            a.ms = SHOW_MS;
            AlertOverlay.show(c, a);
            AppLog.w("reminder shown");
        }
        if (changed) sp.edit().clear().putStringSet(day, shown).apply();   // only today's kept
    }
}
