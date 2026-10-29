package com.geek.association.review;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Deterministic comment placement: the model quotes code, this class finds where that code is.
 *
 * Three layers, in the order the Open Code Review article describes:
 *
 * <ol>
 *   <li>match against the new side of the diff hunks (context + added lines);</li>
 *   <li>match against the whole new file, when its content is available;</li>
 *   <li>give up, so the pipeline can ask the model once to re-extract the snippet.</li>
 * </ol>
 *
 * Matching is whitespace-normalised and blank-insensitive, and always requires a contiguous run of
 * lines. No line number ever comes from the model.
 */
public final class CommentLocator {

    /**
     * Longer snippets are cut before matching. A snippet this long is no longer a quotation of a
     * place in the code but a restatement of the file; keeping the first lines still points at the
     * right region, while trying to match all of them would almost always fail on a trailing fence
     * or a line the model paraphrased.
     */
    public static final int MAX_SNIPPET_LINES = 50;

    /** Where a snippet was found, and the line range in the file's NEW side. */
    public static final class Result {
        public boolean resolved;
        public String anchor = "NONE";
        /** NEW or OLD; old-side matches are only used for comments on deleted lines. */
        public String side = "NEW";
        public int startLine;
        public int endLine;
        /** How many contiguous candidates the deepest layer examined saw; 0 when nothing matched. */
        public int candidates;
    }

    /** One non-blank line of a haystack side, with the line number it has in that side. */
    private static final class NormLine {
        final int line;
        final String text;

        NormLine(int line, String text) {
            this.line = line;
            this.text = text;
        }
    }

    /** One contiguous match of the whole snippet: both end points and every matched line number. */
    private static final class Candidate {
        final int startLine;
        final int endLine;
        final int[] lines;

        Candidate(int[] lines) {
            this.lines = lines;
            this.startLine = lines[0];
            this.endLine = lines[lines.length - 1];
        }
    }

    public Result locate(ParsedDiff diff, String existingCode, FileContent files) {
        Result unresolved = new Result();
        if (diff == null || existingCode == null) {
            return unresolved;
        }
        // The model sometimes wraps the quotation in a fence or prefixes it with the diff marker it
        // saw in the prompt. Try the cleaned form first, then the verbatim one, so a cleaning rule
        // that guessed wrong cannot hide a snippet that was already an exact match.
        List<String> cleaned = cleanSnippet(existingCode);
        List<String> raw = rawSnippet(existingCode);
        List<List<String>> variants = new ArrayList<>();
        variants.add(cleaned);
        if (!raw.equals(cleaned)) {
            variants.add(raw);
        }

        int seen = 0;
        // Hunk matching runs across all variants before the whole-file layer: placing inside the
        // change is always preferable to placing inside an unchanged file.
        for (List<String> variant : variants) {
            List<String> needle = needle(variant);
            if (needle.isEmpty()) {
                continue;
            }
            Result hit = locateInHunks(diff, needle);
            if (hit.resolved) {
                return hit;
            }
            seen = Math.max(seen, hit.candidates);
        }
        for (List<String> variant : variants) {
            List<String> needle = needle(variant);
            if (needle.isEmpty()) {
                continue;
            }
            Result hit = locateInFile(diff, needle, files);
            if (hit.resolved) {
                return hit;
            }
            seen = Math.max(seen, hit.candidates);
        }
        // anchor stays NONE and the line numbers stay 0: "not placed" must be visible to the
        // pipeline, which drops the comment rather than guessing.
        unresolved.candidates = seen;
        return unresolved;
    }

