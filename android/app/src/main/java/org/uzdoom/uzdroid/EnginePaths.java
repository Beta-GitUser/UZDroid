/*
** EnginePaths.java
**
** Where the engine keeps its data, how that maps onto Android storage, and the
** environment variables that make the engine (which only knows about POSIX
** paths) put everything in one visible place.
**
** SPDX-License-Identifier: GPL-3.0-or-later
*/

package org.uzdoom.uzdroid;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.system.Os;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class EnginePaths {

    /** Name of the folder that mirrors a desktop UZDoom "installation root". */
    public static final String ROOT_NAME = "UZDroid";

    /** Subfolders created inside the root. */
    private static final String[] SUBDIRS = {
            "config",       // XDG_CONFIG_HOME  -> config/uzdoom/uzdoom.ini
            "data",         // XDG_DATA_HOME    -> data/games/uzdoom (saves, autoloads)
            "cache",        // XDG_CACHE_HOME
            "logs",         // engine console log
            "screenshots",  // XDG_PICTURES_DIR
            "iwads",        // WADs the launcher lists
            "mods",         // files imported through the file picker
            "engine"        // PK3s extracted from the APK assets (UZDOOM_PROGDIR)
    };

    private EnginePaths() {
    }

    // -----------------------------------------------------------------------
    // Storage locations
    // -----------------------------------------------------------------------

    /** /storage/emulated/0/Documents/UZDroid - the "standalone root" mirror. */
    @SuppressWarnings("deprecation")
    public static File preferredRoot() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), ROOT_NAME);
    }

    /** App-private fallback used when all-files access has not been granted. */
    public static File fallbackRoot(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = new File(context.getFilesDir(), "external");
        }
        return new File(base, ROOT_NAME);
    }

    public static boolean hasAllFilesAccess(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * The folder everything is written to. Falls back to app-private storage,
     * which still works but is not visible in a file manager.
     */
    public static File root(Context context) {
        return hasAllFilesAccess(context) ? preferredRoot() : fallbackRoot(context);
    }

    public static boolean ensureLayout(File root) {
        boolean ok = true;
        if (!root.exists()) {
            ok = root.mkdirs();
        }
        for (String sub : SUBDIRS) {
            File dir = new File(root, sub);
            if (!dir.exists()) {
                ok &= dir.mkdirs();
            }
        }
        return ok;
    }

    /** Prompts for the "All files access" special permission (Android 11+). */
    public static void requestAllFilesAccess(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
                return;
            } catch (Exception ignored) {
                // Some OEM builds do not implement the per-app screen; fall through
                // to the global list.
            }
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                return;
            } catch (Exception ignored) {
                // Nothing else we can do from here.
            }
        } else {
            activity.requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE},
                    0x51);
        }
    }

    // -----------------------------------------------------------------------
    // Environment for the engine process
    // -----------------------------------------------------------------------

    /**
     * The engine derives every path from the XDG variables (Unix build), so
     * setting them here is enough to make it use {@code root} for configs,
     * saves, screenshots and logs - no engine changes required.
     */
    public static void applyEnvironment(File root) {
        setEnv("HOME", root.getAbsolutePath());
        setEnv("XDG_CONFIG_HOME", new File(root, "config").getAbsolutePath());
        setEnv("XDG_DATA_HOME", new File(root, "data").getAbsolutePath());
        setEnv("XDG_CACHE_HOME", new File(root, "cache").getAbsolutePath());
        setEnv("XDG_PICTURES_DIR", new File(root, "screenshots").getAbsolutePath());
        setEnv("TMPDIR", new File(root, "cache").getAbsolutePath());

        // Read by i_main.cpp: the folder holding the extracted PK3s.
        setEnv("UZDOOM_PROGDIR", new File(root, "engine").getAbsolutePath());
        // Read by the Android platform layer: console mirror.
        setEnv("UZDOOM_LOG", new File(root, "logs/uzdoom.log").getAbsolutePath());
    }

    private static void setEnv(String name, String value) {
        try {
            Os.setenv(name, value, true);
        } catch (Exception e) {
            android.util.Log.w("UZDroid", "Unable to set " + name + "=" + value, e);
        }
    }

    // -----------------------------------------------------------------------
    // Engine data (PK3s) shipped inside the APK
    // -----------------------------------------------------------------------

    public static File engineDir(File root) {
        return new File(root, "engine");
    }

    /**
     * Copies anything missing from assets/ into {@code root/engine}. Assets
     * cannot be read with plain file APIs, and the engine only understands real
     * paths, so they have to be unpacked once.
     *
     * @return true if something was extracted
     */
    public static boolean ensureEngineData(Context context, File root) throws IOException {
        File target = engineDir(root);
        if (!target.exists() && !target.mkdirs()) {
            throw new IOException("Unable to create " + target);
        }
        return copyAssetTree(context.getAssets(), "", target);
    }

    private static boolean copyAssetTree(AssetManager assets, String path, File target) throws IOException {
        String[] children = assets.list(path);

        if (children == null || children.length == 0) {
            // Leaf entry: it is a file.
            return copyAssetFile(assets, path, target);
        }

        if (!target.isDirectory() && !target.mkdirs() && !target.isDirectory()) {
            throw new IOException("Unable to create " + target);
        }

        boolean copied = false;
        for (String child : children) {
            String childPath = path.isEmpty() ? child : path + "/" + child;
            copied |= copyAssetTree(assets, childPath, new File(target, child));
        }
        return copied;
    }

    private static boolean copyAssetFile(AssetManager assets, String path, File out) throws IOException {
        if (out.isFile() && out.length() > 0) {
            return false;
        }

        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Unable to create " + parent);
        }

        try (InputStream in = assets.open(path);
             OutputStream os = new FileOutputStream(out)) {
            copyStream(in, os);
        }
        return true;
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        out.flush();
    }

    // -----------------------------------------------------------------------
    // Misc helpers used by the launcher UI
    // -----------------------------------------------------------------------

    /** Lists *.wad files that the launcher offers to start. */
    public static List<File> findWads(File... dirs) {
        List<File> result = new ArrayList<>();
        for (File dir : dirs) {
            File[] files = dir == null ? null : dir.listFiles();
            if (files == null) {
                continue;
            }
            Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File file : files) {
                String name = file.getName().toLowerCase();
                if (file.isFile() && (name.endsWith(".wad") || name.endsWith(".iwad"))) {
                    result.add(file);
                }
            }
        }
        return result;
    }
}
