/*
** LaunchConfig.java
**
** Turns a picked file into command line arguments. Two things can be picked:
**
**   - a launch config (any text file whose lines are engine arguments, with
**     '#' or ';' comments), which is inlined, and
**   - anything else (wad, pk3, deh, ...), which becomes "-file <path>".
**
** Deliberately no extension filtering: the picker accepts any file and the
** content decides what happens, which is what makes this usable for the odd
** things people actually load.
**
** SPDX-License-Identifier: GPL-3.0-or-later
*/

package org.uzdoom.uzdroid;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class LaunchConfig {

    /** Only this much of a file is looked at to decide what it is. */
    private static final int SNIFF_LIMIT = 64 * 1024;

    private LaunchConfig() {
    }

    /** Result of sniffing: either a config's arguments or a plain filename. */
    public static final class Sniffed {
        public final boolean config;
        public final String args;

        Sniffed(boolean config, String args) {
            this.config = config;
            this.args = args;
        }
    }

    /**
     * Reads up to {@link #SNIFF_LIMIT} bytes and decides what the file is.
     * Never throws: an unreadable file simply comes back as "not a config".
     */
    public static Sniffed sniff(InputStream in) {
        byte[] head;
        try {
            head = readLimited(in);
        } catch (IOException e) {
            return new Sniffed(false, "");
        }
        if (head == null) {
            return new Sniffed(false, "");
        }
        return classify(head);
    }

    static Sniffed classify(byte[] head) {
        // A binary file (wad, pk3 - all zips start with "PK", wads have form
        // bytes) is never a config.
        for (byte b : head) {
            if (b == 0) {
                return new Sniffed(false, "");
            }
        }

        String text = new String(head, StandardCharsets.UTF_8);
        String args = parse(text);
        // A config is only recognised as such when it actually carries engine
        // arguments; otherwise a stray text file would silently do nothing.
        return new Sniffed(!args.isEmpty(), args);
    }

    /**
     * Collects argument lines: blanks, '#' and ';' comments are dropped, one
     * line is one argument block (quotes are honoured by the caller's
     * tokenizer). This is the same shape as a plain .txt list of arguments,
     * which is the point.
     */
    static String parse(String text) {
        StringBuilder out = new StringBuilder();
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")
                    || line.startsWith("//")) {
                continue;
            }
            // Only lines that look like arguments count; anything else (a
            // cfg's "key = value", a readme) means this is not a launch config.
            if (!line.startsWith("-") && !line.startsWith("+")) {
                return "";
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(line);
        }
        return out.toString();
    }

    /**
     * Appends a file to an existing argument line. Keeps a trailing "-file" (or
     * similar) from being duplicated, because the engine expects
     * "-file a.wad b.pk3".
     */
    public static String appendFile(String existing, String path) {
        String quoted = LaunchArgs.quote(path);
        String current = existing == null ? "" : existing.trim();
        if (current.isEmpty()) {
            return "-file " + quoted;
        }

        java.util.List<String> tokens = LaunchArgs.tokenize(current);
        String last = tokens.isEmpty() ? "" : tokens.get(tokens.size() - 1).toLowerCase();
        if (last.equals("-file") || last.equals("-iwad") || last.equals("-deh")
                || last.equals("-bex") || last.equals("-warp") || last.equals("-turbo")) {
            return current + " " + quoted;
        }
        return current + " -file " + quoted;
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8 * 1024];
        int total = 0;
        int read;
        while (total < SNIFF_LIMIT && (read = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
            total += read;
        }
        return buffer.size() == 0 ? null : buffer.toByteArray();
    }
}
