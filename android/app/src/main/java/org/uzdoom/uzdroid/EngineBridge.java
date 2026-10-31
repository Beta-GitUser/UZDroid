/*
** EngineBridge.java
**
** The Java side of a small channel into the engine's console: a touch button
** can run a console command instead of pretending to be a key press. The
** command is queued through an SDL event by the native side, so it runs on the
** game thread like any bound key would.
**
** SPDX-License-Identifier: GPL-3.0-or-later
*/

package org.uzdoom.uzdroid;

import android.util.Log;

public final class EngineBridge {

    private static final String TAG = "UZDroid";

    private EngineBridge() {
    }

    /** Implemented in libmain.so (i_input.cpp). */
    private static native void exec(String command);

    /**
     * Runs a console command. Safe to call before the engine is up: the text
     * simply has nowhere to go yet.
     */
    public static void run(String command) {
        if (command == null || command.isEmpty()) {
            return;
        }

        try {
            exec(command);
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "command dropped, engine not loaded: " + command);
        }
    }
}
