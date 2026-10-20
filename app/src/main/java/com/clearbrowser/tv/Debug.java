package com.clearbrowser.tv;

import android.util.Log;

/**
 * Test logging (page timing, video stats, per-request blocks, player stats). Off normally, so the
 * TV's CPU isn't spent on it; switch on for a test session with
 *     adb shell setprop log.tag.Clear DEBUG      (then restart the app)
 */
final class Debug {
    static final boolean ON = Log.isLoggable("Clear", Log.DEBUG);

    private Debug() { }
}
