/*
** TouchButtons.java
**
** The visual side of touch controls: on-screen buttons, and the joystick frame
** and knob that follow the engine's movement stick.
**
** Nothing is preset. Everything comes from the Touch folder in the data
** directory:
**
**   Touch/touch-buttons.txt   one line per button
**   Touch/<label>.png         the image for a button
**   Touch/JoyFrame.png        stick background
**   Touch/JoyKnob.png         stick thumb
**
** Images are scaled to their button, so one drawing works at any resolution.
** A button without an image falls back to its label as text.
**
** SPDX-License-Identifier: GPL-3.0-or-later
*/

package org.uzdoom.uzdroid;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import org.libsdl.app.SDLActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TouchButtons {

    private static final String TAG = "UZDroid";
    private static final String DIR = "Touch";
    private static final String CONFIG = "touch-buttons.txt";

    /** Must match StickRadiusFraction in i_input.cpp. */
    private static final float STICK_RADIUS = 0.10f;

    private static Layer layer;

    private TouchButtons() {
    }

    /** Adds the overlay to SDL's own layout, above the game surface. */
    public static void install(Activity activity) {
        if (layer != null) {
            return;
        }

        View layout = SDLActivity.getContentView();   // SDL's RelativeLayout
        if (!(layout instanceof ViewGroup)) {
            // Not fatal: reload() tries again, which happens on every resume.
            Log.w(TAG, "no SDL layout yet, touch buttons not installed");
            CrashLog.append(activity, "touch overlay: no SDL layout yet, retrying on resume");
            return;
        }

        layer = new Layer(activity);
        CrashLog.append(activity, "touch overlay: added above the game surface");
        ((ViewGroup) layout).addView(layer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // The shipped layout and art have to be in place before the first read,
        // otherwise the first launch would create an empty config and the
        // defaults would never be copied.
        try {
            EnginePaths.ensureTouchData(activity, EnginePaths.root(activity));
        } catch (IOException e) {
            Log.w(TAG, "could not copy the Touch folder", e);
        }

        reload(activity);
    }

    /** Re-reads the config and the images; called on resume. */
    public static void reload(Activity activity) {
        if (layer == null) {
            install(activity);       // retry: the layout may not have existed yet
            if (layer == null) {
                return;
            }
        }

        File dir = new File(EnginePaths.root(activity), DIR);
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            Log.w(TAG, "cannot create " + dir);
        }

        File file = new File(dir, CONFIG);
        if (!file.isFile()) {
            // Older builds kept it in the root; keep reading that if it is there.
            File legacy = new File(EnginePaths.root(activity), CONFIG);
            if (legacy.isFile()) {
                file = legacy;
            } else {
                writeTemplate(file);
            }
        }

        List<Spec> specs;
        try {
            specs = parse(file);
        } catch (IOException e) {
            Log.w(TAG, "cannot read " + file, e);
            specs = new ArrayList<>();
        }

        for (Spec spec : specs) {
            spec.bitmap = load(dir, spec.label);
        }

        layer.setButtons(specs);
        layer.setStickImages(load(dir, "JoyFrame"), load(dir, "JoyKnob"));

        CrashLog.append(activity, "touch buttons: " + specs.size() + " from " + file.getAbsolutePath());

        if (specs.isEmpty()) {
            Log.w(TAG, "touch-buttons.txt produced no buttons; see the line formats in it");
            CrashLog.append(activity, "touch buttons: none usable - check label/input/x/y per line");
        }
    }

    /** Called by the engine whenever the movement stick moves. Game thread. */
    public static void onStick(float originX, float originY, float dx, float dy, boolean active) {
        if (layer == null) {
            return;
        }
        layer.setStick(originX, originY, dx, dy, active);   // posts its own invalidate
    }

    private static Bitmap load(File dir, String name) {
        File file = new File(dir, name + ".png");
        if (!file.isFile()) {
            return null;
        }
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    // ---------------------------------------------------------------------
    // Config
    // ---------------------------------------------------------------------

    private static final class Spec {
        String label;
        int keyCode = -1;        // Android key code, or
        int mouseButton = 0;     // 1..3 for mouse1..mouse3, or
        String command;          // a console command
        boolean hold;            // press runs it, release runs its opposite
        float x, y, w, h, alpha;
        Bitmap bitmap;
    }

    private static List<Spec> parse(File file) throws IOException {
        List<Spec> specs = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // A ';' can separate a command chain, so only '#' starts a comment.
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split("\\s+");
                if (parts.length < 4) {
                    Log.w(TAG, "touch button needs label, input, x and y: " + line);
                    continue;
                }

                Spec spec = new Spec();
                spec.label = parts[0];
                spec.x = number(parts[2], -1f);
                spec.y = number(parts[3], -1f);
                spec.w = parts.length > 4 ? number(parts[4], 0.12f) : 0.12f;
                spec.h = parts.length > 5 ? number(parts[5], 0.14f) : 0.14f;
                spec.alpha = parts.length > 6 ? number(parts[6], 0.4f) : 0.4f;

                if (spec.x < 0 || spec.y < 0) {
                    Log.w(TAG, "touch button needs fractional x and y: " + line);
                    continue;
                }

                spec.alpha = Math.max(0.05f, Math.min(1f, spec.alpha));

                String raw = parts[1];
                String input = raw.toLowerCase(Locale.ROOT);

                if (raw.regionMatches(true, 0, "cmd:", 0, 4) && raw.length() > 4) {
                    // Runs a console command: an alias, a mod action, anything
                    // the console can name. Press only.
                    spec.command = raw.substring(4);
                } else if (raw.regionMatches(true, 0, "hold:", 0, 5) && raw.length() > 5) {
                    // Press runs the command, release runs the opposite one
                    // (+attack / -attack), which is what a real button does.
                    spec.command = raw.substring(5);
                    spec.hold = true;
                } else if (input.startsWith("mouse")) {
                    try {
                        int button = Integer.parseInt(input.substring(5));
                        if (button < 1 || button > 3) {
                            Log.w(TAG, "only mouse1..mouse3 are supported: " + line);
                            continue;
                        }
                        spec.mouseButton = button;
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "unknown mouse button: " + line);
                        continue;
                    }
                } else {
                    spec.keyCode = keyCode(input);
                    if (spec.keyCode < 0) {
                        Log.w(TAG, "unknown key '" + input + "': " + line);
                        continue;
                    }
                }

                specs.add(spec);
            }
        }

        return specs;
    }

    private static float number(String text, float fallback) {
        try {
            return Float.parseFloat(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Key names a config file can use. Letters and digits are the fallback. */
    private static int keyCode(String name) {
        switch (name) {
            case "esc": case "escape": return KeyEvent.KEYCODE_ESCAPE;
            case "space": return KeyEvent.KEYCODE_SPACE;
            case "enter": return KeyEvent.KEYCODE_ENTER;
            case "tab": return KeyEvent.KEYCODE_TAB;
            case "shift": return KeyEvent.KEYCODE_SHIFT_LEFT;
            case "ctrl": case "control": return KeyEvent.KEYCODE_CTRL_LEFT;
            case "alt": return KeyEvent.KEYCODE_ALT_LEFT;
            case "up": return KeyEvent.KEYCODE_DPAD_UP;
            case "down": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "left": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "right": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "comma": return KeyEvent.KEYCODE_COMMA;
            case "period": return KeyEvent.KEYCODE_PERIOD;
            case "minus": return KeyEvent.KEYCODE_MINUS;
            case "equals": return KeyEvent.KEYCODE_EQUALS;
            case "lbracket": return KeyEvent.KEYCODE_LEFT_BRACKET;
            case "rbracket": return KeyEvent.KEYCODE_RIGHT_BRACKET;
            case "backspace": return KeyEvent.KEYCODE_DEL;
            case "pageup": return KeyEvent.KEYCODE_PAGE_UP;
            case "pagedown": return KeyEvent.KEYCODE_PAGE_DOWN;
            case "home": return KeyEvent.KEYCODE_MOVE_HOME;
            case "end": return KeyEvent.KEYCODE_MOVE_END;
            default:
                break;
        }

        if (name.length() > 1 && name.charAt(0) == 'f') {
            try {
                int n = Integer.parseInt(name.substring(1));
                if (n >= 1 && n <= 12) {
                    return KeyEvent.KEYCODE_F1 + (n - 1);
                }
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }

        if (name.length() == 1) {
            char c = name.charAt(0);
            if (c >= 'a' && c <= 'z') {
                return KeyEvent.KEYCODE_A + (c - 'a');
            }
            if (c >= '0' && c <= '9') {
                return KeyEvent.KEYCODE_0 + (c - '0');
            }
        }

        return -1;
    }

    private static void writeTemplate(File file) {
        String text =
            "# UZDroid touch buttons\n" +
            "#\n" +
            "# One button per line, whitespace separated:\n" +
            "#\n" +
            "#   label   input   x   y   [width] [height] [alpha]\n" +
            "#\n" +
            "# label  - drawn on the button, and the name of its image:\n" +
            "#          Touch/<label>.png, scaled to the button. Without an\n" +
            "#          image the label is drawn as text.\n" +
            "# input  - a key name, mouse1 / mouse2 / mouse3, cmd:<console\n" +
            "#          command>, or hold:<+command> to also run the opposite\n" +
            "#          command when the button is released\n" +
            "#          keys: a-z, 0-9, space, enter, esc, tab, shift, ctrl, alt,\n" +
            "#                up, down, left, right, f1-f12, comma, period, minus,\n" +
            "#                equals, lbracket, rbracket, backspace, pageup,\n" +
            "#                pagedown, home, end\n" +
            "# x, y   - top left corner, as a fraction of the screen (0..1)\n" +
            "# width  - default 0.12\n" +
            "# height - default 0.14\n" +
            "# alpha  - default 0.4\n" +
            "#\n" +
            "# Nothing is defined yet, so there are no buttons on screen. Example:\n" +
            "#\n" +
            "#   fire    hold:+attack           0.86  0.60  0.12  0.18  0.45\n" +
            "#   use     cmd:+use               0.74  0.60  0.10  0.14  0.45\n" +
            "#   console cmd:toggleconsole      0.94  0.16  0.08  0.10  0.45\n" +
            "#   keys    cmd:uzdroid_kbup       0.94  0.28  0.08  0.10  0.45\n" +
            "#\n" +
            "# Commands run through the console, the way a bound key runs them, so\n" +
            "# mod actions and aliases work without needing a spare key. A command\n" +
            "# cannot contain spaces; wrap it in an alias first.\n";

        try (FileWriter writer = new FileWriter(file, false)) {
            writer.write(text);
        } catch (IOException e) {
            Log.w(TAG, "cannot write " + file, e);
        }
    }

    // ---------------------------------------------------------------------
    // Views
    // ---------------------------------------------------------------------

    /** Lays the buttons out by fraction of the surface, so rotation is free. */
    private static final class Layer extends ViewGroup {
        private final List<Btn> buttons = new ArrayList<>();

        private Bitmap frame;
        private Bitmap knob;

        private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF scratch = new RectF();

        // Written by the game thread, read here.
        private volatile float stickX, stickY, stickDx, stickDy;
        private volatile boolean stickActive;

        Layer(Context context) {
            super(context);
            setClipChildren(false);
            setWillNotDraw(false);   // the joystick is drawn by the layer itself
            imagePaint.setAlpha(200);
        }

        void setButtons(List<Spec> specs) {
            removeAllViews();
            buttons.clear();

            for (Spec spec : specs) {
                Btn button = new Btn(getContext(), spec);
                buttons.add(button);
                addView(button);
            }

            requestLayout();
            invalidate();
        }

        void setStickImages(Bitmap frame, Bitmap knob) {
            this.frame = frame;
            this.knob = knob;
            invalidate();
        }

        void setStick(float originX, float originY, float dx, float dy, boolean active) {
            stickX = originX;
            stickY = originY;
            stickDx = dx;
            stickDy = dy;
            stickActive = active;
            postInvalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            if (!stickActive || frame == null) {
                return;
            }

            float radius = Math.min(getWidth(), getHeight()) * STICK_RADIUS;
            if (radius <= 0) {
                return;
            }

            scratch.set(stickX - radius, stickY - radius, stickX + radius, stickY + radius);
            canvas.drawBitmap(frame, null, scratch, imagePaint);

            if (knob != null) {
                float kx = stickX + stickDx * radius;
                float ky = stickY + stickDy * radius;
                float kr = radius * 0.8f;
                scratch.set(kx - kr, ky - kr, kx + kr, ky + kr);
                canvas.drawBitmap(knob, null, scratch, imagePaint);
            }
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int height = MeasureSpec.getSize(heightSpec);

            setMeasuredDimension(width, height);

            for (Btn button : buttons) {
                int w = Math.max(1, Math.round(button.spec.w * width));
                int h = Math.max(1, Math.round(button.spec.h * height));
                button.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            }
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int width = right - left;
            int height = bottom - top;

            for (Btn button : buttons) {
                int x = Math.round(button.spec.x * width);
                int y = Math.round(button.spec.y * height);
                button.layout(x, y, x + button.getMeasuredWidth(), y + button.getMeasuredHeight());
            }
        }
    }

    /** A single round button that holds one input while pressed. */
    private static final class Btn extends View {
        final Spec spec;

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF shape = new RectF();

        private boolean pressed;
        private boolean sent;

        Btn(Context context, Spec spec) {
            super(context);
            this.spec = spec;

            setFocusable(false);
            setFocusableInTouchMode(false);

            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Color.BLACK);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2f);
            stroke.setColor(Color.WHITE);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setFakeBoldText(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (spec.bitmap != null) {
                // The drawing defines the look; alpha still comes from the file
                // so buttons can be made see-through as a group.
                image.setAlpha((int) (Math.min(1f, spec.alpha * 2f) * 255));
                canvas.drawBitmap(spec.bitmap, null, shape, image);
                return;
            }

            float pad = stroke.getStrokeWidth();
            shape.set(pad, pad, getWidth() - pad, getHeight() - pad);

            fill.setAlpha((int) (spec.alpha * 255));
            stroke.setAlpha((int) (Math.min(1f, spec.alpha * 2f) * 255));

            float radius = Math.min(shape.width(), shape.height()) * 0.5f;
            canvas.drawRoundRect(shape, radius, radius, fill);
            canvas.drawRoundRect(shape, radius, radius, stroke);

            text.setTextSize(Math.max(10f, Math.min(shape.height() * 0.34f, shape.width() * 0.42f)));
            text.setAlpha((int) (Math.min(1f, spec.alpha * 2.5f) * 255));

            float baseline = shape.centerY() - (text.descent() + text.ascent()) * 0.5f;
            canvas.drawText(spec.label, shape.centerX(), baseline, text);

            if (pressed) {
                fill.setAlpha((int) (Math.min(1f, spec.alpha * 1.6f) * 255));
                canvas.drawRoundRect(shape, radius, radius, fill);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = true;
                    invalidate();
                    send(true);
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (pressed) {
                        pressed = false;
                        invalidate();
                        send(false);
                    }
                    return true;

                default:
                    return true;
            }
        }

        /** "+attack" becomes "-attack"; anything else has no opposite. */
        private static String opposite(String command) {
            if (command.startsWith("+")) {
                return "-" + command.substring(1);
            }
            if (command.startsWith("-")) {
                return "+" + command.substring(1);
            }
            return "";
        }

        private void send(boolean down) {
            if (down == sent) {
                return;
            }
            sent = down;

            float cx = getX() + getWidth() * 0.5f;
            float cy = getY() + getHeight() * 0.5f;

            try {
                if (spec.command != null) {
                    if (down) {
                        EngineBridge.run(spec.command);
                    } else if (spec.hold) {
                        String opposite = opposite(spec.command);
                        if (!opposite.isEmpty()) {
                            EngineBridge.run(opposite);
                        }
                    }
                } else if (spec.mouseButton > 0) {
                    int state = down ? (1 << (spec.mouseButton - 1)) : 0;
                    SDLActivity.onNativeMouse(state,
                            down ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP,
                            cx, cy, false);
                } else if (down) {
                    SDLActivity.onNativeKeyDown(spec.keyCode);
                } else {
                    SDLActivity.onNativeKeyUp(spec.keyCode);
                }
            } catch (UnsatisfiedLinkError e) {
                // SDL is not up yet; nothing to send to.
                Log.w(TAG, "input dropped, SDL not loaded", e);
            }
        }
    }
}
