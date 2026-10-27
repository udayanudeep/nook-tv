package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Recent play history (the clock button at the top of the home screen, or Recent in the drawer): "Continue watching"
 * from the TV's Watch Next list and our own log of what played, then the recently used apps. Material 3 Expressive:
 * a dark tonal surface, large rounded cards that morph and spring on focus, pill buttons and progress indicators.
 */
public class RecentActivity extends Activity {
    private static final int SURFACE = 0xFF141218, CONTAINER = 0xFF211F26, CONTAINER_HIGH = 0xFF2B2930,
            PRIMARY = 0xFFD0BCFF, ON_PRIMARY = 0xFF381E72, PRIMARY_CONTAINER = 0xFF4F378B,
            ON_PRIMARY_CONTAINER = 0xFFEADDFF, SECONDARY_CONTAINER = 0xFF4A4458, ON_SECONDARY_CONTAINER = 0xFFE8DEF8,
            TERTIARY_CONTAINER = 0xFF633B48, ON_SURFACE = 0xFFE6E0E9, ON_SURFACE_VARIANT = 0xFFCAC4D0, OUTLINE = 0xFF49454F;

    private static final ExecutorService LOADER = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private float k = 1;
    private LinearLayout watchingRow, appsRow;
    private TextView watchingEmpty, appsEmpty;
    private View firstFocus;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        setContentView(build());
    }

    @Override protected void onResume() {
        super.onResume();
        load();
    }

    private int px(float v) {
        return Math.round(v * k);
    }

    // MARK: - Layout

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new Backdrop());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(80), px(60), px(80), px(60));
        scroll.addView(col, new ScrollView.LayoutParams(-1, -2));

        // Header: title, subtitle, and the "Clear history" pill on the right.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(Ui.tr("Recent"), 72, ON_SURFACE, Ui.displaySemibold);
        title.setLetterSpacing(-0.015f);
        titles.addView(title);
        titles.addView(text(Ui.tr("What you watched and the apps you used"), 28, ON_SURFACE_VARIANT, Ui.regular));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(pill(Ui.tr("Clear history"), v -> {
            History.clear(this);
            load();
        }));
        col.addView(header);

        col.addView(section(Ui.tr("Continue watching")), sectionParams());
        watchingRow = row();
        col.addView(scroller(watchingRow));
        watchingEmpty = text(Ui.tr("Nothing yet. What you play in YouTube, Netflix, Prime Video, JioHotstar and other apps shows up here."),
                26, ON_SURFACE_VARIANT, Ui.regular);
        watchingEmpty.setPadding(px(8), px(10), 0, 0);
        col.addView(watchingEmpty);

        col.addView(section(Ui.tr("Recently used apps")), sectionParams());
        appsRow = row();
        col.addView(scroller(appsRow));
        appsEmpty = text("", 26, ON_SURFACE_VARIANT, Ui.regular);
        appsEmpty.setPadding(px(8), px(10), 0, 0);
        col.addView(appsEmpty);
        return root;
    }

    private LinearLayout.LayoutParams sectionParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = px(48);
        p.bottomMargin = px(8);
        return p;
    }

    private View section(String name) {
        TextView t = text(name, 34, PRIMARY, Ui.semibold);
        t.setPadding(px(8), 0, 0, 0);
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(px(8), px(24), px(40), px(32));   // room for the focused card to grow
        r.setClipChildren(false);
        r.setClipToPadding(false);
        return r;
    }

    private View scroller(LinearLayout row) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipChildren(false);
        h.setClipToPadding(false);
        h.setSmoothScrollingEnabled(true);
        h.addView(row);
        return h;
    }

    private TextView text(String s, float size, int color, android.graphics.Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k);
        t.setTextColor(color);
        if (face != null) t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Filled tonal pill button (M3): secondary container, primary when focused. */
    private View pill(String label, View.OnClickListener click) {
        TextView b = text(label, 26, ON_SECONDARY_CONTAINER, Ui.medium);
        b.setPadding(px(36), px(20), px(36), px(20));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SECONDARY_CONTAINER);
        bg.setCornerRadius(px(40));
        b.setBackground(bg);
        b.setFocusable(true);
        b.setOnClickListener(click);
        b.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? PRIMARY : SECONDARY_CONTAINER);
            b.setTextColor(has ? ON_PRIMARY : ON_SECONDARY_CONTAINER);
            spring(v, has ? 1.08f : 1f);
        });
        return b;
    }

    // MARK: - Cards

    /** A "Continue watching" card: 16:9 picture, progress, title and app. Focus: corner morph, primary ring, spring. */
    private View watchCard(History.Entry e) {
        int w = px(400), ih = px(225);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setClipChildren(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CONTAINER);
        bg.setCornerRadius(px(28));
        card.setBackground(bg);
        card.setPadding(px(10), px(10), px(10), px(18));

        FrameLayout pic = new FrameLayout(this);
        final float[] radius = {px(20)};
        pic.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius[0]); }
        });
        pic.setClipToOutline(true);
        GradientDrawable ph = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {PRIMARY_CONTAINER, TERTIARY_CONTAINER});
        pic.setBackground(ph);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pic.addView(img, new FrameLayout.LayoutParams(-1, -1));
        TextView initial = text(e.title.substring(0, 1).toUpperCase(), 96, ON_PRIMARY_CONTAINER, Ui.displaySemibold);
        initial.setGravity(Gravity.CENTER);
        pic.addView(initial, new FrameLayout.LayoutParams(-1, -1));
        card.addView(pic, new LinearLayout.LayoutParams(w, ih));

        float p = e.progress();
        if (p >= 0) {
            ProgressBar bar = new ProgressBar(p);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, px(10));
            lp.topMargin = px(16);
            card.addView(bar, lp);
        }
        TextView t = text(e.title, 28, ON_SURFACE, Ui.semibold);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(w, -2);
        tp.topMargin = px(14);
        tp.leftMargin = px(6);
        card.addView(t, tp);
        String meta = History.label(getPackageManager(), e.pkg) + (e.subtitle.isEmpty() ? "" : " · " + e.subtitle);
        TextView m = text(meta + "  ·  " + ago(e.at), 22, ON_SURFACE_VARIANT, Ui.regular);
        m.setSingleLine(true);
        m.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(w, -2);
        mp.topMargin = px(6);
        mp.leftMargin = px(6);
        card.addView(m, mp);

        card.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? PRIMARY_CONTAINER : CONTAINER);
            bg.setStroke(has ? px(4) : 0, PRIMARY);
            bg.setCornerRadius(has ? px(40) : px(28));     // shape morph: rounder when focused
            radius[0] = has ? px(30) : px(20);
            pic.invalidateOutline();
            t.setTextColor(has ? ON_PRIMARY_CONTAINER : ON_SURFACE);
            spring(v, has ? 1.08f : 1f);
        });
        card.setOnClickListener(v -> open(e));
        LOADER.execute(() -> {
            Bitmap b = History.art(this, e, w);
            if (b != null) main.post(() -> {
                img.setImageBitmap(b);
                initial.setVisibility(View.GONE);
            });
        });
        return card;
    }

    /** A recently used app: its TV banner (or icon) on a rounded tile, with its name. */
    private View appCard(String pkg, String name) {
        int w = px(260), ih = px(146);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CONTAINER);
        bg.setCornerRadius(px(24));
        card.setBackground(bg);
        card.setPadding(px(10), px(10), px(10), px(16));

        ImageView img = new ImageView(this);
        img.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(16)); }
        });
        img.setClipToOutline(true);
        PackageManager pm = getPackageManager();
        Drawable banner = null;
        try {
            banner = pm.getApplicationBanner(pkg);
            if (banner == null) {
                img.setScaleType(ImageView.ScaleType.FIT_CENTER);
                img.setPadding(px(30), px(20), px(30), px(20));
                GradientDrawable ib = new GradientDrawable();
                ib.setColor(CONTAINER_HIGH);
                img.setBackground(ib);
                banner = pm.getApplicationIcon(pkg);
            } else {
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            }
        } catch (Exception ignored) {
        }
        img.setImageDrawable(banner);
        card.addView(img, new LinearLayout.LayoutParams(w, ih));
        TextView t = text(name, 24, ON_SURFACE, Ui.medium);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(w, -2);
        tp.topMargin = px(14);
        card.addView(t, tp);
        card.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? PRIMARY : CONTAINER);
            bg.setCornerRadius(has ? px(36) : px(24));
            t.setTextColor(has ? ON_PRIMARY : ON_SURFACE);
            spring(v, has ? 1.1f : 1f);
        });
        card.setOnClickListener(v -> {
            Intent i = HomeApps.launchIntent(pm, pkg);
            if (i != null) start(i);
        });
        return card;
    }

    private static LinearLayout.LayoutParams gap(int px) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.rightMargin = px;
        return p;
    }

    /** Expressive spatial spring: a quick overshoot when focus lands. */
    private static void spring(View v, float scale) {
        v.animate().scaleX(scale).scaleY(scale).translationZ(scale > 1 ? 24 : 0)
                .setInterpolator(new OvershootInterpolator(2.2f)).setDuration(320).start();
    }

    // MARK: - Data

    private void load() {
        LOADER.execute(() -> {
            History.ensureAccess(getApplicationContext());
            final List<History.Entry> watching = History.continueWatching(this, 20);
            final List<String[]> apps = History.recentApps(this, 12);
            final boolean usage = History.hasUsageAccess(this), listener = History.hasListenerAccess(this);
            main.post(() -> show(watching, apps, usage, listener));
        });
    }

    private void show(List<History.Entry> watching, List<String[]> apps, boolean usage, boolean listener) {
        if (isFinishing()) return;
        View focused = getCurrentFocus();
        watchingRow.removeAllViews();
        appsRow.removeAllViews();
        for (History.Entry e : watching) watchingRow.addView(watchCard(e), gap(px(32)));
        for (String[] a : apps) appsRow.addView(appCard(a[0], a[1]), gap(px(28)));
        watchingEmpty.setVisibility(watching.isEmpty() ? View.VISIBLE : View.GONE);
        if (watching.isEmpty() && !listener) {
            watchingEmpty.setText(Ui.tr("Play history needs one-time access. Switch to Free My TV once in its settings, or run: adb shell cmd notification allow_listener io.freemytv/io.freemytv.PlaybackListener"));
        }
        appsEmpty.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
        appsEmpty.setText(usage ? Ui.tr("No apps used in the last 30 days.")
                : Ui.tr("Recent apps need one-time access. Switch to Free My TV once in its settings, or run: adb shell appops set io.freemytv GET_USAGE_STATS allow"));
        if (focused == null || focused.getParent() == null) {
            View first = watchingRow.getChildCount() > 0 ? watchingRow.getChildAt(0)
                    : appsRow.getChildCount() > 0 ? appsRow.getChildAt(0) : null;
            if (first != null) first.requestFocus();
        }
    }

    private void open(History.Entry e) {
        if (e.intentUri != null) {
            try {
                Intent i = Intent.parseUri(e.intentUri, Intent.URI_INTENT_SCHEME);
                if (start(i)) return;
            } catch (Exception ignored) {
            }
        }
        Intent i = HomeApps.launchIntent(getPackageManager(), e.pkg);
        if (i != null) start(i);
    }

    private boolean start(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception ex) {
            AppLog.w("recent: couldn't open: " + ex.getMessage());
            return false;
        }
    }

    private static String ago(long at) {
        long min = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
        if (min < 1) return Ui.tr("now");
        if (min < 60) return min + " min";
        long h = min / 60;
        if (h < 24) return h + " h";
        long d = h / 24;
        return d + (d == 1 ? " day" : " days");
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
            finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // MARK: - Drawables

    /** M3 Expressive linear progress: rounded active part, a gap, and the track with its end stop dot. */
    private final class ProgressBar extends View {
        private final float progress;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        ProgressBar(float progress) {
            super(RecentActivity.this);
            this.progress = progress;
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), rad = h / 2, gapW = h * 0.8f;
            float end = Math.max(h, w * progress);
            p.setColor(PRIMARY);
            r.set(0, 0, end, h);
            c.drawRoundRect(r, rad, rad, p);
            if (end + gapW < w) {
                p.setColor(SECONDARY_CONTAINER);
                r.set(end + gapW, 0, w, h);
                c.drawRoundRect(r, rad, rad, p);
                p.setColor(PRIMARY);
                c.drawCircle(w - rad, rad, rad * 0.45f, p);
            }
        }
    }

    /** The dark tonal surface with two soft color fields, like the settings screen. */
    private final class Backdrop extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            c.drawColor(SURFACE);
            blob(c, b.width() * 0.18f, b.height() * 0.1f, b.width() * 0.4f, Ui.alpha(PRIMARY_CONTAINER, 0.55f));
            blob(c, b.width() * 0.85f, b.height() * 0.95f, b.width() * 0.42f, Ui.alpha(TERTIARY_CONTAINER, 0.45f));
        }

        private void blob(Canvas c, float x, float y, float r, int color) {
            p.setShader(new RadialGradient(x, y, r, color, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, r, p);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }
}