    /** New-side, then old-side, contiguous matching inside one hunk, never across hunk boundaries. */
    private Result locateInHunks(ParsedDiff diff, List<String> needle) {
        Result result = new Result();
        List<Candidate> onNew = new ArrayList<>();
        List<Candidate> onOld = new ArrayList<>();
        for (ParsedDiff.Hunk hunk : diff.hunks) {
            collect(sideOf(hunk, true), needle, onNew);
            collect(sideOf(hunk, false), needle, onOld);
        }
        if (!onNew.isEmpty()) {
            result.candidates = onNew.size();
            Candidate chosen = nearest(onNew, diff, true);
            if (chosen == null) {
                // Several equally good spots on the new side: refusing to choose is the whole point
                // of the filter. A comment one line off is worse than no comment.
                return result;
            }
            result.resolved = true;
            result.anchor = "HUNK";
            result.side = "NEW";
            result.startLine = chosen.startLine;
            result.endLine = chosen.endLine;
            return result;
        }
        if (!onOld.isEmpty()) {
            result.candidates = onOld.size();
            Candidate chosen = nearest(onOld, diff, false);
            if (chosen == null) {
                return result;
            }
            result.resolved = true;
            result.anchor = "HUNK";
            result.side = "OLD";
            result.startLine = chosen.startLine;
            result.endLine = chosen.endLine;
        }
        return result;
    }

