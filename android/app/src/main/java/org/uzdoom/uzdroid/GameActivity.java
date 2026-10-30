/*
** GameActivity.java
**
** The SDL activity that actually runs the engine. SDLActivity loads libSDL2.so
** and libmain.so and then calls SDL_main() on its own thread; everything we do
** here is (a) hand it a command line and (b) make sure the environment points
** at the data folder before the engine starts.
**
** SPDX-License-Identifier: GPL-3.0-or-later
*/

package org.uzdoom.uzdroid;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import org.libsdl.app.SDLActivity;

import java.io.File;

public class GameActivity extends SDLActivity {

    private static final String TAG = "UZDroid";

    /** Command line arguments, passed as String[]. */
    public static final String EXTRA_ARGS = "org.uzdoom.uzdroid.EXTRA_ARGS";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /**
     * SDL loads libraries in this order. The last one is the one that contains
     * the entry point, so "main" has to stay last - that is what makes SDL look
     * for libmain.so and SDL_main().
     */
    @Override
    protected String[] getLibraries() {
        return new String[]{"SDL2", "main"};
    }

    /**
     * Called by SDL right before the native thread starts, which makes this the
     * last safe place to set up the process environment.
     */
    @Override
    protected String[] getArguments() {
        File root = EnginePaths.root(this);
        EnginePaths.ensureLayout(root);
        rotateLog(new File(root, "logs/uzdoom.log"));
        EnginePaths.applyEnvironment(root);

        String[] args = getIntent().getStringArrayExtra(EXTRA_ARGS);
        if (args == null) {
            args = new String[0];
        }

        Log.i(TAG, "Engine root: " + root.getAbsolutePath());
        Log.i(TAG, "Engine arguments: " + java.util.Arrays.toString(args));

        return args;
    }

    /**
     * Keeps the log from growing forever: once it passes a few megabytes the
     * current file becomes uzdoom.previous.log.
     */
    private static void rotateLog(File log) {
        final long maxSize = 4L * 1024L * 1024L;
        try {
            if (log.isFile() && log.length() > maxSize) {
                File previous = new File(log.getParentFile(), "uzdoom.previous.log");
                if (previous.exists() && !previous.delete()) {
                    return;
                }
                if (!log.renameTo(previous)) {
                    Log.w(TAG, "Could not rotate " + log);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Log rotation failed", e);
        }
    }

    /** Convenience used by the launcher. */
    public static Intent createIntent(android.content.Context context, String[] args) {
        Intent intent = new Intent(context, GameActivity.class);
        intent.putExtra(EXTRA_ARGS, args);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }
}
