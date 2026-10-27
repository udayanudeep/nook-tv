package io.freemytv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Holding Volume Up or Down keeps changing the volume. With RemoteKeyService filtering keys, Android's TV policy takes
 * the volume keys in the accessibility layer (KeyboardInterceptor) and they never repeat: a press is one step however
 * long it's held. The service never sees them, and the volume itself can't tell a hold from a tap (the key coming up
 * changes nothing; the volume broadcasts come up to half a second late). Sony's key daemon ("asd") logs every key going
 * down and up, so with READ_LOGS (granted over ADB: pm grant io.freemytv android.permission.READ_LOGS) the log says
 * exactly when Volume is down: after DELAY we step it the same way every EVERY until the key comes up. Without the
 * permission, or on a TV whose log says nothing, a press stays one step as before. Never longer than MAX_MS (a lost
 * "up" line can't run away), never past the limits, and any other key stops it.
 */
final class VolumeHold {
    private VolumeHold() {}

    private static final long DELAY = 400, EVERY = 110, MAX_MS = 12000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static AudioManager audio;
    private static volatile Process logcat;
    private static volatile boolean running;
    private static Runnable step;
    private static long startedAt;

    static void start(Context c) {
        if (running) return;
        if (c.checkSelfPermission("android.permission.READ_LOGS") != PackageManager.PERMISSION_GRANTED) {
            AppLog.w("volume hold: no READ_LOGS, a press stays one step");
            return;
        }
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return;
        running = true;
        Thread reader = new Thread(new Runnable() {
            @Override public void run() {
                read();
            }
        }, "VolumeHold");
        reader.setDaemon(true);
        reader.start();
    }

    static void stop(Context c) {
        running = false;
        Process p = logcat;
        if (p != null) p.destroy();
        MAIN.post(new Runnable() {
            @Override public void run() {
                cancel();
            }
        });
    }

    /** Another key on the remote: a hold of Volume can't be going on. */
    static void otherKey() {
        cancel();
    }

    /** Follows the key daemon's lines from now on; started again if logcat ends (logd restarting, say). */
    private static void read() {
        while (running) {
            try {
                // -T 1: the last line so far, then what comes from now on (that old line is skipped below).
                Process p = new ProcessBuilder("logcat", "-v", "raw", "-T", "1", "-s", "asd:I").redirectErrorStream(true).start();
                logcat = p;
                BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()), 1024);
                boolean first = true;
                String line;
                while (running && (line = in.readLine()) != null) {
                    if (first) {
                        first = false;
                        continue;
                    }
                    if (!line.startsWith("KeyEvent {")) continue;
                    final int sign = line.contains("KEYCODE_VOLUME_UP") ? 1 : line.contains("KEYCODE_VOLUME_DOWN") ? -1 : 0;
                    final boolean down = line.contains("action=ACTION_DOWN");
                    if (sign == 0 && !down) continue;
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            if (sign != 0 && down) begin(sign);
                            else cancel();   // the key came up, or another went down
                        }
                    });
                }
                p.destroy();
            } catch (Exception e) {
                AppLog.w("volume hold: " + e.getMessage());
            }
            if (running) SystemClock.sleep(5000);
        }
    }

    private static void begin(int sign) {
        cancel();
        final int direction = sign > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
        startedAt = SystemClock.uptimeMillis();
        step = new Runnable() {
            @Override public void run() {
                int v = audio.getStreamVolume(AudioManager.STREAM_MUSIC), max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                boolean atLimit = direction == AudioManager.ADJUST_RAISE ? v >= Math.min(max, VolumeLimit.cap(audio)) : v <= 0;
                if (SystemClock.uptimeMillis() - startedAt > MAX_MS || atLimit) {
                    cancel();
                    return;
                }
                audio.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
                MAIN.postDelayed(this, EVERY);
            }
        };
        MAIN.postDelayed(step, DELAY);   // a tap is over before this: the TV's own one step is all it does
    }

    private static void cancel() {
        if (step != null) MAIN.removeCallbacks(step);
        step = null;
    }
}
