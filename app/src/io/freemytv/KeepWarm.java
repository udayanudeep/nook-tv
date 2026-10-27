package io.freemytv;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;

/**
 * Faster start after the TV wakes up. While the TV slept, Android could end the home screen's process (:home) to make
 * room; waking up then meant starting it from nothing: the fonts, the data, and decoding and sharpening your photo
 * (the home screen showed the plain sky first and faded the photo in a second or two later). The main process (kept
 * alive by PlaybackListener) binds this small service in :home, so :home is kept like the main process is: the home
 * screen is still there, with its wallpaper ready, when the TV wakes. The screen coming on also gets the wallpaper of
 * that moment (a new hour's photo) decoding at once, before the home screen asks for it.
 */
public class KeepWarm extends Service {
    private final IBinder binder = new Binder();
    private BroadcastReceiver screenOn;

    @Override public void onCreate() {
        super.onCreate();
        Ui.init(this);
        warm();
        screenOn = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                warm();
                Briefing.screenOn(c);   // the morning's first: the briefing card
            }
        };
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    private void warm() {
        try {
            Recs.get(this).photoBackdrop();   // starts building this moment's photo if it isn't ready
        } catch (RuntimeException e) {
            AppLog.w("keep warm: " + e.getMessage());
        }
    }

    @Override public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override public void onDestroy() {
        if (screenOn != null) unregisterReceiver(screenOn);
        super.onDestroy();
    }

    private static ServiceConnection connection;

    /** From the main process: keeps :home alive as long as it is (once; Android rebinds it if :home ever dies). */
    static synchronized void bind(Context c) {
        if (connection != null) return;
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {}
            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        Context app = c.getApplicationContext();
        if (!app.bindService(new Intent(app, KeepWarm.class), connection, Context.BIND_AUTO_CREATE)) {
            connection = null;
            AppLog.w("keep warm: couldn't bind");
        }
    }
}
