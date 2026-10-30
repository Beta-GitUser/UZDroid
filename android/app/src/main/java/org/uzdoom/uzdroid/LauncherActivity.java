/*
** LauncherActivity.java
**
** The wrapper launcher: picks a WAD, sets the render resolution, lets the user
** edit the engine command line and points at the data folder that mirrors a
** desktop UZDoom installation.
**
** SPDX-License-Identifier: GPL-3.0-or-later
*/

package org.uzdoom.uzdroid;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class LauncherActivity extends AppCompatActivity {

    private static final int REQ_PICK_FILE = 1001;

    /** SeekBar range: 25% .. 200% in 5% steps. */
    private static final int SCALE_MIN = 25;
    private static final int SCALE_MAX = 200;
    private static final int SCALE_STEP = 5;

    private LauncherPrefs prefs;
    private File root;

    private TextView tvRoot;
    private Button btnStorage;
    private Button btnLaunch;
    private Button btnRescan;
    private Spinner spinIwad;
    private List<File> wads = new ArrayList<>();

    private TextView tvScale;
    private SeekBar seekScale;
    private CheckBox chkCustom;
    private EditText etWidth;
    private EditText etHeight;
    private Button btnScreenSize;

    private EditText etArgs;
    private Button btnAddFile;

    /** Set when the engine is started, so an immediate return can be reported. */
    private long launchedAt = 0;
    private long logSizeAtLaunch = 0;

    private CheckBox chkJoystick;
    private CheckBox chkTouchMouse;
    private CheckBox chkSound;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Before anything else: if layout inflation or the settings blow up, we
        // want a stack trace on disk rather than a window that closes.
        CrashLog.install(this);
        CrashLog.append(this, "launcher started");

        setContentView(R.layout.activity_launcher);
        CrashLog.append(this, "layout inflated");

        prefs = LauncherPrefs.load(this);

        tvRoot = findViewById(R.id.tv_root);
        btnStorage = findViewById(R.id.btn_storage);
        btnLaunch = findViewById(R.id.btn_launch);
        btnRescan = findViewById(R.id.btn_rescan);
        spinIwad = findViewById(R.id.spin_iwad);

        tvScale = findViewById(R.id.tv_scale);
        seekScale = findViewById(R.id.seek_scale);
        chkCustom = findViewById(R.id.chk_custom);
        etWidth = findViewById(R.id.et_width);
        etHeight = findViewById(R.id.et_height);
        btnScreenSize = findViewById(R.id.btn_screen_size);

        etArgs = findViewById(R.id.et_args);
        btnAddFile = findViewById(R.id.btn_add_file);

        chkJoystick = findViewById(R.id.chk_joystick);
        chkTouchMouse = findViewById(R.id.chk_touch_mouse);
        chkSound = findViewById(R.id.chk_sound);

        setupListeners();
        restoreControls();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from the game (or from a permission screen) should always
        // leave the launcher in a usable state.
        setBusy(false);
        refresh();
        reportImmediateExit();
    }

    /**
     * A start-up crash used to look like "the app just closed". If the engine
     * came back within a few seconds and did not add a single line to its log,
     * say so instead of leaving the user guessing.
     */
    private void reportImmediateExit() {
        if (launchedAt == 0) {
            return;
        }

        final long elapsed = System.currentTimeMillis() - launchedAt;
        launchedAt = 0;

        File log = new File(root, "logs/uzdoom.log");
        long size = log.isFile() ? log.length() : 0;

        if (elapsed > 10000 || size > logSizeAtLaunch) {
            return;
        }

        CrashLog.append(this, "engine returned after " + elapsed + " ms without writing a log");

        new AlertDialog.Builder(this)
                .setTitle(R.string.early_exit_title)
                .setMessage(getString(R.string.early_exit_message, elapsed / 1000))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.share_log, (dialog, which) -> shareLog())
                .show();
    }

    // -----------------------------------------------------------------------
    // UI wiring
    // -----------------------------------------------------------------------

    private void setupListeners() {
        btnStorage.setOnClickListener(v -> EnginePaths.requestAllFilesAccess(this));

        btnLaunch.setOnClickListener(v -> launch(selectedWad()));

        btnRescan.setOnClickListener(v -> refresh());

        seekScale.setMax((SCALE_MAX - SCALE_MIN) / SCALE_STEP);
        seekScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateScaleLabel(scalePercent());
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.scalePercent = scalePercent();
                persist();
            }
        });

        chkCustom.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.customResolution = isChecked;
            etWidth.setEnabled(isChecked);
            etHeight.setEnabled(isChecked);
            btnScreenSize.setEnabled(isChecked);
            seekScale.setEnabled(!isChecked);
            persist();
        });

        btnScreenSize.setOnClickListener(v -> {
            int[] size = screenSize();
            etWidth.setText(Integer.toString(size[0]));
            etHeight.setText(Integer.toString(size[1]));
            persistFromControls();
        });

        btnAddFile.setOnClickListener(v -> pickFile());

        chkJoystick.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.joystickEnabled = isChecked;
            persist();
        });

        chkTouchMouse.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.touchAsMouse = isChecked;
            persist();
        });

        chkSound.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.soundEnabled = isChecked;
            persist();
        });

        findViewById(R.id.btn_open_data).setOnClickListener(v -> openDataFolder());
        findViewById(R.id.btn_share_log).setOnClickListener(v -> shareLog());
        findViewById(R.id.btn_reset_config).setOnClickListener(v -> confirmResetConfig());

        View.OnFocusChangeListener focusPersist = (v, hasFocus) -> {
            if (!hasFocus) {
                persistFromControls();
            }
        };
        etWidth.setOnFocusChangeListener(focusPersist);
        etHeight.setOnFocusChangeListener(focusPersist);
        etArgs.setOnFocusChangeListener(focusPersist);
    }

    private void restoreControls() {
        seekScale.setProgress((clampScale(prefs.scalePercent) - SCALE_MIN) / SCALE_STEP);
        updateScaleLabel(prefs.scalePercent);

        chkCustom.setChecked(prefs.customResolution);
        etWidth.setText(Integer.toString(prefs.customWidth));
        etHeight.setText(Integer.toString(prefs.customHeight));
        etWidth.setEnabled(prefs.customResolution);
        etHeight.setEnabled(prefs.customResolution);
        btnScreenSize.setEnabled(prefs.customResolution);
        seekScale.setEnabled(!prefs.customResolution);

        chkJoystick.setChecked(prefs.joystickEnabled);
        chkTouchMouse.setChecked(prefs.touchAsMouse);
        chkSound.setChecked(prefs.soundEnabled);

        etArgs.setText(prefs.extraArgs);
        etArgs.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    }

    private void refresh() {
        root = EnginePaths.root(this);
        boolean created = EnginePaths.ensureLayout(root);
        boolean external = EnginePaths.hasAllFilesAccess(this);

        String text = root.getAbsolutePath()
                + (external ? "\n(readable from any file manager)" : "\n(app-private: grant file access for Documents/UZDroid)");
        if (!created) {
            text += "\nWARNING: could not create some folders";
        }
        tvRoot.setText(text);

        btnStorage.setVisibility(external ? View.GONE : View.VISIBLE);

        populateWads();
    }

    /**
     * Fills the IWAD dropdown. One row instead of a button per WAD, which used
     * to push everything else off the screen.
     */
    private void populateWads() {
        wads = EnginePaths.findWads(
                new File(root, "iwads"),
                new File(root, "data/games/uzdoom"));

        List<String> labels = new ArrayList<>();
        int selected = 0;
        for (int i = 0; i < wads.size(); i++) {
            File wad = wads.get(i);
            labels.add(wad.getName());
            if (wad.getAbsolutePath().equals(prefs.lastWad)) {
                selected = i;
            }
        }

        final boolean empty = labels.isEmpty();
        if (empty) {
            labels.add(getString(R.string.no_wads_short, new File(root, "iwads").getAbsolutePath()));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinIwad.setAdapter(adapter);
        spinIwad.setEnabled(!empty);
        spinIwad.setSelection(selected, false);
        spinIwad.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                File wad = selectedWad();
                prefs.lastWad = wad == null ? "" : wad.getAbsolutePath();
                persist();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /** The WAD picked in the dropdown, or null when there is none. */
    private File selectedWad() {
        int position = spinIwad.getSelectedItemPosition();
        if (position < 0 || position >= wads.size()) {
            return null;
        }
        return wads.get(position);
    }

    // -----------------------------------------------------------------------
    // Launching
    // -----------------------------------------------------------------------

    private void launch(File iwad) {
        persistFromControls();
        prefs.save(this);

        final File targetRoot = EnginePaths.root(this);
        if (!EnginePaths.ensureLayout(targetRoot)) {
            Toast.makeText(this, R.string.error_storage, Toast.LENGTH_LONG).show();
            return;
        }

        setBusy(true);

        final int[] size = screenSize();
        final String[] args = LaunchArgs.build(prefs, iwad, size[0], size[1]);
        CrashLog.append(this, "launching: " + android.text.TextUtils.join(" ", args));

        File engineLog = new File(targetRoot, "logs/uzdoom.log");
        logSizeAtLaunch = engineLog.isFile() ? engineLog.length() : 0;
        launchedAt = System.currentTimeMillis();

        new Thread(() -> {
            String error = null;
            try {
                EnginePaths.ensureEngineData(getApplicationContext(), targetRoot);
            } catch (IOException e) {
                error = e.getMessage();
            }

            CrashLog.append(getApplicationContext(),
                    failure == null ? "engine data ready" : "engine data failed: " + failure);

            final String failure = error;
            runOnUiThread(() -> {
                setBusy(false);
                if (failure != null) {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.error_title)
                            .setMessage(getString(R.string.error_assets, failure))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return;
                }
                try {
                    startActivity(GameActivity.createIntent(this, args));
                } catch (Exception e) {
                    Toast.makeText(this, getString(R.string.error_launch, e.toString()), Toast.LENGTH_LONG).show();
                }
            });
        }, "uzdroid-assets").start();
    }

    private void setBusy(boolean busy) {
        btnLaunch.setEnabled(!busy);
        btnLaunch.setText(busy ? R.string.preparing : R.string.launch);
    }

    // -----------------------------------------------------------------------
    // File picker (adds any file to the command line)
    // -----------------------------------------------------------------------

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK_FILE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.error_no_picker, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQ_PICK_FILE || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        String current = etArgs.getText().toString().trim();

        // Any file can be picked; what it is decides what happens. A text file
        // holding argument lines is inlined as a launch config, everything else
        // becomes "-file <path>".
        LaunchConfig.Sniffed sniffed = sniff(uri);
        if (sniffed.config) {
            etArgs.setText(current.isEmpty() ? sniffed.args : current + " " + sniffed.args);
            etArgs.setSelection(etArgs.getText().length());
            persistFromControls();
            Toast.makeText(this, R.string.applied_config, Toast.LENGTH_SHORT).show();
            return;
        }

        String path = resolvePath(uri);

        if (path == null) {
            path = importFile(uri);
        }

        if (path == null) {
            Toast.makeText(this, R.string.error_import, Toast.LENGTH_LONG).show();
            return;
        }

        etArgs.setText(LaunchConfig.appendFile(current, path));
        etArgs.setSelection(etArgs.getText().length());
        persistFromControls();
    }

    private LaunchConfig.Sniffed sniff(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return new LaunchConfig.Sniffed(false, "");
            }
            return LaunchConfig.sniff(in);
        } catch (IOException | SecurityException e) {
            return new LaunchConfig.Sniffed(false, "");
        }
    }

    /**
     * Best effort attempt to turn a content:// URI into a real path. This works
     * for the "External storage" provider (which is what the system picker uses
     * for files on the shared storage) and fails for everything else, in which
     * case the file is copied into the data folder instead.
     */
    private String resolvePath(Uri uri) {
        if ("file".equals(uri.getScheme())) {
            return uri.getPath();
        }

        String docId = null;
        try {
            docId = android.provider.DocumentsContract.getDocumentId(uri);
        } catch (Exception ignored) {
            // Not a DocumentsProvider URI.
        }

        if (docId == null) {
            return null;
        }

        // "primary:Download/doom2.wad" -> /storage/emulated/0/Download/doom2.wad
        int colon = docId.indexOf(':');
        if (colon < 0) {
            return null;
        }

        String volume = docId.substring(0, colon);
        String relative = docId.substring(colon + 1);

        if ("primary".equalsIgnoreCase(volume)) {
            File candidate = new File(android.os.Environment.getExternalStorageDirectory(), relative);
            return candidate.isFile() ? candidate.getAbsolutePath() : null;
        }

        return null;
    }

    private String importFile(Uri uri) {
        String name = queryDisplayName(uri);
        if (TextUtils.isEmpty(name)) {
            // Any file at all can be added, so do not invent an extension.
            name = "imported-" + System.currentTimeMillis();
        }
        // Never let the provider dictate a path.
        name = new File(name).getName();

        File modsDir = new File(root, "mods");
        if (!modsDir.isDirectory() && !modsDir.mkdirs()) {
            return null;
        }
        File destination = new File(modsDir, name);

        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(destination)) {
            if (in == null) {
                return null;
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
            return destination.getAbsolutePath();
        } catch (IOException e) {
            return null;
        }
    }

    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver()
                .query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
            // Fall through.
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Data folder helpers
    // -----------------------------------------------------------------------

    /**
     * Hands the data folder to whatever file manager understands
     * {@code resource/folder}. Nothing ships with Android that does, so the
     * usual outcome is the path being shown instead - which is still the useful
     * part, because it is a real, browsable folder.
     */
    private void openDataFolder() {
        File folder = new File(root, "data");
        if (!folder.isDirectory()) {
            folder = root;
        }

        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", folder);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "resource/folder");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            Toast.makeText(this, folder.getAbsolutePath(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Shares every log and crash file there is. The interesting failure mode is
     * "the app died and wrote nothing", so the journal and the crash dumps from
     * the private folder are included - they exist even when the data folder is
     * not writable.
     */
    private void shareLog() {
        List<File> files = logFiles();
        if (files.isEmpty()) {
            Toast.makeText(this, R.string.no_log, Toast.LENGTH_LONG).show();
            return;
        }

        List<Uri> uris = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        for (File file : files) {
            paths.add(file.getAbsolutePath());
            try {
                uris.add(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file));
            } catch (IllegalArgumentException e) {
                // Not covered by file_paths.xml - still worth reporting below.
            }
        }

        if (uris.isEmpty()) {
            Toast.makeText(this, TextUtils.join("\n", paths), Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent;
        if (uris.size() == 1) {
            intent = new Intent(Intent.ACTION_SEND);
            intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            intent.putExtra(Intent.EXTRA_STREAM, new ArrayList<>(uris));
        }
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "UZDroid log");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share_log)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, TextUtils.join("\n", paths), Toast.LENGTH_LONG).show();
        }
    }

    private void confirmResetConfig() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.reset_config)
                .setMessage(R.string.reset_config_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.reset_config, (dialog, which) -> {
                    deleteRecursive(new File(root, "config/uzdoom"));
                    Toast.makeText(this, R.string.reset_config_done, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    // -----------------------------------------------------------------------
    // Small utilities
    // -----------------------------------------------------------------------

    private void persist() {
        prefs.save(this);
    }

    private void persistFromControls() {
        prefs.scalePercent = chkCustom.isChecked() ? prefs.scalePercent : scalePercent();

        prefs.customWidth = parseInt(etWidth.getText().toString(), prefs.customWidth);
        prefs.customHeight = parseInt(etHeight.getText().toString(), prefs.customHeight);
        prefs.extraArgs = etArgs.getText().toString();

        prefs.save(this);
        updateScaleLabel(prefs.scalePercent);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private int scalePercent() {
        return clampScale(SCALE_MIN + seekScale.getProgress() * SCALE_STEP);
    }

    private static int clampScale(int value) {
        return Math.max(SCALE_MIN, Math.min(SCALE_MAX, value));
    }

    private void updateScaleLabel(int percent) {
        if (percent == LauncherPrefs.SCALE_NATIVE) {
            tvScale.setText(R.string.scale_native);
        } else {
            tvScale.setText(getString(R.string.scale_value, percent));
        }
    }

    private int[] screenSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = getWindowManager().getMaximumWindowMetrics().getBounds();
            return new int[]{bounds.width(), bounds.height()};
        }
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        return new int[]{metrics.widthPixels, metrics.heightPixels};
    }

}