    /**
     * Second layer: the whole new file. It is only reached when the snippet is not inside any hunk,
     * which usually means the model commented on surrounding code rather than the changed lines.
     */
    private Result locateInFile(ParsedDiff diff, List<String> needle, FileContent files) {
        Result result = new Result();
        if (files == null) {
            return result;
        }
        String content;
        try {
            content = files.read(diff.path);
        } catch (RuntimeException e) {
            // Historical regeneration has no head snapshot; a read failure degrades to "layer two
            // skipped", it must never fail the review run.
            return result;
        }
        if (content == null || content.isBlank()) {
            return result;
        }
        List<NormLine> hay = new ArrayList<>();
        String[] lines = content.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String text = normalize(lines[i]);
            if (!text.isEmpty()) {
                hay.add(new NormLine(i + 1, text));
            }
        }
        List<Candidate> candidates = new ArrayList<>();
        collect(hay, needle, candidates);
        if (candidates.isEmpty()) {
            return result;
        }
        result.candidates = candidates.size();
        Candidate chosen = candidates.size() == 1 ? candidates.get(0) : nearest(candidates, diff, true);
        if (chosen == null) {
            // Several matches at the same distance from the change: this is exactly the ambiguous
            // case the article rejects instead of picking the first one.
            return result;
        }
        result.resolved = true;
        result.anchor = "FILE";
        result.side = "NEW";
        result.startLine = chosen.startLine;
        result.endLine = chosen.endLine;
        return result;
    }

    /**
     * Picks the candidate closest to a changed line; a tie is not a choice, so it returns null.
     * "Closest to a changed line" is a heuristic the article uses: a quotation that shares text
     * with both an added line and a distant identical block is almost certainly about the change.
     */
    private Candidate nearest(List<Candidate> candidates, ParsedDiff diff, boolean newSide) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        List<Integer> changed = changedLines(diff, newSide);
        int best = Integer.MAX_VALUE;
        Candidate chosen = null;
        int ties = 0;
        for (Candidate candidate : candidates) {
            int distance = distance(candidate, changed);
            if (distance < best) {
                best = distance;
                chosen = candidate;
                ties = 1;
            } else if (distance == best) {
                ties++;
            }
        }
        return ties == 1 ? chosen : null;
    }

    /** Lines a comment most likely refers to: added lines on the new side, deleted ones on the old. */
    private List<Integer> changedLines(ParsedDiff diff, boolean newSide) {
        List<Integer> changed = new ArrayList<>();
        List<Integer> all = new ArrayList<>();
        for (ParsedDiff.Hunk hunk : diff.hunks) {
            for (ParsedDiff.Line line : hunk.lines) {
                int number = newSide ? line.newLine : line.oldLine;
                if (number <= 0) {
                    continue;
                }
                boolean isChange = newSide
                        ? line.kind == ParsedDiff.LineKind.ADDED
                        : line.kind == ParsedDiff.LineKind.DELETED;
                if (isChange) {
                    changed.add(number);
                }
                all.add(number);
            }
        }
        // A deletion-only hunk has no added lines; then every line it still shows is a fair
        // reference point. Empty only for an empty diff, where every candidate ties anyway.
        return changed.isEmpty() ? all : changed;
    }

    private static int distance(Candidate candidate, List<Integer> changed) {
        int best = Integer.MAX_VALUE;
        for (int line : candidate.lines) {
            for (int target : changed) {
                best = Math.min(best, Math.abs(line - target));
            }
        }
        return best;
    }

    /** Appends every contiguous occurrence of {@code needle}; overlaps count, ambiguity matters. */
    private static void collect(List<NormLine> hay, List<String> needle, List<Candidate> out) {
        int m = needle.size();
        if (m == 0) {
            return;
        }
        for (int i = 0; i + m <= hay.size(); i++) {
            int j = 0;
            while (j < m && hay.get(i + j).text.equals(needle.get(j))) {
                j++;
            }
            if (j == m) {
                int[] lines = new int[m];
                for (int k = 0; k < m; k++) {
                    lines[k] = hay.get(i + k).line;
                }
                out.add(new Candidate(lines));
            }
        }
    }

    /** One side of a hunk as non-blank normalised lines; blanks are skipped but keep their numbers. */
    private static List<NormLine> sideOf(ParsedDiff.Hunk hunk, boolean newSide) {
        List<NormLine> out = new ArrayList<>();
        for (ParsedDiff.Line line : hunk.lines) {
            boolean usable = newSide
                    ? line.kind != ParsedDiff.LineKind.DELETED && line.newLine > 0
                    : line.kind != ParsedDiff.LineKind.ADDED && line.oldLine > 0;
            if (!usable) {
                continue;
            }
            String text = normalize(line.text);
            if (!text.isEmpty()) {
                out.add(new NormLine(newSide ? line.newLine : line.oldLine, text));
            }
        }
        return out;
    }

    /** The snippet lines after fence/prefix cleaning and edge trimming, still with original text. */
    private static List<String> cleanSnippet(String code) {
        List<String> lines = new ArrayList<>();
        for (String line : code.split("\\R", -1)) {
            String stripped = line.strip();
            // Fences and a quoted "@@" header are prompt furniture, not code.
            if (stripped.startsWith("```") || stripped.startsWith("@@")) {
                continue;
            }
            lines.add(line);
        }
        trimBlankEdges(lines);
        if (mostlyDiffPrefixed(lines)) {
            List<String> stripped = new ArrayList<>();
            for (String line : lines) {
                if (line.isEmpty()) {
                    stripped.add(line);
                    continue;
                }
                char first = line.charAt(0);
                // A stolen " " prefix is harmless (normalisation strips indentation anyway); the
                // "+" / "-" prefixes are the ones that would make an exact quotation miss.
                stripped.add(first == '+' || first == '-' || first == ' ' ? line.substring(1) : line);
            }
            trimBlankEdges(stripped);
            return cap(stripped);
        }
        return cap(lines);
    }

    /** The part of the snippet the model actually wrote, for the fallback attempt. */
    private static List<String> rawSnippet(String code) {
        List<String> lines = new ArrayList<>(Arrays.asList(code.split("\\R", -1)));
        trimBlankEdges(lines);
        return cap(lines);
    }

    /**
     * A model quoting a hunk tends to keep the marker in front of every line. Requiring a strict
     * majority avoids mangling an ordinary snippet whose first line happens to start with "-".
     */
    private static boolean mostlyDiffPrefixed(List<String> lines) {
        int nonBlank = 0;
        int prefixed = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            nonBlank++;
            char first = line.charAt(0);
            if (first == '+' || first == '-' || first == ' ') {
                prefixed++;
            }
        }
        return nonBlank > 0 && prefixed * 2 > nonBlank;
    }

    private static List<String> cap(List<String> lines) {
        if (lines.size() <= MAX_SNIPPET_LINES) {
            return lines;
        }
        return new ArrayList<>(lines.subList(0, MAX_SNIPPET_LINES));
    }

    private static void trimBlankEdges(List<String> lines) {
        while (!lines.isEmpty() && lines.get(0).isBlank()) {
            lines.remove(0);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
    }

    private static List<String> needle(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            String normalised = normalize(line);
            if (!normalised.isEmpty()) {
                out.add(normalised);
            }
        }
        return out;
    }

    /** Normalises one line for comparison: trim, collapse internal whitespace, ignore blank lines. */
    public static String normalize(String line) {
        if (line == null) {
            return "";
        }
        return line.strip().replaceAll("\\s+", " ");
    }
}
