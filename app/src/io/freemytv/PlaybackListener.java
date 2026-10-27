package io.freemytv;

import android.content.ComponentName;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.service.notification.NotificationListenerService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the play history: a notification listener only so the TV lets us see the apps' media sessions (what's
 * playing, its title, picture and position). It reads no notifications. Granted from ADB:
 * cmd notification allow_listener io.freemytv/io.freemytv.PlaybackListener
 */
public class PlaybackListener extends NotificationListenerService {
    /** Save the position again at most this often while something keeps playing. */
    private static final long SAVE_EVERY = 30_000;

    private HandlerThread thread;
    private Handler handler;
    private MediaSessionManager sessions;
    private final Map<MediaController.Callback, MediaController> watched = new HashMap<>();
    private final Map<String, Long> savedAt = new HashMap<>();
    private final Map<String, String> artSaved = new HashMap<>();

    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::watch;

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        NotifyServer.start(this);   // this service keeps the main process alive: your phone's alerts arrive here
        VolumeLimit.start(this);
        ScreenTime.start(this);
        if (HomeMode.FREE.equals(HomeMode.state(this).optString("mode"))) KeepWarm.bind(this);   // home screen ready on wake
        if (thread == null) {
            thread = new HandlerThread("history");
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        sessions = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        if (sessions == null) return;
        ComponentName me = new ComponentName(this, PlaybackListener.class);
        try {
            sessions.addOnActiveSessionsChangedListener(changed, me, handler);
            watch(sessions.getActiveSessions(me));
            AppLog.w("history: listening to media sessions");
        } catch (SecurityException e) {
            AppLog.w("history: no access to media sessions: " + e.getMessage());
        }
    }

    @Override public void onListenerDisconnected() {
        unwatchAll();
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        super.onListenerDisconnected();
    }

    @Override public void onDestroy() {
        unwatchAll();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    private void unwatchAll() {
        for (Map.Entry<MediaController.Callback, MediaController> e : watched.entrySet()) {
            e.getValue().unregisterCallback(e.getKey());
        }
        watched.clear();
    }

    private void watch(List<MediaController> controllers) {
        unwatchAll();
        if (controllers == null) return;
        for (final MediaController mc : new ArrayList<>(controllers)) {
            MediaController.Callback cb = new MediaController.Callback() {
                @Override public void onPlaybackStateChanged(PlaybackState state) { maybeRecord(mc, false); }
                @Override public void onMetadataChanged(MediaMetadata metadata) { maybeRecord(mc, true); }
            };
            mc.registerCallback(cb, handler);
            watched.put(cb, mc);
            maybeRecord(mc, true);
        }
    }

    private void maybeRecord(MediaController mc, boolean metadataChanged) {
        try {
            PlaybackState st = mc.getPlaybackState();
            MediaMetadata md = mc.getMetadata();
            if (md == null || st == null) return;
            int s = st.getState();
            boolean playing = s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING;
            boolean stopped = s == PlaybackState.STATE_PAUSED || s == PlaybackState.STATE_STOPPED;
            if (!playing && !stopped) return;
            History.Entry e = new History.Entry();
            e.pkg = mc.getPackageName();
            e.title = first(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
            if (e.title.isEmpty()) return;
            e.subtitle = first(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                    MediaMetadata.METADATA_KEY_ALBUM);
            if (e.subtitle.equals(e.title)) e.subtitle = "";
            e.duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            e.position = Math.max(0, st.getPosition());
            e.at = System.currentTimeMillis();
            String artUri = first(md, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                    MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            e.artUri = artUri.isEmpty() ? null : artUri;

            String key = e.key();
            Long last = savedAt.get(key);
            long now = android.os.SystemClock.elapsedRealtime();
            // While playing: once at the start, then every SAVE_EVERY; a pause or stop saves the position right away.
            if (playing && !metadataChanged && last != null && now - last < SAVE_EVERY) return;
            if (stopped && last == null) return;   // something paused that we never saw playing: not history
            savedAt.put(key, now);

            Bitmap art = null;
            if (!key.equals(artSaved.get(e.pkg))) {
                art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                if (art != null) artSaved.put(e.pkg, key);
            }
            History.record(this, e, art);
        } catch (Exception ex) {
            AppLog.w("history: " + ex.getMessage());
        }
    }

    private static String first(MediaMetadata md, String... keys) {
        for (String k : keys) {
            CharSequence s = md.getText(k);
            if (s == null) s = md.getString(k);
            if (s != null && s.toString().trim().length() > 0) return s.toString().trim();
        }
        return "";
    }
}
