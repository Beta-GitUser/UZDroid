/*
** CrashLog.java
**
** Crash and start-up bookkeeping for the rare case where the app dies without
** writing anything: the engine creates its log as the first thing main() does,
** so "no log file at all" means the failure happened before the engine started.
** This keeps a journal in the app's private folder (always writable, no
** permission needed) and copies it to Documents/UZDroid/logs when that is
** reachable, so it can be shared from the launcher.
**
** SPDX-License-Identifier: GPL-3.0-or-later
*/

package org.uzdoom.uzdroid;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CrashLog {

    private static final String TAG = "UZDroid";
    private static final String JOURNAL = "launcher.log";

    private CrashLog() {
    }

    /** Journal that always exists, with or without storage permission. */
    public static File journal(Context context) {
        return new File(context.getFilesDir(), JOURNAL);
    }

    /** The same journal in the visible data folder, when it can be written. */
    public static File publicJournal(Context context) {
        return new File(EnginePaths.root(context), "logs/" + JOURNAL);
    }

    public static void install(Context context) {
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            String stack = describe(throwable);
            append(app, "FATAL in thread '" + thread.getName() + "'\n" + stack);
            writeCrashFile(app, stack);
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    /** One line of history, with a timestamp. Never throws. */
    public static void append(Context context, String message) {
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                + "  " + message + "\n";

        Log.i(TAG, message);
        write(journal(context), line);

        if (EnginePaths.hasAllFilesAccess(context)) {
            write(publicJournal(context), line);
        }
    }

    private static void writeCrashFile(Context context, String stack) {
        String name = "crash-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + ".txt";

        write(new File(context.getFilesDir(), name), stack);

        if (EnginePaths.hasAllFilesAccess(context)) {
            File dir = new File(EnginePaths.root(context), "logs");
            if (dir.isDirectory() || dir.mkdirs()) {
                write(new File(dir, name), stack);
            }
        }
    }

    private static void write(File file, String text) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return;
            }
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(file, true), "UTF-8")) {
                writer.write(text);
                writer.flush();
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Unable to write " + file, e);
        }
    }

    private static String describe(Throwable throwable) {
        StringWriter buffer = new StringWriter();
        PrintWriter printer = new PrintWriter(buffer);
        throwable.printStackTrace(printer);
        printer.flush();
        return buffer.toString();
    }
}
