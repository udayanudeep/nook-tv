package io.freemytv;

import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/**
 * Your Photos (Free My TV settings): a QR code that opens a page on the phone (PhotoServer) to send photos from its
 * gallery or Google Photos, with no computer or Google setup; the count on the TV; and removing them all.
 */
public class PhotosActivity extends QrActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private PhotoServer server;
    private TextView count, small, received, remove;
    private boolean confirmRemove;

    @Override protected void onResume() {
        super.onResume();
        server = new PhotoServer(getExternalFilesDir("photos"), n -> {
            received.setText(Ui.trf("Received: {n}", "n", n));
            showCount();
        });
        String address = null;
        try {
            address = server.start();
        } catch (Exception e) {
            AppLog.w("your photos: couldn't start: " + e.getMessage());
        }
        LinearLayout col = frame(address, Ui.tr("Your Photos"), Ui.tr("Your photos on the home screen and screensaver"));
        if (address == null) {
            col.addView(text(Ui.tr("Connect the TV to Wi-Fi to add photos from your phone."), 32, PRIMARY, Ui.medium), gap(56));
        } else {
            col.addView(text(Ui.tr("1. Scan this code with your phone's camera (on the same Wi-Fi)."), 32, ON_SURFACE, Ui.regular), gap(56));
            col.addView(text(Ui.tr("2. Choose photos from your gallery or Google Photos."), 32, ON_SURFACE, Ui.regular), gap(20));
            col.addView(text(Ui.tr("3. They appear on the TV in a few seconds."), 32, ON_SURFACE, Ui.regular), gap(20));
            col.addView(text(address, 22, ON_SURFACE_VARIANT, Ui.regular), gap(28));
        }
        count = text("", 34, PRIMARY, Ui.semibold);
        col.addView(count, gap(56));
        small = text("", 24, ON_SURFACE_VARIANT, Ui.regular);
        col.addView(small, gap(8));
        received = text("", 26, ON_SURFACE_VARIANT, Ui.regular);
        col.addView(received, gap(8));
        remove = pill(Ui.tr("Remove all photos"), v -> removeAll());
        col.addView(remove, pillGap(44));
        remove.requestFocus();
        showCount();
    }

    @Override protected void onPause() {
        super.onPause();
        if (server != null) server.stop();   // only while this screen is open
        server = null;
        main.removeCallbacksAndMessages(null);
        confirmRemove = false;
    }

    private void showCount() {
        count.setText(Ui.trf("Photos on the TV: {n}", "n", Photos.list(this).size()));
        int tiny = Photos.smallCount(this);
        small.setText(tiny == 0 ? "" : Ui.trf("{n} too small to look sharp: not used as wallpaper", "n", tiny));
    }

    /** OK twice: the first press asks, the second removes every photo (from the phone and from the computer). */
    private void removeAll() {
        if (!confirmRemove) {
            confirmRemove = true;
            remove.setText(Ui.tr("Press OK again to remove all photos"));
            main.postDelayed(() -> {
                confirmRemove = false;
                remove.setText(Ui.tr("Remove all photos"));
            }, 5000);
            return;
        }
        confirmRemove = false;
        main.removeCallbacksAndMessages(null);
        for (File f : Photos.list(this)) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        remove.setText(Ui.tr("Remove all photos"));
        received.setText("");
        showCount();
    }
}
