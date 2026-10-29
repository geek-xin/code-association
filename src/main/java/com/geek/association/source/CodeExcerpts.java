package com.geek.association.source;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A small window of source text around one reported evidence position.
 *
 * <p>The review screen already names a {@code file:line} for every contract hit and every hop in
 * an impact path. Without the surrounding lines the reader has to open the repository to judge
 * whether the hit is real, which is the one step this tool exists to avoid.</p>
 *
 * <p>Only the requested window is materialised. Line numbers are 1-based and refer to the version
 * the text came from; the caller decides which revision that is and labels it in the response.</p>
 */
public final class CodeExcerpts {

    /** How many lines above and below the cited line are returned at most. */
    public static final int MAX_CONTEXT = 10;

    /** A single very long line (generated code, minified files) is cut instead of breaking layout. */
    public static final int MAX_LINE_CHARS = 400;

    private CodeExcerpts() {
    }

    /** One numbered source line; {@code truncated} says its text was cut at {@link #MAX_LINE_CHARS}. */
    public record Line(int number, String text, boolean truncated) {
    }

    /**
     * The window plus the facts needed to render it honestly: which line was asked for, the range
     * actually returned, the revision label, and any note explaining a shorter-than-requested range.
     */
    public record Excerpt(String path, int line, int startLine, int endLine,
                          List<Line> lines, String revision, String note) {
    }

    /**
     * Validates a reported file path and returns it as a normalized relative path.
     *
     * <p>Evidence paths come from the report, not from the address bar, but the endpoint is still
     * callable by anyone who can reach the local service. Absolute paths and any {@code ..} that
     * climbs out of the source root are rejected before a file is opened.</p>
     */
    public static String normalizeRelativePath(String raw) {
        String p = raw == null ? "" : raw.replace('\\', '/').trim();
        if (p.isEmpty()) {
            throw new IllegalArgumentException("缺少文件路径");
        }
        if (p.startsWith("/") || p.matches("^[A-Za-z]:/.*")) {
            throw new IllegalArgumentException("文件路径必须是代码源内的相对路径");
        }
        Path normalized = Path.of(p).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new IllegalArgumentException("路径越出了代码源目录");
        }
        return normalized.toString().replace('\\', '/');
    }

    /** Reads the window from a file on disk, streaming rather than loading the whole file. */
    public static Excerpt fromFile(Path file, String path, int line, int context) throws IOException {
        int wanted = Math.max(1, line);
        int ctx = clamp(context);
        int start = Math.max(1, wanted - ctx);
        int end = wanted + ctx;
        List<Line> lines = new ArrayList<>();
        int total = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                total++;
                if (total < start) {
                    continue;
                }
                if (total > end) {
                    break;
                }
                lines.add(trim(total, raw));
            }
        }
        return assemble(path, wanted, start, end, total, lines, null);
    }

    /** Same window from text already held in memory (a file read from a Git tree). */
    public static Excerpt fromText(String path, String text, int line, int context, String revision) {
        int wanted = Math.max(1, line);
        int ctx = clamp(context);
        String[] all = String.valueOf(text == null ? "" : text).split("\n", -1);
        // A trailing newline produces one empty extra element, which is not a line anyone can cite.
        int total = all.length;
        if (total > 0 && all[total - 1].isEmpty()) {
            total--;
        }
        int start = Math.max(1, wanted - ctx);
        int end = wanted + ctx;
        List<Line> lines = new ArrayList<>();
        for (int n = start; n <= end && n <= total; n++) {
            String raw = all[n - 1];
            if (raw.endsWith("\r")) {
                raw = raw.substring(0, raw.length() - 1);
            }
            lines.add(trim(n, raw));
        }
        return assemble(path, wanted, start, end, total, lines, revision);
    }

    private static Excerpt assemble(String path, int line, int start, int end, int total,
                                    List<Line> lines, String revision) {
        String note = null;
        if (line > total) {
            note = total == 0 ? "文件为空" : "文件只有 " + total + " 行，引用行不存在";
        } else if (!lines.isEmpty() && lines.get(lines.size() - 1).number() < end) {
            note = "文件只到第 " + total + " 行";
        }
        int startShown = lines.isEmpty() ? start : lines.get(0).number();
        int endShown = lines.isEmpty() ? start - 1 : lines.get(lines.size() - 1).number();
        return new Excerpt(path, line, startShown, Math.max(startShown - 1, endShown), lines, revision, note);
    }

    private static int clamp(int context) {
        if (context < 0) {
            return 0;
        }
        return Math.min(context, MAX_CONTEXT);
    }

    private static Line trim(int number, String raw) {
        String text = raw == null ? "" : raw;
        if (text.length() <= MAX_LINE_CHARS) {
            return new Line(number, text, false);
        }
        return new Line(number, text.substring(0, MAX_LINE_CHARS), true);
    }
}
