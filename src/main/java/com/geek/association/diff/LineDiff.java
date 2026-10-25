package com.geek.association.diff;

import java.util.ArrayList;
import java.util.List;

/** Minimal LCS based line diff used only for report rendering. */
public final class LineDiff {

    private LineDiff() {
    }

    public static final class Hunk {
        public int oldStart;
        public int newStart;
        public List<String> lines = new ArrayList<>();
    }

    public static List<Hunk> diff(String oldText, String newText, int maxLines) {
        List<String> a = oldText == null ? List.of() : List.of(oldText.split("\\R", -1));
        List<String> b = newText == null ? List.of() : List.of(newText.split("\\R", -1));
        int n = a.size();
        int m = b.size();
        List<Hunk> hunks = new ArrayList<>();
        if ((long) n * m > 4_000_000L) {
            Hunk hunk = new Hunk();
            hunk.oldStart = 1;
            hunk.newStart = 1;
            hunk.lines.add("@@ diff too large, showing first " + Math.min(maxLines, m) + " lines of new content @@");
            for (int i = 0; i < Math.min(maxLines, m); i++) {
                hunk.lines.add("+" + b.get(i));
            }
            hunks.add(hunk);
            return hunks;
        }
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        Hunk current = null;
        int context = 3;
        List<String> pendingContext = new ArrayList<>();
        while (i < n || j < m) {
            if (i < n && j < m && a.get(i).equals(b.get(j))) {
                if (current != null) {
                    pendingContext.add(" " + a.get(i));
                    if (pendingContext.size() > context * 2) {
                        for (int k = 0; k < context; k++) {
                            current.lines.add(pendingContext.get(k));
                        }
                        hunks.add(current);
                        current = null;
                        pendingContext.clear();
                    }
                }
                i++;
                j++;
            } else if (j < m && (i >= n || lcs[i][j + 1] >= lcs[i + 1][j])) {
                if (current == null) {
                    current = new Hunk();
                    current.oldStart = Math.max(1, i + 1 - Math.min(context, pendingContext.size()));
                    current.newStart = Math.max(1, j + 1 - Math.min(context, pendingContext.size()));
                    current.lines.addAll(pendingContext);
                    pendingContext.clear();
                }
                current.lines.add("+" + b.get(j));
                j++;
            } else {
                if (current == null) {
                    current = new Hunk();
                    current.oldStart = Math.max(1, i + 1 - Math.min(context, pendingContext.size()));
                    current.newStart = Math.max(1, j + 1 - Math.min(context, pendingContext.size()));
                    current.lines.addAll(pendingContext);
                    pendingContext.clear();
                }
                current.lines.add("-" + a.get(i));
                i++;
            }
            if (current != null && current.lines.size() > maxLines) {
                current.lines.add("@@ truncated @@");
                hunks.add(current);
                return hunks;
            }
        }
        if (current != null) {
            for (int k = 0; k < Math.min(context, pendingContext.size()); k++) {
                current.lines.add(pendingContext.get(k));
            }
            hunks.add(current);
        }
        return hunks;
    }

    public static String unified(String path, String oldText, String newText, int maxLines) {
        List<Hunk> hunks = diff(oldText, newText, maxLines);
        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(path).append('\n');
        sb.append("+++ b/").append(path).append('\n');
        for (Hunk h : hunks) {
            sb.append("@@ -").append(h.oldStart).append(" +").append(h.newStart).append(" @@\n");
            for (String line : h.lines) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
