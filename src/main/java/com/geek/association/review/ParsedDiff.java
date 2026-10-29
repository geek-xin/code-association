package com.geek.association.review;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One file's unified diff, parsed into hunks with old/new line numbers.
 *
 * This is the same shape Git prints, because that is what {@code report.diffs} stores. The parser is
 * deliberately tolerant: it accepts missing line counts, the "\\ No newline at end of file" marker
 * and the truncation marker the report writer emits for very large diffs.
 */
public final class ParsedDiff {

    /**
     * {@code @@ -oldStart[,oldCount] +newStart[,newCount] @@ [section heading]}.
     *
     * The counts are optional because the report writer emits {@code @@ -12 +40 @@}: it renumbers from
     * its own LCS and never prints them. Requiring the counts would make most stored diffs
     * unparsable, and an unparsable diff means every comment for that file is dropped.
     */
    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    public enum LineKind { CONTEXT, ADDED, DELETED }

    /** One physical diff line; exactly one of {@link #oldLine} / {@link #newLine} is 0 for +/- lines. */
    public static final class Line {
        public final LineKind kind;
        public final int oldLine;
        public final int newLine;
        public final String text;

        public Line(LineKind kind, int oldLine, int newLine, String text) {
            this.kind = kind;
            this.oldLine = oldLine;
            this.newLine = newLine;
            this.text = text;
        }
    }

    public static final class Hunk {
        public final int oldStart;
        public final int newStart;
        public final List<Line> lines = new ArrayList<>();

        public Hunk(int oldStart, int newStart) {
            this.oldStart = oldStart;
            this.newStart = newStart;
        }
    }

    public final String path;
    public final List<Hunk> hunks = new ArrayList<>();

    /**
     * Set as soon as the writer cut the hunk short. The line numbering after that point is unknown -
     * the "truncated" marker usually replaces the rest of the file, not just one line of it - so any
     * comment placed there would point at code the reviewer never saw.
     */
    private boolean truncatedByWriter;

    public ParsedDiff(String path) {
        this.path = path;
    }

    /**
     * Parses one unified diff. A malformed header stops the parse rather than throwing: the review
     * pipeline must degrade to "this file could not be reviewed", never fail the analysis.
     *
     * A bad line only ends the current hunk, not the whole parse, so a damaged header in the middle
     * of a multi-hunk diff still leaves the earlier hunks usable.
     */
    public static ParsedDiff parse(String path, String unifiedDiff) {
        ParsedDiff diff = new ParsedDiff(path);
        if (unifiedDiff == null || unifiedDiff.isEmpty()) {
            return diff;
        }
        Hunk hunk = null;
        int oldLine = 0;
        int newLine = 0;
        String[] lines = unifiedDiff.split("\\R", -1);
        // unified() terminates every line, so the last token is the empty text after the final
        // newline, not a line. Counting it as a blank context line would append a phantom line at
        // the end of every stored diff.
        int end = lines.length;
        if (end > 0 && lines[end - 1].isEmpty()) {
            end--;
        }
        for (int i = 0; i < end; i++) {
            String line = lines[i];
            if (line.isEmpty() && hunk == null) {
                // An empty line between hunks carries no numbering.
                continue;
            }
            if (line.startsWith("@@")) {
                Matcher header = HUNK_HEADER.matcher(line);
                if (header.matches()) {
                    hunk = new Hunk(Integer.parseInt(header.group(1)), Integer.parseInt(header.group(3)));
                    diff.hunks.add(hunk);
                    oldLine = hunk.oldStart;
                    newLine = hunk.newStart;
                } else {
                    // "@@ truncated @@" / "@@ diff too large ... @@" (or garbage): the rest of this
                    // hunk was cut by the writer, so stop trusting its numbering until the next
                    // well-formed header.
                    if (hunk != null && (line.contains("truncated") || line.contains("diff too large"))) {
                        diff.truncatedByWriter = true;
                    }
                    hunk = null;
                }
                continue;
            }
            if (line.startsWith("\\")) {
                // "\\ No newline at end of file": metadata about the previous line, not a line.
                continue;
            }
            if (hunk == null) {
                // File headers ("--- a/x", "+++ b/x", "diff --git", "index ..."): the caller already
                // knows the path, and none of these advance a line counter.
                continue;
            }
            if (line.isEmpty()) {
                // A context line is " text"; a writer that trims trailing whitespace turns it into
                // "". Treating it as a blank context line keeps the counters aligned with the file.
                hunk.lines.add(new Line(LineKind.CONTEXT, oldLine++, newLine++, ""));
                continue;
            }
            String text = line.substring(1);
            switch (line.charAt(0)) {
                case ' ' -> hunk.lines.add(new Line(LineKind.CONTEXT, oldLine++, newLine++, text));
                case '+' -> hunk.lines.add(new Line(LineKind.ADDED, 0, newLine++, text));
                case '-' -> hunk.lines.add(new Line(LineKind.DELETED, oldLine++, 0, text));
                default -> {
                    // Neither a prefix nor a header: most likely a stray blank/metadata line. Ignore
                    // it rather than advance either side, because a guessed advance shifts every
                    // following comment by one line.
                }
            }
        }
        return diff;
    }

    public boolean hasChanges() {
        for (Hunk hunk : hunks) {
            for (Line line : hunk.lines) {
                if (line.kind != LineKind.CONTEXT) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<Line> newSide() {
        List<Line> out = new ArrayList<>();
        for (Hunk hunk : hunks) {
            for (Line line : hunk.lines) {
                if (line.kind != LineKind.DELETED) {
                    out.add(line);
                }
            }
        }
        return out;
    }

    public List<Line> oldSide() {
        List<Line> out = new ArrayList<>();
        for (Hunk hunk : hunks) {
            for (Line line : hunk.lines) {
                if (line.kind != LineKind.ADDED) {
                    out.add(line);
                }
            }
        }
        return out;
    }

    public boolean truncated() {
        if (truncatedByWriter) {
            return true;
        }
        // Kept for diffs built by hand (tests, old stored reports) that carry the marker as a line.
        for (Hunk hunk : hunks) {
            for (Line line : hunk.lines) {
                if (line.text != null && line.text.contains("truncated")) {
                    return true;
                }
            }
        }
        return false;
    }
}
