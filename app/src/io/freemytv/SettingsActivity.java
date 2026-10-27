package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Free My TV's settings (the app's icon, "All Apps", or the gear on the home screen), in the style of Apple TV's
 * settings: the big symbol and title on the left, the list on the right with the focused row in white. "Home screen"
 * opens the Free My TV / Original choice (with a check on the current one); the switch runs in the main process
 * (HomeMode) and this screen only asks for it and follows the state file.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private DreamView preview;   // "Screensaver": the screensaver right here, without changing the system setting

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new SettingsView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
        // From ADB: am start -n io.freemytv/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && getIntent().getBooleanExtra(EXTRA_SCREENSAVER, false)) {
            getIntent().removeExtra(EXTRA_SCREENSAVER);
            screensaverOnly = true;
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onPause() {
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) closePreview();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    /** Started with this extra (Control Center's Screensaver, or ADB): only the screensaver, then back to where you were. */
    static final String EXTRA_SCREENSAVER = "screensaver";
    private boolean screensaverOnly;

    void showPreview() {
        preview = new DreamView(this);
        setContentView(preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        if (screensaverOnly) {   // Control Center's Screensaver: back to where you were
            finish();
            return;
        }
        setContentView(view);
        view.resume();
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_CHOICE = 1, HOME_SETTINGS = 2, REMOTE_KEYS = 3, PICKER = 4, SHELF_EDIT = 5,
                ABOUT = 6, EXTRAS = 7;
        // Home Settings has 13 rows (14 with the dollar one) and a hint: all fit in 1080.
        private static final float LIST_ROW_H = 54, LIST_ROW_GAP = 4;
        private static final int PICK_VISIBLE = 9;
        private static final float LIST_X = 1000, LIST_R = 1824, LIST_TOP = 186, ROW_GAP = 8, CHOICE_H = 104;
        // The main menu: ten rows (the comparison, the picks and the notices are under About), then a switch's progress
        // or its error.
        private static final float MAIN_TOP = 120, ROW_H = 70, MAIN_GAP = 6;
        // tvOS: the focused row is a white card with dark text.
        private static final int AMBER = 0xFFFFB4AB, INK = 0xFF1C1C1E;
        // Material 3 Expressive, dark scheme (violet seed).
        private static final int M3_SURFACE = 0xFF141218, M3_CONTAINER = 0xFF211F26, M3_CONTAINER_HIGH = 0xFF2B2930,
                M3_PRIMARY = 0xFFD0BCFF, M3_PRIMARY_CONTAINER = 0xFF4F378B, M3_ON_PRIMARY_CONTAINER = 0xFFEADDFF,
                M3_TERTIARY = 0xFFEFB8C8, M3_TERTIARY_CONTAINER = 0xFF633B48, M3_ON_SURFACE_VARIANT = 0xFFCAC4D0;
        private final android.graphics.Path m3Path = new android.graphics.Path();
        private final float[] m3Radii = new float[8];
        private Paint m3Fill, m3Blob;

        private final SettingsActivity activity;
        private final Scene scene = new Scene();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[16];   // one per row: Home Settings has up to 13
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        // The list picker (the app of a remote button): what it shows, which one is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor (also the top buttons' one, with editingTop): what it can hold (selected ones first, in
        // order) and the selection.
        private final List<String[]> shelfRows = new ArrayList<>();
        private final List<String> shelfOrder = new ArrayList<>();
        private boolean editingTop;
        private int pickFor, pickCurrent, pickScroll;   // pickFor: the remote button (index + 1)
        private static final int PICK_CITY = 0, PICK_LANGUAGE = -1;
        private final List<String[]> cities = new ArrayList<>();
        private String cityStatus = "";
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey;
        private int screen = MAIN, focus;
        private boolean running, okDown, backDown, wasBusy;
        private StaticLayout errorLayout;   // the last error, wrapped (rebuilt only when it changes)
        private String errorShown;
        private final Spoken spoken;
        private long startedAt, lastNanos, pendingAt, lastSky;
        private int w, h;
        private float k = 1;
        private Paint title, subtitle, version, rowLabel, rowValue, rowSub, header, cellLabel, cellValue, warn, spinner, white, shadow;
        private TextPaint notice;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                boolean nowBusy = !busyText().isEmpty();
                if (nowBusy) pendingAt = 0;
                // A switch just ended: TalkBack reads how it went (the newest notice).
                JSONArray notices = state.optJSONArray("notices");
                if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                    JSONObject newest = notices.optJSONObject(0);
                    if (newest != null) announceForAccessibility(newest.optString("text"));
                }
                wasBusy = nowBusy;
                invalidate();
                handler.postDelayed(this, busy() ? 500 : 2000);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            HomeData.Weather wx = HomeData.get(a).weather;
            Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
            scene.setMotion(0.5f, 0.2f);
            scene.applyPalette();
            setFocusable(true);
            setContentDescription(Ui.BRAND);
            // TalkBack: one item per row, id = row + 1.
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { for (int i = 1; i <= rowCount(); i++) out.add(i); }
                @Override public String text(int id) { return id >= 1 && id <= rowCount() ? rowText(id - 1) : ""; }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > rowCount()) return false;
                    rowBox(id - 1).round(out);
                    return true;
                }
                @Override public int focused() { return focus + 1; }
                @Override public void focus(int id) {
                    focus = id - 1;
                    invalidate();
                }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        /** A row as TalkBack reads it: its name and its value, as on screen. */
        private String rowText(int row) {
            switch (screen) {
                case MAIN: {
                    if (row == 0) {
                        String busyText = busyText();
                        String value = busy() ? (busyText.isEmpty() ? Ui.tr("Starting…") : busyText)
                                : freeActive() ? Ui.BRAND : Ui.tr("Original");
                        return Ui.trf("Home Screen, {mode}", "mode", value);
                    }
                    String[] names = {Ui.tr("Screensaver, preview"), Ui.tr("Home Settings"),
                            Ui.tr("Shelf"), Ui.tr("Top Buttons"), Ui.tr("Remote Buttons"), Ui.tr("TV Settings"),
                            Ui.tr("Your Photos"), Ui.tr("Phone Notifications"), Ui.tr("Phone Remote"), Ui.tr("Extras"),
                            Ui.tr("About")};
                    return row - 1 < names.length ? names[row - 1] : "";
                }
                case HOME_CHOICE: {
                    String name = row == 0 ? Ui.BRAND + ", " + Ui.tr("no ads, with the new screensaver")
                            : "Original, " + Ui.tr("the TV's factory home screen");
                    boolean inUse = (row == 0) == freeActive();
                    return name + (inUse ? Ui.tr(". In use") : "") + ", " + Ui.trf("{i} of {n}", "i", row + 1, "n", 2);
                }
                case HOME_SETTINGS:
                case EXTRAS:
                case REMOTE_KEYS: {
                    String[] labels = listLabels(), values = listValues();
                    return row < labels.length ? labels[row] + ", " + values[row] : "";
                }
                case PICKER: {
                    int at = pickScroll + row;
                    if (at >= pickLabels.size()) return "";
                    return pickLabels.get(at) + (at == pickCurrent ? Ui.tr(", selected") : "")
                            + ", " + Ui.trf("{i} of {n}", "i", at + 1, "n", pickLabels.size());
                }
                case SHELF_EDIT: {
                    int at = pickScroll + row;
                    if (at >= shelfRows.size()) return "";
                    String[] app = shelfRows.get(at);
                    int place = shelfOrder.indexOf(app[0]);
                    if (editingTop) {
                        return app[1] + ", " + (place >= 0 ? Ui.trf("in the top buttons, place {n}", "n", place + 1)
                                : Ui.tr("not in the top buttons"));
                    }
                    return app[1] + ", " + (place >= 0 ? Ui.trf("on the shelf, place {n}", "n", place + 1)
                            : Ui.tr("not on the shelf"));
                }
                default:
                    return "";
            }
        }

        private RectF rowBox(int row) {
            float top, height;
            if (screen == MAIN) {
                top = MAIN_TOP + row * (ROW_H + MAIN_GAP);
                height = ROW_H;
            } else if (screen == HOME_CHOICE) {
                top = LIST_TOP + 20 + row * (CHOICE_H + ROW_GAP);
                height = CHOICE_H;
            } else {
                top = LIST_TOP + 20 + row * (LIST_ROW_H + LIST_ROW_GAP);
                height = LIST_ROW_H;
            }
            focusBox.set(LIST_X * k, top * k, LIST_R * k, (top + height) * k);
            return focusBox;
        }

        void resume() {
            if (running) return;
            running = true;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
        }

        void pause() {
            running = false;
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = busy();
            for (Spring s : pop) moving |= !s.settled();
            if (moving || SystemClock.uptimeMillis() - lastSky >= 100) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            scene.setSize(w, h);
            title = Ui.text(Ui.displaySemibold, 64 * k, 0xFFE6E0E9);
            title.setLetterSpacing(-0.01f);
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 26 * k, M3_ON_SURFACE_VARIANT);
            subtitle.setTextAlign(Paint.Align.CENTER);
            version = Ui.text(Ui.regular, 22 * k, 0x66FFFFFF);
            version.setTextAlign(Paint.Align.CENTER);
            rowLabel = Ui.text(Ui.medium, 30 * k, 0xF2FFFFFF);
            rowValue = Ui.text(Ui.regular, 28 * k, 0x8CFFFFFF);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            header = Ui.text(Ui.semibold, 21 * k, 0x99EBEBF5);   // tvOS: quiet gray section headers
            header.setLetterSpacing(0.06f);
            cellLabel = Ui.text(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue = Ui.numbers(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 23 * k, AMBER);
            notice = new TextPaint(Ui.text(Ui.regular, 23 * k, 0xB3FFFFFF));
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(M3_PRIMARY);
            m3Fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            m3Blob = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        }

        private boolean busy() {
            return !busyText().isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private String busyText() {
            long at = state.optLong("busyAt"), now = SystemClock.elapsedRealtime();
            boolean stale = now < at || now - at > 10 * 60_000L;
            return stale ? "" : state.optString("busy");
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        private int rowCount() {
            switch (screen) {
                case MAIN: return 12;
                case ABOUT: return 0;
                case HOME_CHOICE: return 2;
                case HOME_SETTINGS: return settingLabels().length;
                case EXTRAS: return extraLabels().length;
                case PICKER: return Math.min(PICK_VISIBLE, pickLabels.size());
                case SHELF_EDIT: return Math.min(PICK_VISIBLE, shelfRows.size());
                default: return Prefs.KEYS.length;
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && i < rowCount() ? 1 : 0;
                pop[i].step(dt);
            }
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;
            if (up - lastSky >= 100) {
                lastSky = up;
                scene.advance(seconds);
            }
            drawM3Background(c, seconds);

            // On the left, as in tvOS Settings: the app's icon, large and lifted, then its name.
            float cx = 500 * k;
            // Centered on the menu's height (rows from 120 to 870).
            drawAppIcon(c, cx, 420 * k, 300 * k);
            c.drawText(Ui.BRAND, cx, 690 * k, title);
            c.drawText(Ui.tr("Your TV's home screen and screensaver"), cx, 738 * k, subtitle);
            c.drawText(versionName(getContext()), cx, 780 * k, version);

            if (screen == MAIN) drawMain(c, seconds); else if (screen == ABOUT) drawAbout(c); else if (screen == HOME_CHOICE) drawChoice(c); else if (screen == PICKER) drawPicker(c); else if (screen == SHELF_EDIT) drawShelfEditor(c); else drawList(c);
        }

        private void drawMain(Canvas c, float seconds) {
            boolean working = busy();
            String busyText = busyText();
            String mode = freeActive() ? Ui.BRAND : Ui.tr("Original");
            String[] labels = {Ui.tr("Home Screen"), Ui.tr("Screensaver"),
                    Ui.tr("Home Settings"), Ui.tr("Shelf"), Ui.tr("Top Buttons"),
                    Ui.tr("Remote Buttons"), Ui.tr("TV Settings"), Ui.tr("Your Photos"), Ui.tr("Phone Notifications"),
                    Ui.tr("Phone Remote"), Ui.tr("Extras"), Ui.tr("About")};
            String[] values = {working ? "" : mode, Ui.tr("Preview"), "", "", "", "", "", "", "", "", "", ""};
            for (int i = 0; i < labels.length; i++) {
                float y = (MAIN_TOP + i * (ROW_H + MAIN_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                float f = drawRowBackground(c, i);
                int ink = blend(0xF2FFFFFF, INK, f);
                rowLabel.setColor(ink);
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                float right = rowRect.right - 30 * k;
                if (i >= 2) {
                    float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                    Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, rowRect.centerY(), 22 * k, blend(0x66FFFFFF, 0x991C1C1E, f));
                } else if (i == 0 && working) {
                    // Switching: the current step in place of the value, with the spinning arc.
                    String text = busyText.isEmpty() ? Ui.tr("Starting…") : busyText;
                    rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
                    String shown = TextUtils.ellipsize(text, new TextPaint(rowValue), 470 * k, TextUtils.TruncateAt.END).toString();
                    c.drawText(shown, right, base, rowValue);
                    float sx = right - rowValue.measureText(shown) - 26 * k;
                    spinner.setColor(blend(M3_PRIMARY, INK, f));
                    Ui.spinner(c, sx, rowRect.centerY(), 12 * k, seconds * 1.3f, spinner);
                } else {
                    rowValue.setColor(blend(0x8CFFFFFF, 0x8C1C1C1E, f));
                    c.drawText(values[i], right, base, rowValue);
                }
            }

            float y = (MAIN_TOP + labels.length * (ROW_H + MAIN_GAP) + 20) * k;
            String error = state.optString("error");
            if (working && busyText.equals(Ui.tr("Accept “Allow USB debugging?” on the TV (tick “Always allow”)."))) {
                c.drawText(Ui.tr("On the TV: tick “Always allow” and press OK."),
                        LIST_X * k + 30 * k, y, warn);
                y += 46 * k;
            } else if (!working && !error.isEmpty()) {
                // The whole message, on up to five lines: some say what to do (the Wi-Fi one, #14).
                int width = Math.round(760 * k);
                if (errorLayout == null || !error.equals(errorShown) || errorLayout.getWidth() != width) {
                    errorShown = error;
                    errorLayout = StaticLayout.Builder.obtain(error, 0, error.length(), new TextPaint(warn), width)
                            .setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END).build();
                }
                c.save();
                c.translate(LIST_X * k + 30 * k, y - errorLayout.getLineBaseline(0));
                errorLayout.draw(c);
                c.restore();
                y += errorLayout.getLineBaseline(errorLayout.getLineCount() - 1) - errorLayout.getLineBaseline(0) + 46 * k;
            }
        }

        /** About: how the two home screens compare, the picks' status (with TMDB's credit) and the latest notices. */
        private void drawAbout(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 44 * k, 0xFFF5F5F7);
            c.drawText(Ui.tr("About"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            float y = drawComparison(c, (LIST_TOP + 40) * k);
            y = drawRecs(c, y + 26 * k);
            drawNotices(c, y + 40 * k);
        }

        /** Home screen picks: status, and the credit TMDB asks for. */
        private float drawRecs(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("RECOMMENDATIONS"), x, y, header);
            String line;
            String error = recs.optString("error");
            int count = recs.optInt("count");
            if (!hasKey) line = Ui.tr("No TMDB key: see the README (config.properties).");
            else if (error.equals(Recs.NO_KNOWN_APPS)) line = Ui.tr("None of the TV's streaming apps can give picks.");
            else if (error.equals(Recs.NO_APPS)) line = Ui.trf("TMDB has none of the TV's apps for tmdb_region={region}.", "region", Recs.REGION);
            else if (!error.isEmpty()) line = Ui.trf("Error: {error}.", "error", error);
            else if (count > 0) line = Ui.trf("On · {n} titles from the TV's apps, 3 new ones every hour.", "n", count);
            else line = Ui.tr("Fetching titles…");
            c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + 42 * k, notice);
            version.setTextAlign(Paint.Align.LEFT);
            c.drawText(Ui.tr("This product uses the TMDB API but is not endorsed or certified by TMDB."), x, y + 78 * k, version);
            if (!Config.WATCHMODE_KEY.trim().isEmpty()) {
                c.drawText(Ui.tr("Links to the titles by Watchmode."), x, y + 108 * k, version);
            }
            version.setTextAlign(Paint.Align.CENTER);
            return y + (Config.WATCHMODE_KEY.trim().isEmpty() ? 78 : 108) * k;
        }

        /**
         * List row, Material 3 Expressive: rows sit in one tonal group (large outer corners, small inner ones, 4 px
         * gaps); the focused row morphs into a full pill in the primary color and grows a little on a spring.
         * Returns how focused it is.
         */
        private float drawRowBackground(Canvas c, int i) {
            float f = Ui.clamp(pop[i].value);
            int n = Math.max(1, rowCount());
            // tvOS: rows rest as faint glass; the focused one lifts into a white card (and its text turns dark).
            float radius = 16 * k;
            m3Fill.setColor(Ui.alpha(0xFFFFFFFF, 0.07f * (1 - f)));
            if (f < 0.99f) c.drawRoundRect(rowRect, radius, radius, m3Fill);
            if (f > 0.01f) {
                float s = 1 + 0.04f * f;
                float cx = rowRect.centerX(), cy = rowRect.centerY();
                tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
                float r = radius * s;
                tmp.offset(0, 10 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.5f * f));
                c.drawRoundRect(tmp, r, r, shadow);
                tmp.offset(0, -10 * k * f);
                // Bright glass with a lit edge, lifting toward you; a glint crosses it as it rises.
                rowGlass.setBounds(Math.round(tmp.left), Math.round(tmp.top), Math.round(tmp.right), Math.round(tmp.bottom));
                rowGlass.setAlpha(Math.round(255 * f));
                rowGlass.draw(c);
                if (f < 0.995f) {
                    float band = tmp.width() * 0.35f, at = tmp.left - band + (tmp.width() + band) * f;
                    m3Fill.setColor(0xFFFFFFFF);   // the shader's own alpha only
                    m3Fill.setShader(new android.graphics.LinearGradient(at, 0, at + band, 0,
                            new int[] {0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF}, null, android.graphics.Shader.TileMode.CLAMP));
                    c.drawRoundRect(tmp, r, r, m3Fill);
                    m3Fill.setShader(null);
                }
                rowRect.set(tmp);
                rowRect.inset(10 * k * f, 0);
            }
            return f;
        }

        private void roundRect(Canvas c, RectF r, float tl, float tr, float br, float bl, Paint p) {
            m3Radii[0] = m3Radii[1] = tl;
            m3Radii[2] = m3Radii[3] = tr;
            m3Radii[4] = m3Radii[5] = br;
            m3Radii[6] = m3Radii[7] = bl;
            m3Path.reset();
            m3Path.addRoundRect(r, m3Radii, android.graphics.Path.Direction.CW);
            c.drawPath(m3Path, p);
        }

        private android.graphics.Bitmap room;
        private long roomAt;
        private final Paint roomPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final RectF roomRect = new RectF();
        private GlassDrawable window, rowGlass;

        /**
         * The room: your wallpaper photo, out of focus (Vision Pro glass, like the home screen), with a glass window
         * floating over it on the right that holds the menu. Without a photo of yours: the dark tonal surface with two
         * slow, blurred color fields (primary and tertiary).
         */
        private void drawM3Background(Canvas c, float seconds) {
            long up = SystemClock.uptimeMillis();
            if (up - roomAt > 2000) {   // looked up now and then: the photo can change while the settings are open
                roomAt = up;
                room = GlassDrawable.room(getContext());
            }
            if (window == null) {
                window = GlassDrawable.clear(k, 40 * k);
                rowGlass = new GlassDrawable(16 * k, 1.6f * k, 0xF7FFFFFF, 0xE0E9E7EF, 0xFFFFFFFF);
            }
            if (room != null) {
                roomRect.set(0, 0, w, h);
                c.drawBitmap(room, null, roomRect, roomPaint);
                c.drawColor(0x99000000);
            } else {
                drawBlobs(c, seconds);
            }
            window.setBounds(Math.round((LIST_X - 34) * k), Math.round(84 * k), Math.round((LIST_R + 34) * k), Math.round(1050 * k));
            window.draw(c);
        }

        private void drawBlobs(Canvas c, float seconds) {
            c.drawColor(0xFF0C0C0E);
            float t = seconds * 0.12f;
            blob(c, (420 + 90 * (float) Math.sin(t)) * k, (380 + 60 * (float) Math.cos(t * 0.8f)) * k, 700 * k, Ui.alpha(0xFF34307A, 0.5f));
            blob(c, (1500 + 120 * (float) Math.cos(t * 0.7f)) * k, (900 + 50 * (float) Math.sin(t)) * k, 760 * k, Ui.alpha(0xFF4E2A48, 0.4f));
        }

        private void blob(Canvas c, float x, float y, float r, int color) {
            m3Blob.setShader(new android.graphics.RadialGradient(x, y, r, color, color & 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(x, y, r, m3Blob);
        }

        private android.graphics.LinearGradient iconFill;
        private float iconFillAt;

        /** The app's icon: a rounded square in violet to plum with the sparkles, on a soft shadow. */
        private void drawAppIcon(Canvas c, float cx, float cy, float size) {
            tmp.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
            float r = size * 0.22f;
            tmp.offset(0, 18 * k);
            shadow.setColor(0x8C000000);
            c.drawRoundRect(tmp, r, r, shadow);
            tmp.offset(0, -18 * k);
            if (iconFill == null || iconFillAt != tmp.top) {
                iconFillAt = tmp.top;
                iconFill = new android.graphics.LinearGradient(tmp.left, tmp.top, tmp.right, tmp.bottom,
                        0xFF7B6CFF, 0xFFB44FD6, android.graphics.Shader.TileMode.CLAMP);
            }
            m3Fill.setColor(0xFFFFFFFF);   // opaque: the rows leave this paint nearly transparent
            m3Fill.setShader(iconFill);
            c.drawRoundRect(tmp, r, r, m3Fill);
            m3Fill.setShader(null);
            Symbols.draw(getContext(), c, "sparkles", cx, cy, size * 0.5f, 0xFFFFFFFF);
        }

        /** Material 3 Expressive "cookie" shape (12 soft scallops), slowly turning. */
        private void drawCookie(Canvas c, float cx, float cy, float r, float degrees, int color) {
            m3Path.reset();
            int lobes = 12, steps = 240;
            double rot = Math.toRadians(degrees);
            for (int j = 0; j <= steps; j++) {
                double a = 2 * Math.PI * j / steps;
                double rr = r * (1 + 0.065 * Math.cos(lobes * a));
                float x = cx + (float) (rr * Math.cos(a + rot)), y = cy + (float) (rr * Math.sin(a + rot));
                if (j == 0) m3Path.moveTo(x, y); else m3Path.lineTo(x, y);
            }
            m3Path.close();
            m3Fill.setColor(color);
            c.drawPath(m3Path, m3Fill);
        }

        private float drawComparison(Canvas c, float y) {
            float x = LIST_X * k + 30 * k, colA = LIST_R * k - 220 * k, colB = LIST_R * k - 30 * k;
            c.drawText(Ui.tr("COMPARISON"), x, y, header);
            cellValue.setColor(0x73FFFFFF);
            c.drawText(Ui.tr("Original"), colA, y, cellValue);
            c.drawText(Ui.BRAND, colB, y, cellValue);
            cellValue.setColor(0xD9FFFFFF);
            JSONObject metrics = state.optJSONObject("metrics");
            JSONObject a = metrics == null ? null : metrics.optJSONObject(HomeMode.ORIGINAL);
            JSONObject b = metrics == null ? null : metrics.optJSONObject(HomeMode.FREE);
            String[][] rows = {{Ui.tr("Home button"), "homeMs"}, {Ui.tr("Free memory"), "memAvailableMB"},
                    {Ui.tr("Home screen memory"), "homePssMB"}};
            for (int i = 0; i < rows.length; i++) {
                float base = y + (46 + 40 * i) * k;
                c.drawText(rows[i][0], x, base, cellLabel);
                c.drawText(metric(a, rows[i][1]), colA, base, cellValue);
                c.drawText(metric(b, rows[i][1]), colB, base, cellValue);
            }
            return y + (46 + 40 * rows.length) * k;
        }

        private void drawNotices(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("NOTICES"), x, y, header);
            JSONArray notices = state.optJSONArray("notices");
            int shown = 0;
            for (int i = 0; notices != null && i < notices.length() && shown < 2; i++) {
                JSONObject o = notices.optJSONObject(i);
                if (o == null) continue;
                String line = ago(o.optLong("at")) + " · " + Ui.brand(o.optString("text"));   // saved before the rename too
                c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + (42 + 38 * shown) * k, notice);
                shown++;
            }
            if (shown == 0) c.drawText(Ui.tr("None yet."), x, y + 42 * k, notice);
        }

        /** Home screen choice: Free My TV or the original, with a check on the one in use. */
        private void drawChoice(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 44 * k, 0xFFE6E0E9);
            c.drawText(Ui.tr("Home Screen"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String[][] options = {{Ui.BRAND, Ui.tr("No ads, with the new screensaver")},
                    {Ui.tr("Original"), Ui.tr("The TV's factory home screen")}};
            int current = freeActive() ? 0 : 1;
            for (int i = 0; i < 2; i++) {
                float y = (LIST_TOP + 20 + i * (CHOICE_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CHOICE_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                rowSub.setColor(blend(0x8CFFFFFF, 0x991C1C1E, f));
                c.drawText(options[i][0], rowRect.left + 30 * k, rowRect.top + 44 * k, rowLabel);
                c.drawText(options[i][1], rowRect.left + 30 * k, rowRect.top + 80 * k, rowSub);
                if (i == current) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            c.drawText(Ui.tr("Switching takes a few seconds; the screen blinks while it measures."), LIST_X * k + 30 * k,
                    (LIST_TOP + 20 + 2 * (CHOICE_H + ROW_GAP) + 40) * k, hint);
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String metric(JSONObject m, String key) {
            if (m == null || !m.has(key)) return "—";
            long v = m.optLong(key);
            if (key.equals("homeMs")) return Ui.trf("{n} ms", "n", v);
            if (key.equals("memAvailableMB") && v >= 1024) return Ui.trf("{n} GB", "n", String.format(Ui.locale(), "%.2f", v / 1024.0));
            return Ui.trf("{n} MB", "n", v);
        }

        private static String ago(long at) {
            long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
            if (minutes < 1) return Ui.tr("now");
            if (minutes < 60) return Ui.trf("{n} min ago", "n", minutes);
            if (minutes < 48 * 60) return Ui.trf("{n} h ago", "n", minutes / 60);
            return Ui.plural("plural|one:{n} day ago|other:{n} days ago", minutes / 1440);
        }

        private static String versionName(Context c) {
            try {
                return Ui.BRAND + " " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return Ui.BRAND;
            }
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && focus > 0) focus--;
                    else if (down && scrolling() && pickScroll > 0) pickScroll--;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && focus < rowCount() - 1) focus++;
                    else if (down && scrolling() && pickScroll + rowCount() < listSize()) pickScroll++;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && screen == SHELF_EDIT) {
                        moveShelfApp(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    } else if (down && (screen == HOME_SETTINGS || screen == REMOTE_KEYS || screen == EXTRAS)) {
                        adjust(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (screen == MAIN) return false;   // leaves the settings
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            if (screen == PICKER && (pickFor == PICK_CITY || pickFor == PICK_LANGUAGE)) {
                                focus = pickFor == PICK_CITY ? CITY_ROW : 0;
                                screen = HOME_SETTINGS;
                            } else if (screen == PICKER) {
                                focus = pickFor - 1;
                                screen = REMOTE_KEYS;
                            } else {
                                focus = screen == HOME_SETTINGS ? 2 : screen == SHELF_EDIT ? (editingTop ? 4 : 3)
                                        : screen == REMOTE_KEYS ? 5 : screen == EXTRAS ? 10 : screen == ABOUT ? 11 : 0;
                                screen = MAIN;
                            }
                        }
                        backDown = false;
                    }
                    invalidate();
                    spoken.focusMoved();
                    return true;
                default:
                    return false;
            }
        }

        // MARK: - Home Settings and Remote Buttons

        private static final String[] UNITS = {"auto", "C", "F"}, THEMES = {"auto", "light", "dark"};
        /** Home Settings: language, temperature, theme, sounds, weather, city, quotes, one row per coin, your photos (2). */
        private static final int CITY_ROW = 5, COIN_ROWS = 7;

        private boolean dollar() {
            return "BRL".equalsIgnoreCase(Config.CURRENCY.trim());
        }

        private String[] settingLabels() {
            List<String> l = new ArrayList<>();
            l.add(Ui.tr("Language"));
            l.add(Ui.tr("Temperature"));
            l.add(Ui.tr("Theme"));
            l.add(Ui.tr("Navigation sounds"));
            l.add(Ui.tr("Weather widget"));
            l.add(Ui.tr("City"));
            l.add(Ui.tr("Quotes widget"));
            l.add("Bitcoin");
            l.add("Ethereum");
            l.add("Solana");
            if (dollar()) l.add(Ui.tr("Dollar"));
            l.add(Ui.tr("Wallpaper photos"));           // the last three rows (photoRow() - 1)
            l.add(Ui.tr("Home photo changes"));         // photoRow()
            l.add(Ui.tr("Screensaver photo changes"));
            return l.toArray(new String[0]);
        }

        /** The "Home photo changes" row; the screensaver's is the next one (after the coins, whose count varies). */
        private int photoRow() {
            return settingLabels().length - 2;
        }

        private static String every(int seconds) {
            if (seconds >= 86_400) return Ui.tr("Once a day");
            if (seconds == 3600) return Ui.tr("Every hour");
            if (seconds >= 60) return Ui.trf("Every {n} min", "n", seconds / 60);
            return Ui.trf("Every {n} s", "n", seconds);
        }

        /** The next choice in `values` after `current` (or before it), wrapping around. */
        private static int step(int[] values, int current, int dir) {
            int at = 0;
            for (int i = 0; i < values.length; i++) if (values[i] == current) at = i;
            return values[(at + dir + values.length) % values.length];
        }

        private String[] settingValues() {
            String[] labels = settingLabels();
            String[] v = new String[labels.length];
            String lang = Prefs.language();
            v[0] = lang.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(lang);
            String unit = Prefs.temperature();
            v[1] = unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : Ui.tr("unit|Automatic");
            String theme = Prefs.theme();
            v[2] = theme.equals("light") ? Ui.tr("Light") : theme.equals("dark") ? Ui.tr("Dark") : Ui.tr("Automatic");
            v[3] = onOff(Prefs.sounds());
            v[4] = onOff(Prefs.weather());
            v[CITY_ROW] = !cityStatus.isEmpty() ? cityStatus : Prefs.city().isEmpty() ? "—" : Prefs.city();
            v[6] = onOff(Prefs.quotes());
            // No quotes widget, nowhere to show prices: the coins read as off (and stay locked) until it is back on.
            int photo = photoRow();
            for (int i = COIN_ROWS; i < photo - 1; i++) v[i] = onOff(Prefs.quotes() && Prefs.coin(i - COIN_ROWS));
            String mode = Prefs.wallpaperMode();
            v[photo - 1] = mode.equals("all") ? Ui.tr("All photos") : mode.equals("fav") ? Ui.tr("Favourites") : Ui.tr("By time of day");
            v[photo] = every(Prefs.photoHomeMinutes() * 60);
            v[photo + 1] = every(Prefs.photoDreamSeconds());
            return v;
        }

        /** "auto", then English and every language with a catalogue. */
        private static String[] languageChoices() {
            String[] all = Ui.languages();
            String[] v = new String[all.length + 1];
            v[0] = "auto";
            System.arraycopy(all, 0, v, 1, all.length);
            return v;
        }

        private static String onOff(boolean on) {
            return on ? Ui.tr("On") : Ui.tr("Off");
        }

        private String[] listLabels() {
            return screen == REMOTE_KEYS ? keyLabels() : screen == EXTRAS ? extraLabels() : settingLabels();
        }

        private String[] listValues() {
            return screen == REMOTE_KEYS ? keyValues() : screen == EXTRAS ? extraValues() : settingValues();
        }

        /** Extras: volume limit (and when), the break reminder, the morning briefing (and a preview), festivals, special days. */
        private static String[] extraLabels() {
            return new String[] {Ui.tr("Volume limit"), Ui.tr("Volume limit applies"), Ui.tr("Break reminder"),
                    Ui.tr("Morning briefing"), Ui.tr("Morning briefing preview"), Ui.tr("Festival themes"), Ui.tr("Special days"),
                    Ui.tr("TV Cleanup")};
        }

        private String[] extraValues() {
            int limit = Prefs.volumeLimit(), hours = Prefs.screenTimeHours(), days = Celebrate.days(getContext()).size();
            return new String[] {
                limit == 0 ? Ui.tr("Off") : Ui.trf("Up to {n}", "n", limit),
                Prefs.volumeLimitNightOnly() ? Ui.tr("At night (10 PM to 7 AM)") : Ui.tr("All day"),
                hours == 0 ? Ui.tr("Off") : hours == 1 ? Ui.tr("After 1 hour") : Ui.trf("After {n} hours", "n", hours),
                onOff(Prefs.morningBriefing()),
                Ui.tr("Show now"),
                onOff(Prefs.festivals()),
                days == 0 ? Ui.tr("None yet") : Ui.plural("plural|one:{n} saved|other:{n} saved", days),
                Cleanup.recorded(getContext()).isEmpty() ? Ui.tr("Review") : Ui.plural("plural|one:{n} app off|other:{n} apps off",
                        Cleanup.recorded(getContext()).size())};
        }

        private String[] keyLabels() {
            if (RemoteKeys.STEM_NETFLIX) return new String[] {Ui.tr("Netflix button")};
            if (RemoteKeys.KTC) return new String[] {Ui.tr("Tabii button")};
            if (RemoteKeys.SONY) return new String[] {Ui.tr("BRAVIA CORE button"),
                    Ui.tr("Netflix button"), Ui.tr("Disney+ button"),
                    Ui.tr("Prime Video button"), Ui.tr("YouTube button"),
                    Ui.tr("Crunchyroll button"), Ui.tr("Sony LIV button"), Ui.tr("Google Play button")};
            return new String[] {Ui.tr("Netflix button"), Ui.tr("YouTube button"),
                    Ui.tr("Prime Video button"), Ui.tr("TCL Channel button")};
        }

        private String[] keyValues() {
            String[] v = new String[Prefs.KEYS.length];
            for (int i = 0; i < v.length; i++) {
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[i]));
                v[i] = at >= 0 ? appLabels.get(at) : Ui.tr("Not installed");
            }
            return v;
        }

        /** Installed TV apps, by name, after "Nothing": what a remote button can open. */
        private void loadApps() {
            List<String[]> found = new ArrayList<>();
            for (String[] app : HomeApps.launchable(getContext())) {
                if (!app[0].equals(getContext().getPackageName())) found.add(app);
            }
            Collections.sort(found, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
            appPkgs.clear();
            appLabels.clear();
            appPkgs.add("");
            // On Sony, "nothing" leaves the key to the TV, which opens its usual app.
            appLabels.add(RemoteKeys.SONY ? Ui.tr("Sony default") : Ui.tr("Nothing"));
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
        }

        /** Changes the focused row's value one step (OK and → go forward, ← goes back). */
        private void adjust(int dir) {
            if (screen == EXTRAS) {
                switch (focus) {
                    case 0: Prefs.setVolumeLimit(step(Prefs.VOLUME_LIMITS, Prefs.volumeLimit(), dir)); break;
                    case 1: Prefs.setVolumeLimitNightOnly(!Prefs.volumeLimitNightOnly()); break;
                    case 2: Prefs.setScreenTimeHours(step(Prefs.SCREEN_TIME_HOURS, Prefs.screenTimeHours(), dir)); break;
                    case 3: Prefs.setMorningBriefing(!Prefs.morningBriefing()); break;
                    case 5: Prefs.setFestivals(!Prefs.festivals()); break;
                    default: break;   // the preview and Special days: OK only
                }
                return;
            }
            if (screen == REMOTE_KEYS) {
                int n = appPkgs.size();
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[focus]));
                int next = at < 0 ? (dir > 0 ? 0 : n - 1) : (at + dir + n) % n;
                Prefs.setKeyApp(Prefs.KEYS[focus], appPkgs.get(next));
                return;
            }
            switch (focus) {
                case 0: break;   // OK opens the language picker.
                case 1: {
                    int at = java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature());
                    Prefs.setTemperature(UNITS[(at + dir + UNITS.length) % UNITS.length]);
                    break;
                }
                case 2: {
                    int at = java.util.Arrays.asList(THEMES).indexOf(Prefs.theme());
                    Prefs.setTheme(THEMES[(at + dir + THEMES.length) % THEMES.length]);
                    break;
                }
                case 3: Prefs.setSounds(!Prefs.sounds()); break;
                case 4: Prefs.setWeather(!Prefs.weather()); break;
                case CITY_ROW: return;
                case 6: Prefs.setQuotes(!Prefs.quotes()); break;
                default:
                    if (focus == photoRow() - 1) {
                        int at = java.util.Arrays.asList(Prefs.WALLPAPER_MODES).indexOf(Prefs.wallpaperMode());
                        Prefs.setWallpaperMode(Prefs.WALLPAPER_MODES[(at + dir + Prefs.WALLPAPER_MODES.length) % Prefs.WALLPAPER_MODES.length]);
                        break;
                    }
                    if (focus == photoRow()) {
                        Prefs.setPhotoHomeMinutes(step(Prefs.PHOTO_HOME_MINUTES, Prefs.photoHomeMinutes(), dir));
                        break;
                    }
                    if (focus == photoRow() + 1) {
                        Prefs.setPhotoDreamSeconds(step(Prefs.PHOTO_DREAM_SECONDS, Prefs.photoDreamSeconds(), dir));
                        break;
                    }
                    if (!Prefs.quotes()) return;
                    Prefs.setCoin(focus - COIN_ROWS, !Prefs.coin(focus - COIN_ROWS));
                    break;
            }
            HomeData.get(getContext()).settingsChanged();
        }

        private void askCity() {
            if (Ui.tr("Searching…").equals(cityStatus)) return;
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setHint(Ui.tr("Type the city's name"));
            field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("City"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Search"), (d, which) -> searchCity(field.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                searchCity(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        private void searchCity(String name) {
            if (name.trim().isEmpty()) return;
            cityStatus = Ui.tr("Searching…");
            invalidate();
            new Thread(() -> {
                List<String[]> found;
                String error;
                try {
                    found = HomeData.findCity(name);
                    error = found.isEmpty() ? Ui.tr("No city with that name.") : "";
                } catch (Exception e) {
                    found = new ArrayList<>();
                    error = Ui.trf("Couldn't search now: {error}", "error", e.getMessage());
                    AppLog.w("city search: " + e.getMessage());
                }
                List<String[]> result = found;
                String message = error;
                handler.post(() -> {
                    cityStatus = message;
                    if (!result.isEmpty() && screen == HOME_SETTINGS) {
                        cities.clear();
                        cities.addAll(result);
                        openPicker(PICK_CITY);
                        spoken.focusMoved();
                    }
                    invalidate();
                    if (!message.isEmpty()) handler.postDelayed(() -> { cityStatus = ""; invalidate(); }, 4000);
                });
            }).start();
        }

        private boolean scrolling() {
            return screen == PICKER || screen == SHELF_EDIT;
        }

        private int listSize() {
            return screen == SHELF_EDIT ? shelfRows.size() : pickLabels.size();
        }

        // MARK: - Shelf editor

        private void openShelfEditor(boolean top) {
            editingTop = top;
            shelfOrder.clear();
            if (top) {
                shelfOrder.addAll(HomeApps.topButtonKeys());
                rebuildShelfRows(HomeApps.topCandidates());
            } else {
                // Only the apps the shelf shows: one that was uninstalled would hold a place (and count toward the
                // limit) without a row to take it off.
                PackageManager pm = getContext().getPackageManager();
                for (String pkg : HomeApps.shelfPackages(getContext())) if (HomeApps.launchIntent(pm, pkg) != null) shelfOrder.add(pkg);
                rebuildShelfRows(HomeApps.shelfCandidates(getContext()));
            }
            pickScroll = 0;
            focus = 0;
            screen = SHELF_EDIT;
        }

        /** Selected apps first, in shelf order, then the rest in the order they came. */
        private void rebuildShelfRows(List<String[]> candidates) {
            shelfRows.clear();
            for (String pkg : shelfOrder) {
                for (String[] cand : candidates) if (cand[0].equals(pkg)) { shelfRows.add(cand); break; }
            }
            for (String[] cand : candidates) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand);
        }

        /** Same rows after a change: keeps the focus on the app that was focused. */
        private void refreshShelfRows(String focusedPkg) {
            List<String[]> all = new ArrayList<>(shelfRows);
            rebuildShelfRows(all);
            for (int i = 0; i < shelfRows.size(); i++) {
                if (!shelfRows.get(i)[0].equals(focusedPkg)) continue;
                int visible = rowCount();
                if (i < pickScroll) pickScroll = i;
                else if (i >= pickScroll + visible) pickScroll = i - visible + 1;
                focus = i - pickScroll;
                break;
            }
            if (editingTop) Prefs.setTopButtons(shelfOrder); else Prefs.setShelf(shelfOrder);
        }

        /** OK: puts the app on the shelf (at the end) or takes it off. */
        private void toggleShelfApp() {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            if (shelfOrder.contains(pkg)) shelfOrder.remove(pkg);
            else if (editingTop || shelfOrder.size() < HomeApps.MAX_SHELF) shelfOrder.add(pkg);   // three buttons: no limit to reach
            else return;
            refreshShelfRows(pkg);
        }

        /** ← → : moves a shelf app one place earlier or later. */
        private void moveShelfApp(int dir) {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            int at = shelfOrder.indexOf(pkg), to = at + dir;
            if (at < 0 || to < 0 || to >= shelfOrder.size()) return;
            shelfOrder.remove(at);
            shelfOrder.add(to, pkg);
            refreshShelfRows(pkg);
        }

        private void drawShelfEditor(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 44 * k, 0xFFE6E0E9);
            c.drawText(editingTop ? Ui.tr("Top Buttons") : Ui.tr("Shelf"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                String[] app = shelfRows.get(pickScroll + i);
                int place = shelfOrder.indexOf(app[0]);
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(place >= 0 ? 0xF2FFFFFF : 0x80FFFFFF, place >= 0 ? INK : 0x801C1C1E, f));
                rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(TextUtils.ellipsize(app[1], new TextPaint(rowLabel), 520 * k, TextUtils.TruncateAt.END).toString(), rowRect.left + 30 * k, base, rowLabel);
                c.drawText(place >= 0 ? "#" + (place + 1) : Ui.tr("shelf|Off"), rowRect.right - 30 * k, base, rowValue);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText(editingTop ? Ui.tr("OK adds or removes a button. ← → change the order.")
                    : Ui.trf("OK adds or removes (up to {n}). ← → change the order.", "n", HomeApps.MAX_SHELF),
                    LIST_X * k + 30 * k, hy, hint);
        }

        private void openPicker(int which) {
            pickFor = which;
            pickLabels.clear();
            if (which == PICK_CITY) {
                for (String[] city : cities) pickLabels.add(city[0]);
                pickCurrent = -1;
            } else if (which == PICK_LANGUAGE) {
                String[] choices = languageChoices();
                for (String code : choices) {
                    pickLabels.add(code.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(code));
                }
                pickCurrent = java.util.Arrays.asList(choices).indexOf(Prefs.language());
            } else {
                pickLabels.addAll(appLabels);
                pickCurrent = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[which - 1]));
            }
            int visible = Math.min(PICK_VISIBLE, pickLabels.size());
            int at = Math.max(0, pickCurrent);
            pickScroll = Math.max(0, Math.min(at - 3, pickLabels.size() - visible));
            focus = at - pickScroll;
            screen = PICKER;
        }

        /** A long list (the apps): nine rows at a time, the one in use checked. */
        private void drawPicker(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 44 * k, 0xFFE6E0E9);
            String title = pickFor == PICK_CITY ? Ui.tr("City")
                    : pickFor == PICK_LANGUAGE ? Ui.tr("Language") : keyLabels()[pickFor - 1];
            c.drawText(title, LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                c.drawText(pickLabels.get(at), rowRect.left + 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
                if (at == pickCurrent) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText((pickScroll + focus + 1) + " / " + pickLabels.size() + "  ·  " + Ui.tr("OK picks, Back cancels."),
                    LIST_X * k + 30 * k, hy, hint);
        }

        private void drawList(Canvas c) {
            boolean keys = screen == REMOTE_KEYS, extras = screen == EXTRAS;
            String[] labels = listLabels();
            String[] values = listValues();
            Paint heading = Ui.text(Ui.displaySemibold, 44 * k, 0xFFE6E0E9);
            c.drawText(keys ? Ui.tr("Remote Buttons") : extras ? Ui.tr("Extras") : Ui.tr("Home Settings"),
                    LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            for (int i = 0; i < labels.length; i++) {
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                boolean locked = screen == HOME_SETTINGS && i >= COIN_ROWS && i < labels.length - 3 && !Prefs.quotes();   // the coins
                if (extras && i == 1 && Prefs.volumeLimit() == 0) locked = true;   // "when" means nothing without a limit
                rowValue.setColor(blend(locked ? 0x59FFFFFF : 0xB3FFFFFF, locked ? 0x591C1C1E : 0xB31C1C1E, f));
                if (locked) rowLabel.setColor(blend(0x73FFFFFF, 0x731C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                c.drawText(values[i], rowRect.right - 30 * k, base, rowValue);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            float hy = (LIST_TOP + 20 + labels.length * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText(Ui.tr("OK or the arrows change the value."), LIST_X * k + 30 * k, hy, hint);
            if (extras) {
                c.drawText(Ui.tr("Special days: press OK and add them from your phone."), LIST_X * k + 30 * k, hy + 38 * k, hint);
            }
            if (keys) {
                c.drawText(RemoteKeys.VESTEL ? Ui.tr("YouTube, Prime Video and Google Play: the TV itself controls those buttons.")
                        : RemoteKeys.KTC ? Ui.tr("Netflix, YouTube and Prime Video: the TV itself controls those buttons.")
                        : RemoteKeys.SONY ? Ui.tr("Sony default: the button opens what it always did.")
                        : RemoteKeys.FORMOVIE ? Ui.tr("The other buttons: the TV itself controls them.")
                        : RemoteKeys.supported() ? Ui.tr("Disney and Globoplay: the TV itself controls those buttons.")
                        : Ui.tr("TCL remote buttons: on this TV they work as always."), LIST_X * k + 30 * k, hy + 38 * k, hint);
            }
        }

        private void activate() {
            Context c = getContext();
            if (screen == ABOUT) return;   // only read
            if (screen == HOME_CHOICE) {
                boolean wantFree = focus == 0;
                boolean already = wantFree ? freeActive() : HomeMode.ORIGINAL.equals(state.optString("mode", HomeMode.ORIGINAL));
                if (!already && !busy()) {
                    HomeMode.request(c, wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                    pendingAt = SystemClock.uptimeMillis();
                }
                screen = MAIN;
                focus = 0;
            } else if (screen == PICKER && pickFor == PICK_CITY) {
                String[] city = cities.get(pickScroll + focus);
                Prefs.setCity(city[1], city[2], city[3]);
                HomeData.get(c).settingsChanged();
                focus = CITY_ROW;
                screen = HOME_SETTINGS;
            } else if (screen == PICKER && pickFor == PICK_LANGUAGE) {
                Prefs.setLanguage(languageChoices()[pickScroll + focus]);
                focus = 0;
                screen = HOME_SETTINGS;
            } else if (screen == PICKER) {
                int at = pickScroll + focus;
                Prefs.setKeyApp(Prefs.KEYS[pickFor - 1], appPkgs.get(at));
                focus = pickFor - 1;
                screen = REMOTE_KEYS;
            } else if (screen == SHELF_EDIT) {
                toggleShelfApp();
                invalidate();
                return;
            } else if (screen == REMOTE_KEYS) {
                openPicker(focus + 1);
            } else if (screen == EXTRAS && focus == 4) {
                Briefing.preview(c);
                return;
            } else if (screen == EXTRAS && focus == 6) {
                activity.startActivity(new Intent(activity, RemoteActivity.class));
            } else if (screen == EXTRAS && focus == 7) {
                activity.startActivity(new Intent(activity, CleanupActivity.class));
            } else if (screen == EXTRAS) {
                adjust(1);
                invalidate();
                return;
            } else if (screen == HOME_SETTINGS && focus == 0) {
                openPicker(PICK_LANGUAGE);
            } else if (screen == HOME_SETTINGS && focus == CITY_ROW) {
                askCity();
                return;
            } else if (screen == HOME_SETTINGS) {
                adjust(1);
                invalidate();
                return;
            } else if (focus == 0) {
                if (busy()) return;
                screen = HOME_CHOICE;
                focus = freeActive() ? 0 : 1;
            } else if (focus == 1) {
                pause();
                activity.showPreview();
            } else if (focus == 2) {
                screen = HOME_SETTINGS;
                focus = 0;
            } else if (focus == 3) {
                openShelfEditor(false);
            } else if (focus == 4) {
                openShelfEditor(true);
            } else if (focus == 5) {
                HomeMode.ensureRemoteService(c, 0);   // the buttons work from inside other apps through this service
                loadApps();
                screen = REMOTE_KEYS;
                focus = 0;
            } else if (focus == 6) {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (focus == 7) {
                activity.startActivity(new Intent(activity, PhotosActivity.class));
            } else if (focus == 8) {
                activity.startActivity(new Intent(activity, PhoneActivity.class));
            } else if (focus == 9) {
                activity.startActivity(new Intent(activity, RemoteActivity.class));
            } else if (focus == 10) {
                screen = EXTRAS;
                focus = 0;
            } else {
                screen = ABOUT;
                focus = 0;
            }
            for (Spring s : pop) s.snap(0);
            invalidate();
            spoken.focusMoved();
        }
    }
}
