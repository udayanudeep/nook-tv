package io.freemytv;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * A pane of smoked glass, as in Vision Pro: a see-through fill that's a little lighter at the top, and an edge that
 * catches the light along the top and fades down the sides. The pop-ups (AlertOverlay, Briefing) and the settings
 * screens' panels and buttons use it, so everything matches the home screen's glass.
 */
final class GlassDrawable extends Drawable {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float radius, stroke;
    private int top, bottom, edgeTop, alpha = 255;

    /** `top` and `bottom`: the fill's colours (with their transparency); `edgeTop`: the light on the top edge. */
    GlassDrawable(float radius, float stroke, int top, int bottom, int edgeTop) {
        this.radius = radius;
        this.stroke = stroke;
        this.top = top;
        this.bottom = bottom;
        this.edgeTop = edgeTop;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(stroke);
    }

    /** A pane of clear glass (a settings panel): barely tinted, with the lit edge. */
    static GlassDrawable clear(float k, float radius) {
        return new GlassDrawable(radius, 1.6f * k, 0x24FFFFFF, 0x0FFFFFFF, 0x8CFFFFFF);
    }

    /**
     * The room behind the settings screens: your wallpaper photo, softly blurred (as the home screen has it now), or
     * null when the home screen shows no photo of yours.
     */
    static android.graphics.Bitmap room(android.content.Context c) {
        if (!Photos.any(c)) return null;
        Backdrop b = Recs.get(c).photoBackdrop();
        return b != null && b.photo ? b.soft : null;
    }

    /** The usual smoked glass of the pop-ups. */
    static GlassDrawable smoked(float k, float radius) {
        return new GlassDrawable(radius, 1.6f * k, 0xD9302D36, 0xEB16151A, 0xA6FFFFFF);
    }

    /** New colours (a focused button lights up); redraws. */
    void set(int top, int bottom, int edgeTop) {
        this.top = top;
        this.bottom = bottom;
        this.edgeTop = edgeTop;
        onBoundsChange(getBounds());
        invalidateSelf();
    }

    @Override protected void onBoundsChange(Rect b) {
        r.set(b.left + stroke / 2, b.top + stroke / 2, b.right - stroke / 2, b.bottom - stroke / 2);
        fill.setShader(new LinearGradient(0, b.top, 0, b.bottom, top, bottom, Shader.TileMode.CLAMP));
        int faint = (edgeTop & 0x00FFFFFF) | ((edgeTop >>> 24) / 6 << 24);
        edge.setShader(new LinearGradient(0, b.top, 0, b.bottom, new int[] {edgeTop, faint, 0x00FFFFFF, faint},
                new float[] {0, 0.25f, 0.65f, 1}, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas c) {
        fill.setAlpha(alpha);
        edge.setAlpha(alpha);
        c.drawRoundRect(r, radius, radius, fill);
        c.drawRoundRect(r, radius, radius, edge);
    }

    @Override public void setAlpha(int a) {
        alpha = a;
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter cf) {
        fill.setColorFilter(cf);
        edge.setColorFilter(cf);
    }

    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
