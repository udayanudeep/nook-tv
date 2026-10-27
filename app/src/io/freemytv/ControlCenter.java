package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;

/**
 * Control Center (the top-right button on the home screen): a glass panel under the button with the TV's quick
 * controls. Sleep timer, Turn off now, Free up memory, Network, Speed test, Sound output, Screensaver, Appearance.
 * ↑ ↓ move, OK does it (or steps the value), ← → step the timer and the appearance, Back (or ↑ at the top) closes.
 * Drawn by HomeView on its own layer; what it does is in TvActions.
 */
final class ControlCenter {
    private static final int SLEEP = 0, OFF = 1, MEMORY = 2, NETWORK = 3, SPEED = 4, SOUND = 5, SAVER = 6, LOOK = 7, ROWS = 8;
    private static final int[] SLEEP_MINUTES = {0, 15, 30, 60, 90, 120};
    private static final String[] THEMES = {"auto", "light", "dark"};
    private static final float W = 470, ROW = 56, TITLE = 58, PAD = 14;

    private final Activity activity;
    private final Runnable redraw, closed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Spring show = new Spring(0.3f, 0.86f, 0);
    private final Spring[] rows = new Spring[ROWS];
    private final String[] values = new String[ROWS];
    private final RectF panel = new RectF(), row = new RectF(), anchor = new RectF();
    private Paint title, label, value, valueOn, labelOn;
    private GlassDrawable glass, focusGlass;
    private float paintK;
    private boolean open, themeChanged;
    private int focus;

    ControlCenter(Activity activity, Runnable redraw, Runnable closed) {
        this.activity = activity;
        this.redraw = redraw;
        this.closed = closed;
        for (int i = 0; i < ROWS; i++) rows[i] = new Spring(0.28f, 0.82f, 0);
    }

    boolean isOpen() {
        return open;
    }

    /** Opens under the button (`from`, in screen coordinates). */
    void open(RectF from) {
        anchor.set(from);
        open = true;
        focus = 0;
        themeChanged = false;
        show.target = 1;
        for (Spring s : rows) s.snap(0);
        values[SLEEP] = sleepValue();
        values[OFF] = "";
        values[MEMORY] = TvActions.freeMemory(activity);
        values[NETWORK] = "";
        values[SPEED] = Ui.tr("Test now");
        values[SOUND] = TvActions.soundOutput(activity);
        values[SAVER] = "";
        values[LOOK] = themeName(Prefs.theme());
        TvActions.network(activity, v -> set(NETWORK, v));
        redraw.run();
    }

    void close() {
        if (!open) return;
        open = false;
        show.target = 0;
        redraw.run();
        if (themeChanged) {   // a new look: the home screen draws itself again
            themeChanged = false;
            HomeData.get(activity).settingsChanged();
            main.postDelayed(activity::recreate, 220);
        }
        closed.run();
    }

    private void set(int i, String v) {
        values[i] = v;
        redraw.run();
    }

    /** One frame; true while it animates (opening, closing, the focus moving). */
    boolean step(float dt) {
        boolean moving = show.step(dt);
        for (int i = 0; i < ROWS; i++) {
            rows[i].target = open && i == focus ? 1 : 0;
            moving |= rows[i].step(dt);
        }
        return moving;
    }

    boolean visible() {
        return open || show.value > 0.002f;
    }

