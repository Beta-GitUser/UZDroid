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

        // SDL has just created its layout, so the button overlay can go on top
        // of the surface. It is empty until touch-buttons.txt says otherwise.
        TouchButtons.install(this);

        // Android shows its own pointer for a connected mouse; the engine only
        // draws a cursor when there is none.
        MousePresence.start(this);
    }

    /**
     * SDL asks for an orientation once it has a window. With no orientation hint
     * it asks for SCREEN_ORIENTATION_FULL_USER, which follows the system
     * rotation lock and beats android:screenOrientation in the manifest - so the
     * game could come up in portrait. Clamping it here makes landscape
     * unconditional whatever SDL decides.
     */
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        super.setOrientationBis(Math.max(w, h), Math.min(w, h), false,
                "LandscapeLeft LandscapeRight");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-read the file every time we come back, so an edit applies without
        // reinstalling anything.
        TouchButtons.reload(this);
        // The engine has certainly started by now; make sure it knows.
        MousePresence.refreshNow();
    }

    /**
     * Called from the engine's input code as the movement stick moves, so the
     * overlay can put the knob under the finger. Runs on the game thread.
     */
    public void onTouchStick(float originX, float originY, float dx, float dy, boolean active) {
        TouchButtons.onStick(originX, originY, dx, dy, active);
    }

    /** The engine's input is up; send it the button table again. */
    public void onTouchReady() {
        TouchButtons.republish();
    }

    /** Layout editing was turned on or off; the overlay shows it and saves. */
    public void onTouchEditMode(boolean editing) {
        TouchButtons.setEditing(editing);
    }

    /** A palette chip was tapped and the engine made a button for it. */
    public void onTouchButtonAdded(boolean ui, int index, String input, String label,
                                   float x, float y, float w, float h) {
        TouchButtons.onButtonAdded(ui, index, input, label, x, y, w, h);
    }

    /** A button was dropped on the palette row, which deletes it. */
    public void onTouchButtonRemoved(boolean ui, int index) {
        TouchButtons.onButtonRemoved(ui, index);
    }

    /** A button was dragged or resized while editing. */
    public void onTouchButtonMoved(int index, boolean ui, float x, float y, float w, float h) {
        TouchButtons.moveButton(index, ui, x, y, w, h);
    }

    /** Which button the engine sees held, so the overlay can draw it pressed. */
    public void onTouchButtonPressed(boolean ui, int index, boolean pressed) {
        TouchButtons.onButtonPressed(ui, index, pressed);
    }

    /**
     * Called from the engine's input code when the menu (or the console)
     * takes over the pointer, and again when gameplay gets it back. Runs on
     * the game thread.
     */
    public void onTouchContext(boolean ui) {
        TouchButtons.setUiContext(ui);
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
        try {
            EnginePaths.ensureTouchData(this, root);
        } catch (java.io.IOException e) {
            Log.w(TAG, "could not copy the Touch folder", e);
        }
        rotateLog(new File(root, "logs/uzdoom.log"));
        EnginePaths.applyEnvironment(root);

        String[] args = getIntent().getStringArrayExtra(EXTRA_ARGS);
        if (args == null) {
            args = new String[0];
        }

        CrashLog.append(this, "engine starting in " + root.getAbsolutePath());
        CrashLog.append(this, "arguments: " + java.util.Arrays.toString(args));

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
