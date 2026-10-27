package io.freemytv;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A settings screen that pairs with a phone: a QR code on the left (an address on the home network), the title and
 * the steps on the right, with pill buttons. Your Photos and Phone Notifications. The Recent screen's dark surface.
 */
abstract class QrActivity extends Activity {
    static final int SURFACE = 0xFF141218, PRIMARY = 0xFFD0BCFF, ON_PRIMARY = 0xFF381E72,
            PRIMARY_CONTAINER = 0xFF4F378B, SECONDARY_CONTAINER = 0xFF4A4458, ON_SECONDARY_CONTAINER = 0xFFE8DEF8,
            TERTIARY_CONTAINER = 0xFF633B48, ON_SURFACE = 0xFFE6E0E9, ON_SURFACE_VARIANT = 0xFFCAC4D0;

    float k = 1;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
    }

    int px(float v) {
        return Math.round(v * k);
    }

    /** The screen: the code for `address` (a gray card without one), the title and subtitle; returns the column. */
    LinearLayout frame(String address, String title, String subtitle) {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new Backdrop());
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(120), px(80), px(120), px(80));
        row.setClipChildren(false);
        root.addView(row, new FrameLayout.LayoutParams(-1, -1));

        // The QR code on a white card (phones read it best dark on light, with its quiet zone).
        ImageView qr = new ImageView(this);
        if (address != null) {
            qr.setImageBitmap(Qr.bitmap(address, 12));
            qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }
        GradientDrawable card = new GradientDrawable();
        card.setColor(address != null ? 0xFFFFFFFF : SECONDARY_CONTAINER);
        card.setCornerRadius(px(36));
        qr.setBackground(card);
        qr.setPadding(px(16), px(16), px(16), px(16));
        row.addView(qr, new LinearLayout.LayoutParams(px(520), px(520)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(64), px(56), px(64), px(60));
        col.setClipChildren(false);   // the focused button grows past its edges
        col.setClipToPadding(false);
        col.setBackground(GlassDrawable.clear(k, px(44)));   // a floating glass window, like the home screen's glass
        col.setElevation(px(14));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
        cp.leftMargin = px(70);
        row.addView(col, cp);
        TextView t = text(title, 72, ON_SURFACE, Ui.displaySemibold);
        t.setLetterSpacing(-0.015f);
        col.addView(t);
        col.addView(text(subtitle, 28, ON_SURFACE_VARIANT, Ui.regular));
        setContentView(root);
        return col;
    }

    LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = px(top);
        return p;
    }

    /** For pill buttons: as wide as their text. */
    LinearLayout.LayoutParams pillGap(int top) {
        LinearLayout.LayoutParams p = gap(top);
        p.width = LinearLayout.LayoutParams.WRAP_CONTENT;
        return p;
    }

    TextView text(String s, float size, int color, android.graphics.Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k);
        t.setTextColor(color);
        if (face != null) t.setTypeface(face);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    /** A glass pill button: clear glass at rest; focused, it lifts, lights up and leans toward you a little. */
    TextView pill(String label, View.OnClickListener click) {
        TextView b = text(label, 28, ON_SURFACE, Ui.medium);
        b.setPadding(px(40), px(22), px(40), px(22));
        GlassDrawable bg = new GlassDrawable(px(40), 1.6f * k, PILL_TOP, PILL_BOTTOM, 0x80FFFFFF);
        b.setBackground(bg);
        b.setFocusable(true);
        b.setOnClickListener(click);
        b.setCameraDistance(px(4000));
        b.setOnFocusChangeListener((v, has) -> {
            if (has) bg.set(0xF2FFFFFF, 0xD9E8E6EE, 0xFFFFFFFF);
            else bg.set(PILL_TOP, PILL_BOTTOM, 0x80FFFFFF);
            b.setTextColor(has ? 0xFF1C1B1F : ON_SURFACE);
            v.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f).rotationX(has ? 6 : 0)
                    .translationZ(has ? px(18) : 0).setDuration(200).start();
        });
        return b;
    }

    private static final int PILL_TOP = 0x33FFFFFF, PILL_BOTTOM = 0x1AFFFFFF;

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
            finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** Your wallpaper photo softly blurred (else the dark surface with two soft color fields), like the settings. */
    private final class Backdrop extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint photo = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            android.graphics.Bitmap room = GlassDrawable.room(QrActivity.this);
            if (room != null) {   // the room: your wallpaper, out of focus behind the glass
                c.drawBitmap(room, null, b, photo);
                c.drawColor(0x99000000);
                return;
            }
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
