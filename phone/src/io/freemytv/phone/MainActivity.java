package io.freemytv.phone;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pairing (from the TV's page: freemytv://pair), notification access, a test alert, and which apps' alerts go to
 * the TV. Built in code, like the TV app: no libraries.
 */
public class MainActivity extends Activity {
    private static final int INK = 0xFF1C1B1F, DIM = 0xFF49454F, PRIMARY = 0xFF6750A4, CARD = 0xFFF3EDF7, OK = 0xFF146C2E,
            WARN = 0xFFB3261E;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private float dp = 1;
    private TextView tvStatus, accessStatus, testStatus;
    private Button accessButton;
    private LinearLayout appList;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        setContentView(build());
        handlePair(getIntent());
        loadApps();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handlePair(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    /** freemytv://pair?u=http://192.168.1.20:8766&k=<key>, from the TV's pairing page. */
    private void handlePair(Intent intent) {
        Uri u = intent == null ? null : intent.getData();
        if (u == null || !"freemytv".equals(u.getScheme())) return;
        String address = u.getQueryParameter("u"), key = u.getQueryParameter("k");
        if (address == null || key == null || !address.startsWith("http://")) return;
        Tv.pair(this, address.replaceAll("/+$", ""), key);
        refresh();
    }

    private View build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFFFFBFE);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(24), px(40), px(24), px(40));
        scroll.addView(col);

        col.addView(text("Nook Phone", 28, INK, true));
        col.addView(text("Your TV's remote, Control Center and Cleanup, and this phone's alerts on the TV.", 16, DIM, false), gap(6));
        col.addView(button("Open the remote", v -> {
            if (Tv.paired(this)) startActivity(new Intent(this, RemoteActivity.class));
            else tvStatus.setText("Pair with the TV first (below).");
        }), gap(16));

        LinearLayout tv = card(col, "TV");
        tvStatus = text("", 16, INK, false);
        tv.addView(tvStatus, gap(8));

        LinearLayout access = card(col, "Notification access");
        accessStatus = text("", 16, INK, false);
        access.addView(accessStatus, gap(8));
        accessButton = button("Allow notification access", v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        access.addView(accessButton, gap(12));
        access.addView(text("If Android says “Restricted setting”: open App info below, tap ⋮ at the top, "
                + "choose “Allow restricted settings”, then allow access again.", 14, DIM, false), gap(12));
        access.addView(button("App info", v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())))), gap(8));

        LinearLayout test = card(col, "Try it");
        test.addView(button("Send a test alert to the TV", v -> sendTest()), gap(8));
        testStatus = text("", 15, DIM, false);
        test.addView(testStatus, gap(8));

        LinearLayout privacy = card(col, "Privacy");
        Switch showText = new Switch(this);
        showText.setText("Show the message text on the TV");
        showText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        showText.setChecked(Tv.showText(this));
        showText.setOnCheckedChangeListener((b, on) -> Tv.setShowText(this, on));
        privacy.addView(showText, gap(8));
        privacy.addView(text("Off: the TV shows only the app and who it's from.", 14, DIM, false), gap(4));

        LinearLayout apps = card(col, "Apps that show on the TV");
        apps.addView(text("Untick the ones you don't want on the TV.", 14, DIM, false), gap(4));
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        apps.addView(appList, gap(8));
        return scroll;
    }

    private void refresh() {
        if (Tv.paired(this)) {
            tvStatus.setText("Paired with " + Tv.address(this).replace("http://", "") + ". Checking…");
            tvStatus.setTextColor(INK);
            io.execute(() -> {
                boolean ok = ping();
                main.post(() -> {
                    tvStatus.setText(ok ? "Connected to your TV (" + Tv.address(this).replace("http://", "") + ")."
                            : "Paired, but the TV doesn't answer. Is it on, with this phone on the same Wi-Fi?");
                    tvStatus.setTextColor(ok ? OK : WARN);
                });
            });
        } else {
            tvStatus.setText("Not paired yet. On the TV: Nook → Settings → Phone Notifications, scan the "
                    + "code with this phone's camera and tap Connect.");
            tvStatus.setTextColor(WARN);
        }
        boolean on = accessOn();
        accessStatus.setText(on ? "Allowed: alerts go to the TV." : "Not allowed yet: the app can't see your alerts.");
        accessStatus.setTextColor(on ? OK : WARN);
        accessButton.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    private boolean accessOn() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(new ComponentName(this, Forwarder.class).flattenToString());
    }

    private boolean ping() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(Tv.address(this) + "/ping").openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestProperty("X-Key", Tv.key(this));
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void sendTest() {
        testStatus.setText("Sending…");
        io.execute(() -> {
            boolean ok;
            try {
                ok = Tv.send(this, new JSONObject().put("app", "Nook Phone").put("title", "Hello from your phone")
                        .put("text", "This is how your alerts will look on the TV.").put("call", false).put("icon", ""));
            } catch (Exception e) {
                ok = false;
            }
            boolean sent = ok;
            main.post(() -> testStatus.setText(sent ? "Sent: look at the TV." : "The TV didn't answer. Pair it first, "
                    + "and keep this phone on the same Wi-Fi."));
        });
    }

    /** Apps with a launcher icon, by name, ticked unless muted. */
    private void loadApps() {
        io.execute(() -> {
            PackageManager pm = getPackageManager();
            List<String[]> found = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo r : pm.queryIntentActivities(launcher, 0)) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                found.add(new String[] {pkg, r.loadLabel(pm).toString()});
            }
            Collections.sort(found, (a, b) -> a[1].compareToIgnoreCase(b[1]));
            main.post(() -> {
                Set<String> muted = Tv.muted(this);
                appList.removeAllViews();
                for (String[] app : found) {
                    CheckBox box = new CheckBox(this);
                    box.setText(app[1]);
                    box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                    box.setChecked(!muted.contains(app[0]));
                    box.setOnCheckedChangeListener((b, on) -> {
                        Set<String> now = Tv.muted(this);
                        if (on) now.remove(app[0]); else now.add(app[0]);
                        Tv.setMuted(this, now);
                    });
                    appList.addView(box);
                }
            });
        });
    }

    // MARK: - Views

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(px(18), px(16), px(18), px(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(px(20));
        c.setBackground(bg);
        c.addView(text(title, 18, PRIMARY, true));
        parent.addView(c, gap(20));
        return c;
    }

    private Button button(String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PRIMARY);
        bg.setCornerRadius(px(24));
        b.setBackground(bg);
        b.setGravity(Gravity.CENTER);
        b.setPadding(px(20), px(12), px(20), px(12));
        b.setOnClickListener(click);
        return b;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(0, 1.2f);
        return t;
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = px(top);
        return p;
    }
}
