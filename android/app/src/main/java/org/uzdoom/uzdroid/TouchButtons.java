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
    private static List<Spec> lastSpecs = new ArrayList<>();
    private static List<String> fileLines = new ArrayList<>();
    private static File configFile;
    private static boolean editing;

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

        configFile = file;

        List<Spec> specs;
        try {
            fileLines = readLines(file);
            specs = parse(fileLines);
        } catch (IOException e) {
            Log.w(TAG, "cannot read " + file, e);
            fileLines = new ArrayList<>();
            specs = new ArrayList<>();
        }

        for (Spec spec : specs) {
            spec.bitmap = load(dir, spec.label);
        }

        lastSpecs = specs;
        layer.setButtons(specs);
        layer.setStickImages(load(dir, "JoyFrame"), load(dir, "JoyKnob"));
        publish(specs);

        CrashLog.append(activity, "touch buttons: " + specs.size() + " from " + file.getAbsolutePath());

        if (specs.isEmpty()) {
            Log.w(TAG, "touch-buttons.txt produced no buttons; see the line formats in it");
            CrashLog.append(activity, "touch buttons: none usable - check label/input/x/y per line");
        }
    }

    /**
     * Menus and gameplay do not share controls: the overlay is hidden while a
     * menu or the console owns the pointer. Called from the game thread.
     */
    public static void setUiContext(boolean ui) {
        if (layer == null) {
            return;
        }
        layer.post(() -> {
            layer.setVisibility(ui ? View.INVISIBLE : View.VISIBLE);
            if (ui) {
                layer.setStick(0, 0, 0, 0, false);
            }
        });
    }

    /**
     * The engine asks for the table once its input is up; the first publish may
     * have been dropped because the native side was still loading.
     */
    public static void republish() {
        publish(lastSpecs);
    }

    /** Drag-to-move mode: the engine handles the fingers, we show the result. */
    public static void setEditing(boolean on) {
        editing = on;

        if (layer == null) {
            return;
        }
        layer.post(() -> {
            layer.setEditing(on);
            if (!on) {
                save();
            }
        });
    }

    /** A button followed the finger. Called from the game thread. */
    public static void moveButton(int index, float x, float y) {
        if (layer == null || index < 0 || index >= lastSpecs.size()) {
            return;
        }

        Spec spec = lastSpecs.get(index);
        spec.x = x;
        spec.y = y;

        layer.post(() -> {
            layer.requestLayout();
            layer.invalidate();
        });
    }

    /**
     * Writes the layout back, touching only the lines that are buttons so the
     * comments in the file survive.
     */
    private static void save() {
        if (configFile == null || fileLines.isEmpty()) {
            return;
        }

        StringBuilder out = new StringBuilder();

        for (int index = 0; index < fileLines.size(); index++) {
            Spec spec = specAtLine(index);

            if (spec != null) {
                out.append(String.format(Locale.ROOT, "%s  %s  %.3f  %.3f  %.3f  %.3f  %.2f%n",
                        spec.label, spec.input, spec.x, spec.y, spec.w, spec.h, spec.alpha));
            } else {
                out.append(fileLines.get(index)).append('\n');
            }
        }

        try (FileWriter writer = new FileWriter(configFile, false)) {
            writer.write(out.toString());
            Log.i(TAG, "saved touch layout to " + configFile);
        } catch (IOException e) {
            Log.w(TAG, "cannot save " + configFile, e);
        }
    }

    private static Spec specAtLine(int line) {
        for (Spec spec : lastSpecs) {
            if (spec.line == line) {
                return spec;
            }
        }
        return null;
    }

    /** Engine tells us which button is held, so it can look pressed. */
    public static void onButtonPressed(int index, boolean pressed) {
        if (layer == null) {
            return;
        }
        layer.post(() -> layer.setPressed(index, pressed));
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
        String input;            // passed to the engine as written
        float x, y, w, h, alpha;
        int line;                // where it came from, so saving keeps the file
        Bitmap bitmap;
        volatile boolean pressed;
    }

    private static List<String> readLines(File file) throws IOException {
        List<String> lines = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static List<Spec> parse(List<String> lines) {
        List<Spec> specs = new ArrayList<>();

        for (int index = 0; index < lines.size(); index++) {
            String raw = lines.get(index);
            String line = raw.trim();

            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            {

                String[] parts = line.split("\\s+");
                if (parts.length < 4) {
                    Log.w(TAG, "touch button needs label, input, x and y: " + line);
                    continue;
                }

                Spec spec = new Spec();
                spec.label = parts[0];
                spec.input = parts[1];
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
                spec.line = index;
                specs.add(spec);
            }
        }

        return specs;
    }

    /**
     * Hands the table to the engine, which owns what a press means. Inputs are
     * validated there, so the same names the game's bind command accepts work.
     */
    private static void publish(List<Spec> specs) {
        EngineBridge.run("uzdroid_button_reset");

        for (Spec spec : specs) {
            // ';' would split the command in the console parser, so it travels
            // as '|' and the engine turns it back.
            String input = spec.input.replace(';', '|');
            EngineBridge.run(String.format(Locale.ROOT,
                    "uzdroid_button %.4f %.4f %.4f %.4f %s %s",
                    spec.x, spec.y, spec.w, spec.h, input, spec.label));
        }
    }

    private static float number(String text, float fallback) {
        try {
            return Float.parseFloat(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
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
            "#          key names are the ones the game's own bind command takes,\n" +
            "#          e.g. f, space, Escape, Tab, [, ], F3, Up, Down, Ctrl\n" +
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
        private final Paint editPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean editingOutline;
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

        void setEditing(boolean on) {
            editingOutline = on;
            invalidate();
        }

        void setPressed(int index, boolean pressed) {
            if (index >= 0 && index < buttons.size()) {
                buttons.get(index).setPressedState(pressed);
            }
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

            if (editingOutline) {
                editPaint.setStyle(Paint.Style.STROKE);
                editPaint.setStrokeWidth(3f);
                editPaint.setColor(Color.WHITE);

                for (Btn button : buttons) {
                    canvas.drawRect(button.getLeft(), button.getTop(),
                            button.getRight(), button.getBottom(), editPaint);
                }
            }

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

    /**
     * A single button. It does not handle touch: the engine owns the fingers,
     * so a held button never starves the look gesture on the other thumb.
     */
    private static final class Btn extends View {
        final Spec spec;

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF shape = new RectF();

        Btn(Context context, Spec spec) {
            super(context);
            this.spec = spec;

            setFocusable(false);
            setFocusableInTouchMode(false);
            setClickable(false);

            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Color.BLACK);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2f);
            stroke.setColor(Color.WHITE);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setFakeBoldText(true);
        }

        void setPressedState(boolean pressed) {
            if (spec.pressed != pressed) {
                spec.pressed = pressed;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (spec.bitmap != null) {
                image.setAlpha((int) (Math.min(1f, spec.alpha * 2f) * 255));
                canvas.drawBitmap(spec.bitmap, null, shape, image);
                if (spec.pressed) {
                    fill(spec.alpha * 1.4f);
                    canvas.drawRoundRect(shape, radius(), radius(), fill);
                }
                return;
            }

            float pad = stroke.getStrokeWidth();
            shape.set(pad, pad, getWidth() - pad, getHeight() - pad);

            fill.setAlpha((int) (spec.alpha * 255));
            stroke.setAlpha((int) (Math.min(1f, spec.alpha * 2f) * 255));

            canvas.drawRoundRect(shape, radius(), radius(), fill);
            canvas.drawRoundRect(shape, radius(), radius(), stroke);

            text.setTextSize(Math.max(10f, Math.min(shape.height() * 0.34f, shape.width() * 0.42f)));
            text.setAlpha((int) (Math.min(1f, spec.alpha * 2.5f) * 255));

            float baseline = shape.centerY() - (text.descent() + text.ascent()) * 0.5f;
            canvas.drawText(spec.label, shape.centerX(), baseline, text);

            if (spec.pressed) {
                fill(spec.alpha * 1.8f);
                canvas.drawRoundRect(shape, radius(), radius(), fill);
            }
        }

        private float radius() {
            return Math.min(shape.width(), shape.height()) * 0.5f;
        }

        private void fill(float alpha) {
            fill.setAlpha((int) (Math.min(1f, alpha) * 255));
        }
    }
}
