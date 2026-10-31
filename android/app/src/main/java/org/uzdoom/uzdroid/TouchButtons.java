/*
** TouchButtons.java
**
** On-screen buttons for touch play. Nothing is preset: the layout comes from
** touch-buttons.txt in the data folder, and an empty file means no buttons at
** all. Each button sends an ordinary key or mouse button into SDL, so it shows
** up in the engine's Controls menu and can be bound to anything there.
**
** SPDX-License-Identifier: GPL-3.0-or-later
*/

package org.uzdoom.uzdroid;

import android.app.Activity;
import android.content.Context;
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
    private static final String CONFIG = "touch-buttons.txt";

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
            Log.w(TAG, "no SDL layout yet, touch buttons not installed");
            return;
        }

        layer = new Layer(activity);
        ((ViewGroup) layout).addView(layer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        reload(activity);
    }

    /** Re-reads the config file; called on resume so edits apply immediately. */
    public static void reload(Activity activity) {
        if (layer == null) {
            return;
        }

        File file = new File(EnginePaths.root(activity), CONFIG);

        if (!file.isFile()) {
            writeTemplate(file);
        }

        List<Spec> specs;
        try {
            specs = parse(file);
        } catch (IOException e) {
            Log.w(TAG, "cannot read " + file, e);
            specs = new ArrayList<>();
        }

        layer.setButtons(specs);
        CrashLog.append(activity, "touch buttons: " + specs.size() + " from " + file.getAbsolutePath());
    }

    // ---------------------------------------------------------------------
    // Config
    // ---------------------------------------------------------------------

    private static final class Spec {
        String label;
        int keyCode = -1;        // Android key code, or
        int mouseButton = 0;     // 1..3 for mouse1..mouse3
        float x, y, w, h, alpha;
    }

    private static List<Spec> parse(File file) throws IOException {
        List<Spec> specs = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
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

                String input = parts[1].toLowerCase(Locale.ROOT);
                if (input.startsWith("mouse")) {
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
            "# label  - text drawn on the button\n" +
            "# input  - a key name, or mouse1 / mouse2 / mouse3\n" +
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
            "#   fire    mouse1   0.86  0.60  0.12  0.18  0.45\n" +
            "#   use     e        0.74  0.60  0.10  0.14  0.45\n" +
            "#   jump    space    0.74  0.78  0.10  0.14  0.45\n" +
            "#   menu    esc      0.94  0.04  0.08  0.10  0.45\n" +
            "#\n" +
            "# The keys arrive at the engine as a keyboard or mouse, so bind them in\n" +
            "# Options -> Customize Controls like any real input device.\n";

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

        Layer(Context context) {
            super(context);
            setClipChildren(false);
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

        private void send(boolean down) {
            if (down == sent) {
                return;
            }
            sent = down;

            float cx = getX() + getWidth() * 0.5f;
            float cy = getY() + getHeight() * 0.5f;

            try {
                if (spec.mouseButton > 0) {
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
