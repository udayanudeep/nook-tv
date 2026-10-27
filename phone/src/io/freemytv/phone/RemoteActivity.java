package io.freemytv.phone;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * The TV's remote, in the app: the TV's own phone page (Remote, Control, Cleanup, Days; NotifyServer's /remote with
 * the pairing key) in a WebView, plus what only an app can do: the phone's volume buttons change the TV's volume, and
 * "Paste from phone" reads this phone's clipboard (the page asks through the Nook bridge). Opens first once paired;
 * the gear at the top right goes to the setup (pairing, notifications).
 */
public class RemoteActivity extends Activity {
    private WebView web;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (!Tv.paired(this)) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        getWindow().setStatusBarColor(0xFF141218);
        getWindow().setNavigationBarColor(0xFF141218);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF141218);
        web = new WebView(this);
        web.setBackgroundColor(0xFF141218);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView v, int code, String description, String url) {
                v.loadData("<body style='background:#141218;color:#E6E0E9;font-family:sans-serif;padding:40px 24px;"
                        + "line-height:1.5'><h2>Can't reach the TV</h2><p>Is the TV on, with this phone on the same Wi-Fi? "
                        + "Pull down or reopen the app to try again.</p></body>", "text/html", "utf-8");
            }
        });
        web.addJavascriptInterface(new Bridge(), "Nook");
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        TextView gear = new TextView(this);
        gear.setText("⚙");
        gear.setTextSize(22);
        gear.setTextColor(0xB3FFFFFF);
        gear.setGravity(Gravity.CENTER);
        gear.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        int s = Math.round(48 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(s, s, Gravity.TOP | Gravity.END);
        root.addView(gear, gp);
        setContentView(root);
        load();
    }

    private void load() {
        web.loadUrl(Tv.address(this) + "/remote?k=" + Tv.key(this));
    }

    @Override protected void onResume() {
        super.onResume();
        if (web != null && (web.getUrl() == null || !web.getUrl().startsWith("http"))) load();
    }

    /** The phone's volume buttons are the TV's while the remote is open (held: they repeat). */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (web != null && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                web.evaluateJavascript("window.nookVolume&&nookVolume('" + (code == KeyEvent.KEYCODE_VOLUME_UP ? "up" : "down") + "')", null);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** What the page can ask the app. */
    final class Bridge {
        @JavascriptInterface public String clipboard() {
            final String[] text = {""};
            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            runOnUiThread(() -> {   // the clipboard is read on the main thread, with the app in front
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                ClipData clip = cm == null ? null : cm.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence t = clip.getItemAt(0).coerceToText(RemoteActivity.this);
                    if (t != null) text[0] = t.toString();
                }
                done.countDown();
            });
            try {
                done.await(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return text[0];
        }
    }

    @Override protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
