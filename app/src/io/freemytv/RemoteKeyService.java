package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 */
public class RemoteKeyService extends AccessibilityService {
    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        VolumeHold.start(this);   // holding Volume keeps changing it (the keys don't repeat with this service on)
    }

    @Override public void onDestroy() {
        VolumeHold.stop(this);
        super.onDestroy();
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}
}
