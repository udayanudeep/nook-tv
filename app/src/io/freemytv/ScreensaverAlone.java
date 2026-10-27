package io.freemytv;

/**
 * The screensaver on Android 11 and 12. There the system's DreamActivity runs in the app's default process and only
 * works with the DreamService in that same process, which can't also hold a home activity (#10). Android 12 too: a
 * BRAVIA BF1 (MediaTek) crashed with Screensaver in :home ("BinderProxy cannot be cast to DreamActivityCallbacks").
 * So they get this copy, in the main process; every other version keeps Screensaver in :home
 * (app/res/values-v30/bools.xml, values-v33).
 */
public class ScreensaverAlone extends Screensaver {
}
