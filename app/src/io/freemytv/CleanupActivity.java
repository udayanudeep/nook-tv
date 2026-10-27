package io.freemytv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * TV Cleanup (Settings > Extras): the plan first (each app, what it does, on or off), then one press to switch off the
 * recommended ones, OK on any app to switch it off or back on, and one to turn everything back on. Glass, like the
 * rest of Nook. The work is Cleanup's.
 */
public class CleanupActivity extends Activity {
    private static final int TEXT = 0xFFE6E0E9, DIM = 0xFFCAC4D0, INK = 0xFF1C1B1F;

    private float k;
    private final List<Cleanup.Item> items = new ArrayList<>();
    private LinearLayout list;
    private TextView status, cleanButton, restoreButton;
    private boolean busy;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new Room());

        LinearLayout window = new LinearLayout(this);
        window.setOrientation(LinearLayout.VERTICAL);
        window.setPadding(px(64), px(52), px(64), px(40));
        window.setBackground(GlassDrawable.clear(k, px(44)));
        window.setClipChildren(false);
        window.setClipToPadding(false);
        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(px(1500), -1, Gravity.CENTER_HORIZONTAL);
        wp.topMargin = px(60);
        wp.bottomMargin = px(60);
        root.addView(window, wp);

        window.addView(text(Ui.tr("TV Cleanup"), 60, TEXT, Ui.displaySemibold));
        TextView sub = text(Ui.tr("Switch off ads, tracking, shop demos and apps you don't use. Nothing is deleted: each one can come back."),
                26, DIM, Ui.regular);
        window.addView(sub, gap(10));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setClipChildren(false);
        cleanButton = pill("", v -> cleanRecommended());
        restoreButton = pill(Ui.tr("Turn everything back on"), v -> restoreAll());
        buttons.addView(cleanButton);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
        rp.leftMargin = px(20);
        buttons.addView(restoreButton, rp);
        window.addView(buttons, gap(30));

        status = text("", 24, DIM, Ui.regular);
        window.addView(status, gap(22));

        ScrollView scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setPadding(px(6), px(10), px(6), px(30));
        scroll.addView(list);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, 0, 1);
        sp.topMargin = px(14);
        window.addView(scroll, sp);

        setContentView(root);
        items.addAll(Cleanup.catalogue(this));
        status.setText(Ui.tr("Reading the TV…"));
        updateButtons();
        cleanButton.requestFocus();
        Cleanup.read(this, items, error -> {
            if (error != null) status.setText(Ui.trf("Couldn't read the TV: {error}", "error", error));
            else showMemory();
            rebuild();
        });
    }

    private void showMemory() {
        status.setText(TvActions.freeMemory(this));
    }

    /** The rows, by group, installed apps only. */
    private void rebuild() {
        list.removeAllViews();
        String group = null;
        for (Cleanup.Item it : items) {
            if (!it.installed) continue;
            if (!it.group.equals(group)) {
                group = it.group;
                list.addView(text(groupName(group), 22, 0x99FFFFFF, Ui.semibold), gap(list.getChildCount() == 0 ? 0 : 26));
            }
            list.addView(row(it), gap(10));
        }
        if (list.getChildCount() == 0) list.addView(text(Ui.tr("Nothing to clean up on this TV."), 26, DIM, Ui.regular));
        updateButtons();
    }

    private static String groupName(String g) {
        switch (g) {
            case Cleanup.ADS: return Ui.tr("Ads and tracking");
            case Cleanup.DEMO: return Ui.tr("Shop demo mode");
            case Cleanup.APPS: return Ui.tr("Preloaded apps");
            default: return Ui.tr("Optional");
        }
    }

    /** One app: its name and what it does, and On / Off at the right. OK switches it. */
    private View row(Cleanup.Item it) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(28), px(16), px(28), px(16));
        GlassDrawable bg = new GlassDrawable(px(18), 1.4f * k, 0x1FFFFFFF, 0x0DFFFFFF, 0x4DFFFFFF);
        r.setBackground(bg);
        r.setFocusable(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(it.name + (it.recommended ? "" : "  ·  " + Ui.tr("your choice")), 27, TEXT, Ui.medium);
        TextView what = text(it.what, 21, DIM, Ui.regular);
        what.setMaxLines(1);
        what.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name);
        col.addView(what, gap(4));
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        TextView state = text(stateText(it), 24, it.off ? 0xFF8FD694 : DIM, Ui.semibold);
        r.addView(state);
        r.setOnFocusChangeListener((v, has) -> {
            if (has) bg.set(0xF2FFFFFF, 0xD9E8E6EE, 0xFFFFFFFF);
            else bg.set(0x1FFFFFFF, 0x0DFFFFFF, 0x4DFFFFFF);
            name.setTextColor(has ? INK : TEXT);
            what.setTextColor(has ? 0xB31C1B1F : DIM);
            state.setTextColor(has ? INK : it.off ? 0xFF8FD694 : DIM);
            v.animate().scaleX(has ? 1.02f : 1f).scaleY(has ? 1.02f : 1f).translationZ(has ? px(12) : 0).setDuration(160).start();
        });
        r.setOnClickListener(v -> {
            if (busy) return;
            busy = true;
            state.setText(it.off ? Ui.tr("Turning on…") : Ui.tr("Turning off…"));
            List<Cleanup.Item> one = new ArrayList<>();
            one.add(it);
            Cleanup.set(this, one, it.off, error -> {
                busy = false;
                if (error != null) status.setText(error);
                else showMemory();
                state.setText(stateText(it));
                updateButtons();
            });
        });
        return r;
    }

    private static String stateText(Cleanup.Item it) {
        return it.off ? Ui.tr("Switched off") : Ui.tr("On");
    }

    private List<Cleanup.Item> recommendedOn() {
        List<Cleanup.Item> out = new ArrayList<>();
        for (Cleanup.Item it : items) if (it.installed && it.recommended && !it.off) out.add(it);
        return out;
    }

    private List<Cleanup.Item> cleanedOff() {
        java.util.Set<String> mine = Cleanup.recorded(this);
        List<Cleanup.Item> out = new ArrayList<>();
        for (Cleanup.Item it : items) if (it.installed && it.off && mine.contains(it.pkg)) out.add(it);
        return out;
    }

    private void updateButtons() {
        int n = recommendedOn().size();
        cleanButton.setText(n == 0 ? Ui.tr("All clean") : Ui.plural("plural|one:Clean up {n} app|other:Clean up {n} apps", n));
        restoreButton.setVisibility(cleanedOff().isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void cleanRecommended() {
        List<Cleanup.Item> todo = recommendedOn();
        if (busy || todo.isEmpty()) return;
        busy = true;
        status.setText(Ui.tr("Switching off…"));
        Cleanup.set(this, todo, false, error -> {
            busy = false;
            if (error != null) status.setText(error);
            else status.setText(Ui.trf("Done. {memory}", "memory", TvActions.freeMemory(this)));
            rebuild();
            cleanButton.requestFocus();
        });
    }

    private void restoreAll() {
        List<Cleanup.Item> todo = cleanedOff();
        if (busy || todo.isEmpty()) return;
        busy = true;
        status.setText(Ui.tr("Turning back on…"));
        Cleanup.set(this, todo, true, error -> {
            busy = false;
            if (error != null) status.setText(error);
            else showMemory();
            rebuild();
            cleanButton.requestFocus();
        });
    }

    // MARK: - Views

    private int px(float v) {
        return Math.round(v * k);
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = px(top);
        return p;
    }

    private TextView text(String s, float size, int color, android.graphics.Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k);
        t.setTextColor(color);
        if (face != null) t.setTypeface(face);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    /** A glass pill button, as on the other settings screens. */
    private TextView pill(String label, View.OnClickListener click) {
        TextView b = text(label, 28, TEXT, Ui.medium);
        b.setPadding(px(40), px(20), px(40), px(20));
        GlassDrawable bg = new GlassDrawable(px(40), 1.6f * k, 0x33FFFFFF, 0x1AFFFFFF, 0x80FFFFFF);
        b.setBackground(bg);
        b.setFocusable(true);
        b.setOnClickListener(click);
        b.setOnFocusChangeListener((v, has) -> {
            if (has) bg.set(0xF2FFFFFF, 0xD9E8E6EE, 0xFFFFFFFF);
            else bg.set(0x33FFFFFF, 0x1AFFFFFF, 0x80FFFFFF);
            b.setTextColor(has ? INK : TEXT);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).translationZ(has ? px(16) : 0).setDuration(180).start();
        });
        return b;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
            finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** The room: your wallpaper, out of focus, darkened (else Nook's dark surface). */
    private final class Room extends Drawable {
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            Bitmap room = GlassDrawable.room(CleanupActivity.this);
            if (room != null) {
                c.drawBitmap(room, null, b, p);
                c.drawColor(0xA6000000);
            } else {
                c.drawColor(0xFF141218);
            }
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }
}
