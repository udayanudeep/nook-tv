package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;

/**
 * Volume limit (Settings > Extras): the TV's volume never goes past Prefs.volumeLimitNow. Turned up past it (the remote,
 * a phone casting, an app), it goes straight back, with the TV's own volume bar showing where it stopped. Checked on
 * every change and once a minute (ScreenTime's tick: the night limit starts at 22:00 without anyone pressing a key).
 * Runs in the main process, which PlaybackListener keeps alive. VolumeHold stops stepping at the limit too.
 */
final class VolumeLimit {
    private VolumeLimit() {}

    private static final String CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static BroadcastReceiver receiver;

    static synchronized void start(Context c) {
        if (receiver != null) return;
        final Context app = c.getApplicationContext();
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent i) {
                if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) == AudioManager.STREAM_MUSIC) check(app);
            }
        };
        app.registerReceiver(receiver, new IntentFilter(CHANGED));
        check(app);
    }

    /** Brings the volume down to the limit if it's above it. */
    static void check(Context c) {
        int limit = Prefs.volumeLimitNow();
        if (limit <= 0) return;
        AudioManager audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return;
        // The limit is out of 100 (the TV's own scale); a TV with another scale gets the same share of it.
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cap = Math.max(1, Math.round(limit * max / 100f));
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) > cap) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, cap, AudioManager.FLAG_SHOW_UI);
        }
    }

    /** The highest volume allowed now on this TV's scale (Integer.MAX_VALUE without a limit). */
    static int cap(AudioManager audio) {
        int limit = Prefs.volumeLimitNow();
        if (limit <= 0) return Integer.MAX_VALUE;
        return Math.max(1, Math.round(limit * audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 100f));
    }
}
