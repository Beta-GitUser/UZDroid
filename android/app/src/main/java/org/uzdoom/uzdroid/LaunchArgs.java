/*
** LaunchArgs.java
**
** Turns the launcher settings into the command line that SDL hands to
** SDL_main() as argv.
**
** SPDX-License-Identifier: GPL-3.0-or-later
*/

package org.uzdoom.uzdroid;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class LaunchArgs {

    private LaunchArgs() {
    }

    /**
     * @param screenWidth  current display width in pixels
     * @param screenHeight current display height in pixels
     */
    public static String[] build(LauncherPrefs prefs, File iwad, int screenWidth, int screenHeight) {
        List<String> args = new ArrayList<>();

        if (iwad != null) {
            args.add("-iwad");
            args.add(iwad.getAbsolutePath());
        }

        addResolution(args, prefs, screenWidth, screenHeight);

        if (!prefs.touchControls) {
            // Hands the pointer back to SDL's own touch-to-mouse translation.
            addCvar(args, "touch_enable", "0");
        }

        if (!prefs.soundEnabled) {
            args.add("-nosound");
        }

        if (!prefs.joystickEnabled) {
            // Applies before the joystick subsystem is set up, because the
            // engine runs command line cvars long before video init.
            addCvar(args, "use_joystick", "0");
        }

        args.addAll(tokenize(prefs.extraArgs));

        return args.toArray(new String[0]);
    }

    private static void addResolution(List<String> args, LauncherPrefs prefs, int screenWidth, int screenHeight) {
        if (prefs.customResolution) {
            if (prefs.customWidth > 0 && prefs.customHeight > 0) {
                setCustomResolution(args, prefs.customWidth, prefs.customHeight);
            }
            return;
        }

        if (prefs.scalePercent == LauncherPrefs.SCALE_NATIVE) {
            // Native: let the engine render at the surface resolution.
            return;
        }

        if (screenWidth <= 0 || screenHeight <= 0) {
            return;
        }

        double scale = prefs.scalePercent / 100.0;
        int width = (int) Math.round(screenWidth * scale);
        int height = (int) Math.round(screenHeight * scale);
        setCustomResolution(args, width, height);
    }

    private static void setCustomResolution(List<String> args, int width, int height) {
        width = clamp(width);
        height = clamp(height);

        // vid_scalemode 5 is the "Custom" entry of the engine's scale table.
        addCvar(args, "vid_scalemode", "5");
        addCvar(args, "vid_scale_customwidth", Integer.toString(width));
        addCvar(args, "vid_scale_customheight", Integer.toString(height));
        addCvar(args, "vid_scale_custompixelaspect", "1.0");
    }

    private static int clamp(int value) {
        // Even numbers keep the scaling filters happy; the engine's own minimum
        // is 320x200.
        value &= ~1;
        if (value < 320) {
            value = 320;
        }
        return value;
    }

    private static void addCvar(List<String> args, String name, String value) {
        args.add("+" + name);
        args.add(value);
    }

    /**
     * Splits a command line the way the engine's own parser does: on spaces,
     * honouring double quotes. Backslashes are left alone so Windows-style
     * paths survive.
     */
    public static List<String> tokenize(String commandLine) {
        List<String> tokens = new ArrayList<>();
        if (commandLine == null) {
            return tokens;
        }

        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean haveToken = false;

        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);

            if (c == '"') {
                inQuotes = !inQuotes;
                haveToken = true;
                continue;
            }

            if (!inQuotes && Character.isWhitespace(c)) {
                if (haveToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    haveToken = false;
                }
                continue;
            }

            current.append(c);
            haveToken = true;
        }

        if (haveToken) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    /** Quotes a path so that {@link #tokenize(String)} gives it back unchanged. */
    public static String quote(String value) {
        if (value.indexOf(' ') < 0 && value.indexOf('\t') < 0 && value.indexOf('"') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "") + '"';
    }
}
