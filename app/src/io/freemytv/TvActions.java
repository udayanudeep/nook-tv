package io.freemytv;

import android.app.ActivityManager;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What Control Center does and shows (HomeView's panel) and the sleep timer's end (ScreenTime): turning the TV off,
 * freeing memory, the network's name, a speed test, where the sound goes. Turning off, freeing memory and the Wi-Fi
 * name go through the TV's own ADB (an app can do none of them itself); the rest is plain Android. All of it off the
 * main thread, the answers back on it.
 */
final class TvActions {
    private TvActions() {}

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String SPEED_URL = "https://speed.cloudflare.com/__down?bytes=40000000";

    interface Answer {
        void got(String value);
    }

    private static String adb(Context c, String command) throws Exception {
        try (Adb adb = Adb.connect(c.getApplicationContext(), 5_000, null)) {
            return adb.shell(command);
        }
    }

    /** Standby, as the remote's power button does. */
    static void sleepNow(Context c) {
        Context app = c.getApplicationContext();
        IO.execute(() -> {
            try {
                adb(app, "input keyevent KEYCODE_SLEEP");
            } catch (Exception e) {
                AppLog.w("turn off: " + e.getMessage());
            }
        });
    }

    /** Free memory now, as "945 MB free". */
    static String freeMemory(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return "";
        ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(m);
        return Ui.trf("{n} MB free", "n", m.availMem / (1024 * 1024));
    }

    /** Closes the apps waiting in the background (not the one on screen, nor Nook's services); answers what's free. */
    static void freeUp(Context c, Answer done) {
        Context app = c.getApplicationContext();
        IO.execute(() -> {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
            if (am != null) am.getMemoryInfo(m);
            long before = m.availMem;
            try {
                adb(app, "am kill-all");
            } catch (Exception e) {
                AppLog.w("free up memory: " + e.getMessage());
            }
            SystemClock.sleep(800);
            if (am != null) am.getMemoryInfo(m);
            long freed = Math.max(0, m.availMem - before) / (1024 * 1024);
            String answer = Ui.trf("Freed {n} MB", "n", freed);
            MAIN.post(() -> done.got(answer));
        });
    }

    /** The network: the Wi-Fi's name, "Ethernet", or not connected. */
    static void network(Context c, Answer done) {
        Context app = c.getApplicationContext();
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (caps == null) {
            done.got(Ui.tr("Not connected"));
            return;
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            done.got(Ui.tr("Ethernet"));
            return;
        }
        done.got(Ui.tr("Wi-Fi"));
        IO.execute(() -> {   // its name: Android shows it to an app only with location access; ADB just says it
            try {
                Matcher m = Pattern.compile("connected to \"([^\"]+)\"").matcher(adb(app, "cmd wifi status"));
                if (m.find()) {
                    String name = m.group(1);
                    MAIN.post(() -> done.got(name));
                }
            } catch (Exception e) {
                AppLog.w("network name: " + e.getMessage());
            }
        });
    }

    /** Downloads for up to 8 s and answers the speed ("86 Mbps"). */
    static void speedTest(Answer done) {
        IO.execute(() -> {
            String answer;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(SPEED_URL).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                long bytes = 0, start = SystemClock.elapsedRealtime(), seconds = 8_000;
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[64 * 1024];
                    for (int n; (n = in.read(buf)) > 0; ) {
                        bytes += n;
                        if (SystemClock.elapsedRealtime() - start > seconds) break;
                    }
                } finally {
                    c.disconnect();
                }
                double secs = Math.max(0.2, (SystemClock.elapsedRealtime() - start) / 1000.0);
                long mbps = Math.round(bytes * 8 / secs / 1_000_000);
                answer = Ui.trf("{n} Mbps", "n", mbps);
            } catch (Exception e) {
                AppLog.w("speed test: " + e.getMessage());
                answer = Ui.tr("Couldn't test");
            }
            String a = answer;
            MAIN.post(() -> done.got(a));
        });
    }

    /** Where the sound goes: a Bluetooth speaker, headphones, a sound bar (HDMI ARC), or the TV's speakers. */
    static String soundOutput(Context c) {
        AudioManager audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return "";
        String bt = null;
        boolean arc = false, wired = false;
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) bt = String.valueOf(d.getProductName());
            else if (t == AudioDeviceInfo.TYPE_HDMI_ARC || (android.os.Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_HDMI_EARC)) arc = true;
            else if (t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_WIRED_HEADSET) wired = true;
        }
        if (bt != null) return bt.isEmpty() ? Ui.tr("Bluetooth") : bt;
        if (wired) return Ui.tr("Headphones");
        if (arc) return Ui.tr("Sound bar (HDMI ARC)");
        return Ui.tr("TV speakers");
    }
}
