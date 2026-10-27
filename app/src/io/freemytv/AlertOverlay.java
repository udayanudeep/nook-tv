package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;

/**
 * Your phone's alerts on the TV (NotifyServer): a smoked card in the top right corner over whatever is on screen, for
 * a few seconds each, one after the other. It never takes the remote's focus. Drawing over other apps is granted
 * over ADB (History.ensureAccess: appops SYSTEM_ALERT_WINDOW).
 */
final class AlertOverlay {
    private AlertOverlay() {}

    private static final long SHOW_MS = 6000, CALL_MS = 15_000;
    private static final int MAX_WAITING = 5;

    static final class Alert {
        String app = "", title = "", text = "";
        Bitmap icon;
        boolean call;
        long ms;   // how long it stays (0 = the usual)
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ArrayDeque<Alert> waiting = new ArrayDeque<>();
    private static View shown;

    /** From any thread. */
    static void show(Context c, Alert a) {
        Context app = c.getApplicationContext();
        MAIN.post(() -> {
            if (shown != null) {
                if (waiting.size() < MAX_WAITING) waiting.add(a);
                return;
            }
            display(app, a);
        });
    }

    private static void display(Context c, Alert a) {
        if (!Settings.canDrawOverlays(c)) {
            AppLog.w("phone alert dropped: no permission to draw over other apps");
            return;
        }
        Ui.init(c);
        float k = c.getResources().getDisplayMetrics().widthPixels / 1920f;
        View card = card(c, a, k);
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(Math.round(400 * k),
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.END;
        p.x = Math.round(36 * k);
        p.y = Math.round(118 * k);   // under the top buttons, not over them
        try {
            wm.addView(card, p);
        } catch (Exception e) {
            AppLog.w("phone alert: " + e.getMessage());
            return;
        }
        shown = card;
        card.setAlpha(0);
        card.setTranslationX(80 * k);
        card.animate().alpha(1).translationX(0).setDuration(260).start();
        MAIN.postDelayed(() -> card.animate().alpha(0).translationX(80 * k).setDuration(220).withEndAction(() -> {
            try {
                wm.removeView(card);
            } catch (Exception ignored) {
            }
            shown = null;
            Alert next = waiting.poll();
            if (next != null) display(c, next);
        }).start(), a.ms > 0 ? a.ms : a.call ? CALL_MS : SHOW_MS);
    }

    private static View card(Context c, Alert a, float k) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(14 * k);
        row.setPadding(pad, pad, Math.round(18 * k), pad);
        // Smoked glass with a lit edge, like the home screen's; a call: the edge glows green.
        row.setBackground(a.call ? new GlassDrawable(18 * k, 2.2f * k, 0xD9302D36, 0xEB16151A, 0xFF6DD58C)
                : GlassDrawable.smoked(k, 18 * k));
        row.setElevation(8 * k);

        ImageView icon = new ImageView(c);
        if (a.icon != null) icon.setImageBitmap(a.icon);
        else icon.setImageResource(R.drawable.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int size = Math.round(44 * k);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(size, size);
        ip.rightMargin = Math.round(14 * k);
        row.addView(icon, ip);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(text(c, a.call ? Ui.trf("{app} · Call", "app", a.app) : a.app, 16 * k, 0xB3FFFFFF, Ui.medium, 1));
        if (!a.title.isEmpty()) col.addView(text(c, a.title, 21 * k, 0xFFFFFFFF, Ui.semibold, 1));
        if (!a.text.isEmpty()) col.addView(text(c, a.text, 18 * k, 0xD9FFFFFF, Ui.regular, 2));
        return row;
    }

    private static TextView text(Context c, String s, float px, int color, android.graphics.Typeface face, int lines) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        if (face != null) t.setTypeface(face);
        t.setMaxLines(lines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(0, 1.12f);
        t.setPadding(0, Math.round(px / 14), 0, Math.round(px / 14));
        return t;
    }
}
