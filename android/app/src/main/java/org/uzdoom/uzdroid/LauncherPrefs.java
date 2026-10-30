/*
** LauncherPrefs.java
**
** Everything the launcher remembers between runs.
**
** SPDX-License-Identifier: GPL-3.0-or-later
*/

package org.uzdoom.uzdroid;

import android.content.Context;
import android.content.SharedPreferences;

public final class LauncherPrefs {

    private static final String FILE = "launcher";

    public static final int SCALE_NATIVE = 100;

    /** Internal render resolution as a percentage of the screen (25..200). */
    public int scalePercent = SCALE_NATIVE;

    /** When true, scalePercent is ignored and customWidth/Height are used. */
    public boolean customResolution = false;

    public int customWidth = 1280;
    public int customHeight = 720;

    /** Extra command line arguments, exactly as the user typed them. */
    public String extraArgs = "";

    public String lastWad = "";

    /** SDL joystick/gamepad handling (passes +use_joystick 0 when false). */
    public boolean joystickEnabled = true;

    /** Touch is delivered to the engine as mouse motion and buttons. */
    public boolean touchAsMouse = true;

    /** Sound on. Off passes -nosound, which is also the way to rule libopenal out. */
    public boolean soundEnabled = true;

    public static LauncherPrefs load(Context context) {
        SharedPreferences sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        LauncherPrefs prefs = new LauncherPrefs();
        prefs.scalePercent = sp.getInt("scalePercent", SCALE_NATIVE);
        prefs.customResolution = sp.getBoolean("customResolution", false);
        prefs.customWidth = sp.getInt("customWidth", 1280);
        prefs.customHeight = sp.getInt("customHeight", 720);
        prefs.extraArgs = sp.getString("extraArgs", "");
        prefs.lastWad = sp.getString("lastWad", "");
        prefs.joystickEnabled = sp.getBoolean("joystickEnabled", true);
        prefs.touchAsMouse = sp.getBoolean("touchAsMouse", true);
        prefs.soundEnabled = sp.getBoolean("soundEnabled", true);
        return prefs;
    }

    public void save(Context context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putInt("scalePercent", scalePercent)
                .putBoolean("customResolution", customResolution)
                .putInt("customWidth", customWidth)
                .putInt("customHeight", customHeight)
                .putString("extraArgs", extraArgs)
                .putString("lastWad", lastWad)
                .putBoolean("joystickEnabled", joystickEnabled)
                .putBoolean("touchAsMouse", touchAsMouse)
                .putBoolean("soundEnabled", soundEnabled)
                .apply();
    }
}
