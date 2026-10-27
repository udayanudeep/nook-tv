package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * The remote's app buttons on TCL TVs. The stock home screen opens the apps for Netflix, YouTube and Prime (and TCL
 * Channel); with it off, the keys still arrive, so we open the app the person picked (Settings > Remote Buttons).
 * Disney and Globoplay are opened by the system itself, so they are not listed. The YouTube button (BUTTON_3) never
 * reaches an app's window; only the accessibility service's key filter (RemoteKeyService) sees it.
 * On Vestel TVs and Formovie (Xiaomi) projectors only the Netflix button is ours: the system opens Netflix itself (a
 * global key, after the filter).
 * On KTC TVs (NextStar) only the Tabii button is ours, a global key too: the TV's hot-key receiver opens Tabii with it.
 * On Sony TVs, the app buttons (and the Google Play button of older remotes) are global keys for Sony's own receiver;
 * the filter sees them first, the same way.
 */
final class RemoteKeys {
    private RemoteKeys() {}

    /** Some TCL remotes (BT_RC833) send TCL's own YouTube key instead of BUTTON_3: the same button. */
    private static final int KEY_YOUTUBE_TCL = 4065;

    /**
     * The key codes are the brand's own: on other brands they can be other buttons, so nothing here runs there.
     * Vestel builds TVs sold under other brands (F&U...), so BRAND varies: check the maker too.
     */
    static final boolean TCL = is("TCL"), VESTEL = is("Vestel"), KTC = is("KTC"), SONY = is("Sony"), FORMOVIE = is("Formovie");
    /** Brands whose Netflix button is STEM_1, a global key (MediaTek): only that button is ours. */
    static final boolean STEM_NETFLIX = VESTEL || FORMOVIE;

    static boolean supported() {
        return TCL || STEM_NETFLIX || KTC || SONY;
    }

    private static boolean is(String maker) {
        return maker.equalsIgnoreCase(Build.MANUFACTURER) || maker.equalsIgnoreCase(Build.BRAND);
    }

    /** Opens the app for this key on its first press. True if the key is ours and in use (it is then consumed). */
    static boolean onKey(Context c, KeyEvent e) {
        if (!supported()) return false;
        // BUTTON_3 is also a game controller's button: keys from a gamepad or a joystick are left alone.
        int source = e.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) VolumeHold.otherKey();   // (volume keys never get here: VolumeHold)
        if (e.getKeyCode() == KeyEvent.KEYCODE_SETTINGS || e.getKeyCode() == KeyEvent.KEYCODE_NOTIFICATION) {
            // The remote's gear (a BRAVIA BF1's sends NOTIFICATION, scan 479; others SETTINGS): the stock home screen
            // opened the TV's settings for it, and with that off nothing does.
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                AppLog.w("remote key " + KeyEvent.keyCodeToString(e.getKeyCode()) + " -> the TV's settings");
                try {
                    c.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ex) {
                    AppLog.w("remote key SETTINGS: " + ex.getMessage());
                }
            }
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && !common(e.getKeyCode())) {
            // A key nothing here knows: said once in the log, to find what a button sends.
            AppLog.w("remote key " + KeyEvent.keyCodeToString(e.getKeyCode()) + " scan " + e.getScanCode());
        }
        int keyCode = e.getKeyCode() == KEY_YOUTUBE_TCL ? Prefs.KEY_YOUTUBE : e.getKeyCode();
        // Sony sends BUTTON_9 for five app buttons (BUTTON_4 for Netflix, BUTTON_8 for Google Play): only the scan code
        // tells them apart.
        if (SONY && (keyCode == KeyEvent.KEYCODE_BUTTON_4 || keyCode == KeyEvent.KEYCODE_BUTTON_8
                || keyCode == KeyEvent.KEYCODE_BUTTON_9)) {
            keyCode = sonyButton(e.getScanCode());
        }
        boolean ours = false;
        for (int key : Prefs.KEYS) ours |= key == keyCode;
        if (!ours) return false;
        String pkg = Prefs.keyApp(keyCode);
        if (pkg.isEmpty()) return false;   // "Nothing": the key goes on as if we weren't here
        Intent intent = launchIntent(c.getPackageManager(), pkg);
        if (intent == null) return false;  // not installed: the TV does what it would without us
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            AppLog.w("remote key " + e.getKeyCode() + "/" + e.getScanCode() + " -> " + pkg);
            c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        return true;
    }

    /**
     * The Sony button for a scan code. India's remotes (BRAVIA BF1, "SONY TV VRC 001", 3 rows of 2: BRAVIA CORE,
     * Netflix, YouTube, Prime Video, Crunchyroll, Sony LIV) send other scan codes for the same buttons.
     */
    private static int sonyButton(int scan) {
        switch (scan) {
            case 475: return Prefs.KEY_SONY_CORE;
            case 187: return Prefs.KEY_SONY_NETFLIX;
            case 190: return Prefs.KEY_SONY_YOUTUBE;
            case 189: return Prefs.KEY_SONY_PRIME;
            case 477: return Prefs.KEY_SONY_CRUNCHYROLL;
            default: return Prefs.SONY_KEY + scan;   // 478 is KEY_SONY_LIV
        }
    }

    /** Keys every remote has (and the brand's app buttons, which are ours below): not worth a log line. */
    private static boolean common(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_HOME: case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_BUTTON_4: case KeyEvent.KEYCODE_BUTTON_8: case KeyEvent.KEYCODE_BUTTON_9:
                return true;
            default:
                return false;
        }
    }

    /** The app's TV launcher, or its plain one (kept here, not in HomeApps, so the key process stays small). */
    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }
}
