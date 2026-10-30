package com.geek.association.source;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The source under one reported evidence position.
 *
 * <p>The review screen already names a {@code file:line} for every contract hit and every hop in
 * an impact path. Without the surrounding lines the reader has to open the repository to judge
 * whether the hit is real, which is the one step this tool exists to avoid.</p>
 *
 * <p>Two shapes of excerpt:</p>
 * <ul>
 *   <li>{@link #blockFromText} / {@link #blockFromFile}: the whole code block the cited line lives in
 *       (a method body, a type body, an XML element, a SQL statement). This is what the review screen asks for: three
 *       lines around a call site cut the method signature and the closing brace away, so the reader
 *       cannot tell <em>which</em> method the evidence is about. The block is capped at
 *       {@link #MAX_BLOCK_LINES} lines; beyond that the window is cut around the cited line and the
 *       caption says how much was left out.</li>
 *   <li>{@link #fromText} / {@link #fromFile}: a plain {@code context}-line window. Kept as the
 *       explicit {@code mode=window} behaviour and for files without block boundaries.</li>
 * </ul>
 *
 * <p>Line numbers are 1-based and refer to the version the text came from; the caller decides which
 * revision that is and labels it in the response.</p>
 */
public final class CodeExcerpts {

    /** How many lines above and below the cited line a plain window returns at most. */
    public static final int MAX_CONTEXT = 10;

    /** How many lines one block excerpt may occupy before it is cut around the cited line. */
    public static final int MAX_BLOCK_LINES = 160;

    /** Files without block boundaries (.properties, .yml, .sql) fall back to this window radius. */
    public static final int BLOCK_FALLBACK_CONTEXT = MAX_CONTEXT;

    /** A single very long line (generated code, minified files) is cut instead of breaking layout. */
    public static final int MAX_LINE_CHARS = 400;

    private CodeExcerpts() {
    }

    /** One numbered source line; {@code truncated} says its text was cut at {@link #MAX_LINE_CHARS}. */
    public record Line(int number, String text, boolean truncated) {
    }

    /**
     * The excerpt plus the facts needed to render it honestly: which line was asked for, the range
     * actually returned, how long the file is, whether the range is a code block, the revision
     * label, a short caption for the block ("方法 · 第 12–48 行") and any note explaining a
     * shorter-than-requested range.
     */
    public record Excerpt(String path, int line, int startLine, int endLine, int totalLines,
                          boolean block, String blockLabel, int blockStart, int blockEnd,
                          List<Line> lines, String revision, String caption, String note) {

        /** Same excerpt with another revision label and note; used when the endpoint falls back to the working tree. */
        public Excerpt relabel(String newRevision, String newNote) {
            return new Excerpt(path, line, startLine, endLine, totalLines, block, blockLabel,
                    blockStart, blockEnd, lines, newRevision, caption, newNote);
        }
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

    // ------------------------------------------------------------------ window

    /** Reads a plain window from a file on disk, streaming rather than loading the whole file. */
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
        return assemble(path, wanted, start, end, total, lines, null, null, false, null, 0, 0);
    }

    /** Same window from text already held in memory (a file read from a Git tree). */
    public static Excerpt fromText(String path, String text, int line, int context, String revision) {
        String[] all = split(text);
        return window(path, all, Math.max(1, line), clamp(context), revision, null);
    }

    // ------------------------------------------------------------------- block

    /**
     * The whole code block the cited line lives in, read from a file on disk.
     *
     * <p>Block detection needs the whole file (a closing brace can be a thousand lines below the
     * cited line), so unlike {@link #fromFile} this reads the file into memory. The endpoint only
     * opens files this analysis already indexed, which are bounded by {@code max-file-mb}.</p>
     */
    public static Excerpt blockFromFile(Path file, String path, int line) throws IOException {
        return blockFromLines(path, Files.readAllLines(file, StandardCharsets.UTF_8).toArray(new String[0]),
                Math.max(1, line), null);
    }

    /** The whole code block the cited line lives in, from text already held in memory. */
    public static Excerpt blockFromText(String path, String text, int line, String revision) {
        return blockFromLines(path, split(text), Math.max(1, line), revision);
    }

    private static Excerpt blockFromLines(String path, String[] all, int wanted, String revision) {
        int total = all.length;
        if (total == 0 || wanted > total) {
            return assemble(path, wanted, wanted, wanted - 1, total, List.of(), revision, null,
                    false, null, 0, 0);
        }
        CodeBlocks.Block block = CodeBlocks.enclosing(path, all, wanted, MAX_BLOCK_LINES);
        if (block == null) {
            // .properties / .yml / .sql: no braces, no tags. Say so instead of pretending the
            // 21-line window is the whole statement.
            return window(path, all, wanted, BLOCK_FALLBACK_CONTEXT, revision,
                    "引用行上下 " + BLOCK_FALLBACK_CONTEXT + " 行");
        }
        int start = block.start();
        int end = Math.min(block.end(), total);
        String caption;
        if (end - start + 1 > MAX_BLOCK_LINES) {
            int half = MAX_BLOCK_LINES / 2;
            start = Math.max(block.start(), wanted - half);
            end = Math.min(block.end(), start + MAX_BLOCK_LINES - 1);
            start = Math.max(block.start(), end - MAX_BLOCK_LINES + 1);
            end = Math.min(end, total);
            caption = block.label() + " · 共 " + block.size() + " 行，展示" + range(start, end);
        } else {
            caption = block.label() + " · " + range(start, end);
        }
        List<Line> lines = new ArrayList<>();
        for (int n = start; n <= end && n <= total; n++) {
            lines.add(trim(n, all[n - 1]));
        }
        return assemble(path, wanted, start, end, total, lines, revision, caption,
                true, block.label(), block.start(), block.end());
    }

    private static Excerpt window(String path, String[] all, int wanted, int context, String revision,
                                  String caption) {
        int total = all.length;
        int start = Math.max(1, wanted - context);
        int end = wanted + context;
        List<Line> lines = new ArrayList<>();
        for (int n = start; n <= end && n <= total; n++) {
            lines.add(trim(n, all[n - 1]));
        }
        return assemble(path, wanted, start, end, total, lines, revision, caption, false, null, 0, 0);
    }

    private static Excerpt assemble(String path, int line, int start, int end, int total,
                                    List<Line> lines, String revision, String caption,
                                    boolean block, String blockLabel, int blockStart, int blockEnd) {
        return assemble(path, line, start, end, total, lines, revision, caption,
                block, blockLabel, blockStart, blockEnd, noteFor(line, start, end, total, lines));
    }

    private static Excerpt assemble(String path, int line, int start, int end, int total,
                                    List<Line> lines, String revision, String caption,
                                    boolean block, String blockLabel, int blockStart, int blockEnd,
                                    String note) {
        int startShown = lines.isEmpty() ? 0 : lines.get(0).number();
        int endShown = lines.isEmpty() ? 0 : lines.get(lines.size() - 1).number();
        return new Excerpt(path, line, startShown, endShown, total, block, blockLabel,
                blockStart, blockEnd, lines, revision, caption, note);
    }

    /** "第 12–48 行"; a one-line block is "第 12 行", not "第 12–12 行". */
    private static String range(int start, int end) {
        return start == end ? "第 " + start + " 行" : "第 " + start + "–" + end + " 行";
    }

    /** Why the returned range is shorter than asked for, or null when it is exactly the window. */
    private static String noteFor(int line, int start, int end, int total, List<Line> lines) {
        if (total == 0) {
            return "文件为空";
        }
        if (line > total) {
            return "文件只有 " + total + " 行，引用行不存在";
        }
        if (!lines.isEmpty() && lines.get(lines.size() - 1).number() < end) {
            return "文件只到第 " + total + " 行";
        }
        return null;
    }

    private static int clamp(int context) {
        if (context < 0) {
            return 0;
        }
        return Math.min(context, MAX_CONTEXT);
    }

    /** Split into lines, dropping CR and the empty element a trailing newline produces. */
    private static String[] split(String text) {
        String[] all = String.valueOf(text == null ? "" : text).split("\n", -1);
        for (int i = 0; i < all.length; i++) {
            if (all[i].endsWith("\r")) {
                all[i] = all[i].substring(0, all[i].length() - 1);
            }
        }
        int total = all.length;
        if (total > 0 && all[total - 1].isEmpty()) {
            total--;
        }
        return Arrays.copyOf(all, total);
    }

    private static Line trim(int number, String raw) {
        String text = raw == null ? "" : raw;
        if (text.length() <= MAX_LINE_CHARS) {
            return new Line(number, text, false);
        }
        return new Line(number, text.substring(0, MAX_LINE_CHARS), true);
    }
}
