package io.freemytv;

import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Phone Notifications (Free My TV settings): the QR code for NotifyServer's pairing page (the phone downloads Free My
 * TV Phone there and connects), turning the alerts on or off, and a test pop-up.
 */
public class PhoneActivity extends QrActivity {
    private TextView toggle;

    @Override protected void onResume() {
        super.onResume();
        String address = NotifyServer.pairAddress(this);
        LinearLayout col = frame(address, Ui.tr("Phone Notifications"), Ui.tr("Your phone's alerts in the corner of the TV"));
        if (address == null) {
            col.addView(text(Ui.tr("Connect the TV to Wi-Fi to pair your phone."), 32, PRIMARY, Ui.medium), gap(56));
        } else {
            col.addView(text(Ui.tr("1. Scan this code with your phone's camera (on the same Wi-Fi)."), 32, ON_SURFACE, Ui.regular), gap(56));
            col.addView(text(Ui.tr("2. Download and install the Free My TV Phone app."), 32, ON_SURFACE, Ui.regular), gap(20));
            col.addView(text(Ui.tr("3. Tap Connect, then allow notification access in the app."), 32, ON_SURFACE, Ui.regular), gap(20));
            col.addView(text(address, 22, ON_SURFACE_VARIANT, Ui.regular), gap(28));
        }
        toggle = pill("", v -> {
            Prefs.setPhoneAlerts(!Prefs.phoneAlerts());
            showToggle();
        });
        col.addView(toggle, pillGap(56));
        col.addView(pill(Ui.tr("Send a test pop-up"), v -> {
            AlertOverlay.Alert a = new AlertOverlay.Alert();
            a.app = Ui.BRAND;
            a.title = Ui.tr("Hello from Free My TV");
            a.text = Ui.tr("This is how your phone's alerts look on the TV.");
            AlertOverlay.show(this, a);
        }), pillGap(20));
        showToggle();
        toggle.requestFocus();
    }

    private void showToggle() {
        toggle.setText(Ui.trf("Show phone notifications: {state}", "state", Prefs.phoneAlerts() ? Ui.tr("On") : Ui.tr("Off")));
    }
}