    /** The remote's keys while it's open (all of them: nothing reaches the home screen behind). */
    boolean onKey(KeyEvent e) {
        if (!open) return false;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) {
                    if (focus == 0) close(); else focus--;
                    redraw.run();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && focus < ROWS - 1) {
                    focus++;
                    redraw.run();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) step(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, false);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down && e.getRepeatCount() == 0) step(1, true);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) close();
                return true;
            default:
                return false;   // Home, volume, the app buttons: as usual
        }
    }

    /** OK (`press`) or ← → on the focused row. */
    private void step(int dir, boolean press) {
        switch (focus) {
            case SLEEP: {
                long left = Prefs.sleepAt() - System.currentTimeMillis();
                int at = 0;
                if (left > 0) for (int i = 1; i < SLEEP_MINUTES.length; i++) if (SLEEP_MINUTES[i] * 60_000L >= left - 30_000) { at = i; break; }
                if (left > 0 && at == 0) at = SLEEP_MINUTES.length - 1;
                int next = (at + dir + SLEEP_MINUTES.length) % SLEEP_MINUTES.length;
                Prefs.setSleepAt(next == 0 ? 0 : System.currentTimeMillis() + SLEEP_MINUTES[next] * 60_000L);
                set(SLEEP, sleepValue());
                return;
            }
            case LOOK: {
                int at = java.util.Arrays.asList(THEMES).indexOf(Prefs.theme());
                String next = THEMES[(at + dir + THEMES.length) % THEMES.length];
                Prefs.setTheme(next);
                themeChanged = true;
                set(LOOK, themeName(next));
                return;
            }
            default:
                break;
        }
        if (!press) return;
        switch (focus) {
            case OFF:
                close();
                TvActions.sleepNow(activity);
                break;
            case MEMORY:
                set(MEMORY, Ui.tr("Freeing…"));
                TvActions.freeUp(activity, v -> {
                    set(MEMORY, v);
                    main.postDelayed(() -> set(MEMORY, TvActions.freeMemory(activity)), 3000);
                });
                break;
            case NETWORK:
                start(new Intent(Settings.ACTION_WIFI_SETTINGS));
                break;
            case SPEED:
                if (Ui.tr("Testing…").equals(values[SPEED])) return;
                set(SPEED, Ui.tr("Testing…"));
                TvActions.speedTest(v -> set(SPEED, v));
                break;
            case SOUND:
                start(new Intent(Settings.ACTION_SOUND_SETTINGS));
                break;
            case SAVER:
                start(new Intent(activity, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_SCREENSAVER, true));
                break;
            default:
                break;
        }
    }

    private void start(Intent i) {
        close();
        try {
            activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            AppLog.w("control center: " + e.getMessage());
        }
    }

    private static String sleepValue() {
        long left = Prefs.sleepAt() - System.currentTimeMillis();
        if (left <= 0) return Ui.tr("Off");
        return Ui.trf("Off in {n} min", "n", (left + 59_999) / 60_000);
    }

    private static String themeName(String theme) {
        return theme.equals("light") ? Ui.tr("Light") : theme.equals("dark") ? Ui.tr("Dark") : Ui.tr("Automatic");
    }

    private void paints(float k) {
        if (title != null && paintK == k) return;
        paintK = k;
        title = Ui.text(Ui.medium, 20 * k, 0x99FFFFFF);
        label = Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF);
        labelOn = Ui.text(Ui.semibold, 26 * k, 0xFF1C1B1F);
        value = Ui.text(Ui.regular, 22 * k, 0x99FFFFFF);
        valueOn = Ui.text(Ui.regular, 22 * k, 0xB31C1B1F);
        value.setTextAlign(Paint.Align.RIGHT);
        valueOn.setTextAlign(Paint.Align.RIGHT);
        glass = GlassDrawable.smoked(k, 30 * k);
        focusGlass = new GlassDrawable(16 * k, 1.6f * k, 0xFAFFFFFF, 0xE6E9E7EF, 0xFFFFFFFF);
    }

    /** The panel: smoked glass under the button, growing from its top right corner as it opens. */
    void draw(Canvas c, float k, int screenW) {
        float a = Ui.clamp(show.value);
        if (a <= 0.002f) return;
        paints(k);
        String[] labels = {Ui.tr("Sleep timer"), Ui.tr("Turn off now"), Ui.tr("Free up memory"), Ui.tr("Network"),
                Ui.tr("Speed test"), Ui.tr("Sound output"), Ui.tr("Screensaver"), Ui.tr("Appearance")};
        float right = Math.min(anchor.right + 12 * k, screenW - 30 * k), top = anchor.bottom + 22 * k;
        panel.set(right - W * k, top, right, top + (TITLE + ROWS * ROW + PAD) * k);
        c.save();
        float s = 0.92f + 0.08f * a;
        c.scale(s, s, panel.right, panel.top);
        int alpha = Math.round(255 * a);
        glass.setBounds(Math.round(panel.left), Math.round(panel.top), Math.round(panel.right), Math.round(panel.bottom));
        glass.setAlpha(alpha);
        glass.draw(c);
        title.setAlpha(Math.round(0x99 * a));
        c.drawText(Ui.tr("Control Center"), panel.left + 26 * k, panel.top + 38 * k, title);
        for (int i = 0; i < ROWS; i++) {
            float f = Ui.clamp(rows[i].value);
            float y = panel.top + (TITLE + i * ROW) * k;
            row.set(panel.left + PAD * k, y, panel.right - PAD * k, y + (ROW - 4) * k);
            if (f > 0.01f) {   // the focused row: bright glass, lifted a little
                float g = 1 + 0.03f * f, cx = row.centerX(), cy = row.centerY();
                float hw = row.width() * g / 2, hh = row.height() * g / 2;
                focusGlass.setBounds(Math.round(cx - hw), Math.round(cy - hh), Math.round(cx + hw), Math.round(cy + hh));
                focusGlass.setAlpha(Math.round(alpha * f));
                focusGlass.draw(c);
            }
            float base = row.centerY() + Ui.halfCap(label);
            Paint l = f > 0.5f ? labelOn : label, v = f > 0.5f ? valueOn : value;
            int la = l.getAlpha(), va = v.getAlpha();
            l.setAlpha(Math.round(la * a));
            v.setAlpha(Math.round(va * a));
            c.drawText(labels[i], row.left + 14 * k, base, l);
            String val = values[i] == null ? "" : values[i];
            if (!val.isEmpty()) c.drawText(val, row.right - 14 * k, base, v);
            l.setAlpha(la);
            v.setAlpha(va);
        }
        c.restore();
    }
}
