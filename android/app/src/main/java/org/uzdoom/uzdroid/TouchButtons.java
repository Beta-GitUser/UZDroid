/*
** TouchButtons.java
**
** The visual half of touch controls: two button layouts - one for gameplay, one
** for menus - plus the joystick frame and knob that follow the engine's stick.
**
** Nothing is preset. Everything comes from the Touch folder in the data
** directory:
**
**   Touch/touch-buttons.txt        gameplay buttons
**   Touch/touch-buttons-menu.txt   menu buttons
**   Touch/<label>.png              the image for a button
**   Touch/JoyFrame.png             stick background
**   Touch/JoyKnob.png              stick thumb
**
** The engine owns the presses; this class draws them and writes the layout back
** after it has been dragged around in the editor.
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

    /** Must match StickRadiusFraction in i_input.cpp. */
    private static final float STICK_RADIUS = 0.10f;

    private static Layer layer;

    private static final Layout GAME = new Layout("touch-buttons.txt", false);
    private static final Layout MENU = new Layout("touch-buttons-menu.txt", true);

    private static boolean uiContext;
    private static boolean editing;

    private TouchButtons() {
    }

    private static final class Layout {
        final String fileName;
        final boolean ui;

        File file;
        List<Spec> specs = new ArrayList<>();
        List<String> lines = new ArrayList<>();

        Layout(String fileName, boolean ui) {
            this.fileName = fileName;
            this.ui = ui;
        }
    }

    private static Layout active() {
        return uiContext ? MENU : GAME;
    }

    // ---------------------------------------------------------------------
    // Life cycle
    // ---------------------------------------------------------------------

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

    /** Re-reads both layouts and the images; called on resume. */
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

        read(dir, GAME);
        read(dir, MENU);

        for (Spec spec : GAME.specs) {
            spec.bitmap = load(dir, spec.label);
        }
        for (Spec spec : MENU.specs) {
            spec.bitmap = load(dir, spec.label);
        }

        layer.setStickImages(load(dir, "JoyFrame"), load(dir, "JoyKnob"));
        showActive();

        publish();

        CrashLog.append(activity, "touch buttons: " + GAME.specs.size() + " game, "
                + MENU.specs.size() + " menu, from " + dir.getAbsolutePath());
    }

    private static void read(File dir, Layout layout) {
        File file = new File(dir, layout.fileName);

        if (!file.isFile()) {
            File legacy = new File(dir.getParentFile(), layout.fileName);
            if (legacy.isFile() && !layout.ui) {
                file = legacy;       // older builds kept the game layout in the root
            } else {
                writeTemplate(file, layout);
            }
        }

        layout.file = file;

        try {
            layout.lines = readLines(file);
            layout.specs = parse(layout.lines);
        } catch (IOException e) {
            Log.w(TAG, "cannot read " + file, e);
            layout.lines = new ArrayList<>();
            layout.specs = new ArrayList<>();
        }

        if (layout.specs.isEmpty()) {
            Log.w(TAG, layout.fileName + " produced no buttons");
        }
    }

    /** The engine asks for the table once its input is up. */
    public static void republish() {
        publish();
    }

    /**
     * Hands both layouts to the engine, which owns what a press means. Inputs
     * are validated there, so the names the game's bind command accepts work.
     */
    private static void publish() {
        EngineBridge.run("uzdroid_button_reset");

        publish(GAME);
        publish(MENU);
    }

    private static void publish(Layout layout) {
        for (Spec spec : layout.specs) {
            // ';' would split the command in the console parser, so it travels
            // as '|' and the engine turns it back.
            String input = spec.input.replace(';', '|');
            EngineBridge.run(String.format(Locale.ROOT,
                    "uzdroid_button %s %.4f %.4f %.4f %.4f %s %s",
                    layout.ui ? "menu" : "game", spec.x, spec.y, spec.w, spec.h,
                    input, spec.label));
        }
    }

    // ---------------------------------------------------------------------
    // Calls from the engine
    // ---------------------------------------------------------------------

    /** Menus get their own layout instead of hiding the gameplay one. */
    public static void setUiContext(boolean ui) {
        uiContext = ui;

        if (layer == null) {
            return;
        }
        layer.post(() -> {
            showActive();
            if (ui) {
                layer.setStick(0, 0, 0, 0, false);
            }
        });
    }

    /** Which button the engine sees held, so the overlay can draw it pressed. */
    public static void onButtonPressed(boolean ui, int index, boolean pressed) {
        Layout layout = ui ? MENU : GAME;

        if (index < 0 || index >= layout.specs.size()) {
            return;
        }

        layout.specs.get(index).pressed = pressed;

        if (layout == active() && layer != null) {
            layer.post(layer::invalidate);
        }
    }

    /** A button was dragged or resized in the editor; remember it. */
    public static void moveButton(int index, boolean ui, float x, float y, float w, float h) {
        Layout layout = ui ? MENU : GAME;

        if (index < 0 || index >= layout.specs.size()) {
            return;
        }

        Spec spec = layout.specs.get(index);
        spec.x = x;
        spec.y = y;
        spec.w = w;
        spec.h = h;

        if (layout == active() && layer != null) {
            layer.post(() -> {
                layer.requestLayout();
                layer.invalidate();
            });
        }
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
                save(active());
            }
        });
    }

    /** Called by the engine whenever the movement stick moves. Game thread. */
    public static void onStick(float originX, float originY, float dx, float dy, boolean active) {
        if (layer == null) {
            return;
        }
        layer.setStick(originX, originY, dx, dy, active);   // posts its own invalidate
    }

    // ---------------------------------------------------------------------
    // Saving
    // ---------------------------------------------------------------------

    /**
     * Writes one layout back, touching only the lines that are buttons so the
     * comments in the file survive.
     */
    private static void save(Layout layout) {
        if (layout.file == null || layout.lines.isEmpty()) {
            return;
        }

        StringBuilder out = new StringBuilder();

        for (int index = 0; index < layout.lines.size(); index++) {
            Spec spec = specAtLine(layout, index);

            if (spec != null) {
                out.append(String.format(Locale.ROOT, "%s  %s  %.3f  %.3f  %.3f  %.3f  %.2f%n",
                        spec.label, spec.input, spec.x, spec.y, spec.w, spec.h, spec.alpha));
            } else {
                out.append(layout.lines.get(index)).append('\n');
            }
        }

        try (FileWriter writer = new FileWriter(layout.file, false)) {
            writer.write(out.toString());
            Log.i(TAG, "saved " + layout.file);
        } catch (IOException e) {
            Log.w(TAG, "cannot save " + layout.file, e);
        }
    }

    private static Spec specAtLine(Layout layout, int line) {
        for (Spec spec : layout.specs) {
            if (spec.line == line) {
                return spec;
            }
        }
        return null;
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
            String line = lines.get(index).trim();

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

    private static float number(String text, float fallback) {
        try {
            return Float.parseFloat(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void writeTemplate(File file, Layout layout) {
        String text = layout.ui ? MENU_TEMPLATE : GAME_TEMPLATE;

        try (FileWriter writer = new FileWriter(file, false)) {
            writer.write(text);
        } catch (IOException e) {
            Log.w(TAG, "cannot write " + file, e);
        }
    }

    private static final String HEADER =
        "# One button per line, whitespace separated:\n" +
        "#\n" +
        "#   label   input   x   y   [width] [height] [alpha]\n" +
        "#\n" +
        "# label  - drawn on the button, and the name of its image:\n" +
        "#          Touch/<label>.png, scaled to fit. Without an image the\n" +
        "#          label is drawn as text.\n" +
        "# input  - a key name, mouse1 / mouse2 / mouse3, cmd:<console\n" +
        "#          command>, or hold:<+command> to also run the opposite\n" +
        "#          command when the button is released. Key names are the\n" +
        "#          ones the game's own bind command takes: f, space, Escape,\n" +
        "#          Tab, [, ], F3, Up, Down, Ctrl.\n" +
        "# x, y   - top left corner, as a fraction of the screen (0..1)\n" +
        "# width  - default 0.12, height 0.14, alpha 0.4\n" +
        "#\n" +
        "# Options -> Touch controls -> Move the buttons moves them without\n" +
        "# editing this file by hand.\n" +
        "#\n";

    private static final String GAME_TEMPLATE =
        "# UZDroid gameplay buttons (shown while in a level).\n" +
        HEADER +
        "# Nothing is defined yet. Example:\n" +
        "#   fire    hold:+attack   0.86  0.60  0.12  0.18  0.45\n";

    private static final String MENU_TEMPLATE =
        "# UZDroid menu buttons (shown while a menu or the console is open).\n" +
        HEADER +
        "# Nothing is defined yet. Example:\n" +
        "#   up      Up      0.06  0.55  0.09  0.12  0.45\n" +
        "#   ok      Enter   0.84  0.80  0.12  0.14  0.45\n";

    private static Bitmap load(File dir, String name) {
        File file = new File(dir, name + ".png");
        if (!file.isFile()) {
            return null;
        }
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    private static void showActive() {
        layer.setButtons(active().specs);
        layer.setEditing(editing);
    }

    /** Lays the buttons out by fraction of the surface, so rotation is free. */
    private static final class Layer extends ViewGroup {
        private final List<Btn> buttons = new ArrayList<>();

        private Bitmap frame;
        private Bitmap knob;

        private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint editPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF scratch = new RectF();
        private boolean editingOutline;

        // Written by the game thread, read here.
        private volatile float stickX, stickY, stickDx, stickDy;
        private volatile boolean stickActive;

        Layer(Context context) {
            super(context);
            setClipChildren(false);
            setWillNotDraw(false);   // the joystick and the edit outlines
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
