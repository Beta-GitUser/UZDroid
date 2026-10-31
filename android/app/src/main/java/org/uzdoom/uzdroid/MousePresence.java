/*
** MousePresence.java
**
** Android draws its own pointer for a connected mouse, so the engine must only
** draw a touch cursor while no mouse is attached. This watches the input
** devices and keeps the engine's touch_mouse_present cvar in step, including
** when a mouse is plugged in or unplugged during play.
**
** SPDX-License-Identifier: GPL-3.0-or-later
*/

package org.uzdoom.uzdroid;

import android.app.Activity;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Build;
import android.util.Log;
import android.view.InputDevice;

public final class MousePresence implements InputManager.InputDeviceListener {

    private static final String TAG = "UZDroid";

    private static MousePresence instance;

    private final InputManager inputManager;
    private boolean reported;

    private MousePresence(InputManager inputManager) {
        this.inputManager = inputManager;
    }

    public static void start(Activity activity) {
        if (instance != null) {
            instance.refresh();
            return;
        }

        InputManager manager = (InputManager) activity.getSystemService(Context.INPUT_SERVICE);
        if (manager == null) {
            Log.w(TAG, "no input manager, assuming no mouse");
            return;
        }

        instance = new MousePresence(manager);
        manager.registerInputDeviceListener(instance, null);
        instance.refresh();
    }

    /** Asks the engine to draw its own cursor only when there is no mouse. */
    public static void refreshNow() {
        if (instance != null) {
            instance.refresh();
        }
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        refresh();
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        refresh();
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        refresh();
    }

    private void refresh() {
        boolean mouse = false;

        for (int id : inputManager.getInputDeviceIds()) {
            InputDevice device = inputManager.getInputDevice(id);
            if (device == null) {
                continue;
            }

            int sources = device.getSources();
            boolean isMouse = (sources & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Captured, relative-only mice report this instead.
                isMouse |= (sources & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE;
            }

            if (isMouse) {
                mouse = true;
                break;
            }
        }

        Log.i(TAG, "mouse attached: " + mouse);

        // Always send: the engine may have started after the first look.
        EngineBridge.run("touch_mouse_present " + (mouse ? 1 : 0));
        reported = mouse;
    }
}
