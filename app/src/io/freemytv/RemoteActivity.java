package io.freemytv;

import android.widget.LinearLayout;

/**
 * Phone Remote (Free My TV settings, and Extras > Special days): the QR code for PhoneRemote's page on NotifyServer.
 * The phone's browser is the remote, nothing to install; the same page adds the special days and reminders.
 */
public class RemoteActivity extends QrActivity {
    @Override protected void onResume() {
        super.onResume();
        String address = NotifyServer.remoteAddress(this);
        LinearLayout col = frame(address, Ui.tr("Phone Remote"), Ui.tr("Control the TV from your phone"));
        if (address == null) {
            col.addView(text(Ui.tr("Connect the TV to Wi-Fi to use your phone as a remote."), 32, PRIMARY, Ui.medium), gap(56));
            return;
        }
        col.addView(text(Ui.tr("1. Scan this code with your phone's camera (on the same Wi-Fi)."), 32, ON_SURFACE, Ui.regular), gap(56));
        col.addView(text(Ui.tr("2. The remote opens in the browser: arrows, volume, apps and typing."), 32, ON_SURFACE, Ui.regular), gap(20));
        col.addView(text(Ui.tr("3. Add birthdays, anniversaries and reminders in its Special days tab."), 32, ON_SURFACE, Ui.regular), gap(20));
        col.addView(text(Ui.tr("Tip: add the page to your phone's home screen to open it in one tap."), 26, ON_SURFACE_VARIANT, Ui.regular), gap(28));
        col.addView(text(address, 22, ON_SURFACE_VARIANT, Ui.regular), gap(20));
        int n = Celebrate.days(this).size();
        if (n > 0) {
            col.addView(text(Ui.plural("plural|one:{n} special day saved|other:{n} special days saved", n), 26, PRIMARY, Ui.medium), gap(28));
        }
    }
}
