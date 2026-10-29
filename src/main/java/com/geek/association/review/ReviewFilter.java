package com.geek.association.review;

import com.geek.association.report.ImpactReport;
import com.geek.association.util.Texts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic false-positive filter, applied after localization and before the reflection pass.
 *
 * Everything here is a rule the article calls "deterministic engineering": the file must be one of
 * the files that were actually sent, the cited fact ids must exist, the range must be real, and a
 * near-duplicate of a stronger comment must go. No model call is spent on a check code can do.
 *
 * The output keeps both sides of the decision. {@link Outcome#dropped} is not garbage: the pipeline
 * reports how many comments each rule removed, and a reviewer debugging "why is my comment missing"
 * needs the reason, not just the surviving list.
 */
public final class ReviewFilter {

    /** Past four comments on one file the fifth is noise, whatever its severity says. */
    private static final int PER_FILE_CAP = 4;

    /** Same vocabulary the prompt asks for; a value outside it means the model invented its own. */
    private static final Set<String> SEVERITIES =
            Set.of("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO");

    private static final Set<String> CATEGORIES = Set.of("CORRECTNESS", "SECURITY", "PERFORMANCE",
            "RELIABILITY", "MAINTAINABILITY", "IMPACT", "OTHER");

    /** Higher wins; used for duplicate resolution and for the severity-ordered truncation. */
    private static final Map<String, Integer> SEVERITY_RANK = Map.of(
            "BLOCKER", 5,
            "CRITICAL", 4,
            "MAJOR", 3,
            "MINOR", 2,
            "INFO", 1);

    /**
     * Two titles this similar are the same finding reworded.
     *
     * Similarity is only consulted when the categories also match: a security concern and a
     * performance concern about the same line can legitimately share wording, and collapsing them
     * would hide one of the two dimensions the review is supposed to cover.
     */
    private static final double TITLE_SIMILARITY = 0.6;

    public static final class Outcome {
        public final List<ReviewComment> kept = new ArrayList<>();
        public final List<ReviewComment> dropped = new ArrayList<>();
    }

    public Outcome apply(ImpactReport report, List<ReviewComment> comments,
                         Map<String, ParsedDiff> diffs, Set<String> factIds,
                         Map<String, String> factFiles, int maxComments) {
        Outcome outcome = new Outcome();
        if (comments == null || comments.isEmpty()) {
            return outcome;
        }
        Map<String, ParsedDiff> sent = diffs == null ? Map.of() : diffs;
        Set<String> knownFacts = factIds == null ? Set.of() : factIds;
        Map<String, String> factFileMap = factFiles == null ? Map.of() : factFiles;

        List<ReviewComment> candidates = new ArrayList<>();
        for (ReviewComment comment : comments) {
            if (comment == null) {
                continue;
            }
            String reason = reject(comment, sent.keySet(), knownFacts, factFileMap);
            if (reason == null) {
                comment.status = "KEPT";
                comment.filterNote = null;
                candidates.add(comment);
            } else {
                drop(comment, reason, outcome);
            }
        }
        if (candidates.isEmpty()) {
            return outcome;
        }

        // Dedupe first, caps last: a duplicate must not consume one of the four slots a file is
        // allowed, and a comment cut by the cap must not then be reported as a duplicate.
        List<ReviewComment> current = dedupe(candidates, outcome);
        applyPerFileCap(current, outcome);
        applyGlobalCap(current, maxComments, outcome);

        outcome.kept.addAll(current);
        return outcome;
    }

    /**
     * Returns the first deterministic reason to reject a comment, or {@code null} when it survives.
     *
     * Order is deliberate: the cheapest and most certain checks run first, so the {@code filterNote}
     * names the root cause (a comment on an unsent file is not interesting for also citing a fake
     * fact id). Each rule here is decidable without knowing what the code does.
     */
    private static String reject(ReviewComment comment, Set<String> sentFiles,
                                 Set<String> factIds, Map<String, String> factFiles) {
        String file = comment.file == null ? "" : comment.file.strip();
        if (file.isEmpty() || !sentFiles.contains(file)) {
            return "file-not-sent";
        }
        if (!SEVERITIES.contains(upper(comment.severity))) {
            return "bad-severity";
        }
        if (!CATEGORIES.contains(upper(comment.category))) {
            return "bad-category";
        }
        if (Texts.isBlank(comment.title) || Texts.isBlank(comment.detail)) {
            return "empty-text";
        }
        if (Texts.isBlank(comment.existingCode)) {
            return "empty-existing-code";
        }
        if (comment.startLine <= 0 || comment.endLine < comment.startLine) {
            return "bad-range";
        }
        if (comment.evidence != null && !comment.evidence.isEmpty()) {
            for (String id : comment.evidence) {
                // A blank id is not "no evidence": it is an id the model could not produce, and
                // treating it as absent would let an unverifiable claim through.
                if (id == null || id.isBlank() || !factIds.contains(id.strip())) {
                    return "unknown-evidence";
                }
            }
            boolean sameFile = false;
            for (String id : comment.evidence) {
                if (file.equals(factFiles.get(id == null ? "" : id.strip()))) {
                    sameFile = true;
                    break;
                }
            }
            // The cited fact may exist but describe another file. Keeping such a comment is how the
            // report ends up confidently quoting C1 for a problem proven by something else.
            if (!sameFile) {
                return "cross-file-evidence";
            }
        }
        return null;
    }

    /**
     * Removes near-duplicates, keeping the more severe of each pair.
     *
     * The list is re-scanned after every removal because replacing a weaker comment with a stronger
     * one can create a new duplicate pair; comments per report are capped at 30, so the simplicity
     * of the restart is worth more than the saved comparisons.
     */
    private static List<ReviewComment> dedupe(List<ReviewComment> candidates, Outcome outcome) {
        List<ReviewComment> current = new ArrayList<>(candidates);
        boolean changed = true;
        while (changed) {
            changed = false;
            outer:
            for (int i = 0; i < current.size(); i++) {
                for (int j = 0; j < i; j++) {
                    if (!isDuplicate(current.get(j), current.get(i))) {
                        continue;
                    }
                    ReviewComment earlier = current.get(j);
                    ReviewComment later = current.get(i);
                    if (severityRank(later.severity) > severityRank(earlier.severity)) {
                        drop(earlier, "duplicate", outcome);
                        current.set(j, later);
                        current.remove(i);
                    } else {
                        drop(later, "duplicate", outcome);
                        current.remove(i);
                    }
                    changed = true;
                    break outer;
                }
            }
        }
        return current;
    }

    /** One file's share of the report; the ordering is by severity so a blocker is never cut first. */
    private static void applyPerFileCap(List<ReviewComment> current, Outcome outcome) {
        Map<String, List<ReviewComment>> byFile = new LinkedHashMap<>();
        for (ReviewComment comment : current) {
            byFile.computeIfAbsent(upper(comment.file), key -> new ArrayList<>()).add(comment);
        }
        for (List<ReviewComment> group : byFile.values()) {
            if (group.size() <= PER_FILE_CAP) {
                continue;
            }
            List<ReviewComment> ranked = rankBySeverity(group);
            for (int i = PER_FILE_CAP; i < ranked.size(); i++) {
                current.remove(ranked.get(i));
                drop(ranked.get(i), "cap", outcome);
            }
        }
    }

    /** Global budget, applied after the per-file cap so a single file cannot use it all up first. */
    private static void applyGlobalCap(List<ReviewComment> current, int maxComments, Outcome outcome) {
        int cap = Math.max(0, maxComments);
        if (current.size() <= cap) {
            return;
        }
        List<ReviewComment> ranked = rankBySeverity(current);
        for (int i = cap; i < ranked.size(); i++) {
            current.remove(ranked.get(i));
            drop(ranked.get(i), "cap", outcome);
        }
    }

    private static List<ReviewComment> rankBySeverity(List<ReviewComment> comments) {
        List<ReviewComment> ranked = new ArrayList<>(comments);
        // List.sort is stable, so equal severities keep the model's original order.
        ranked.sort(Comparator.comparingInt((ReviewComment comment) -> severityRank(comment.severity))
                .reversed());
        return ranked;
    }

    private static boolean isDuplicate(ReviewComment a, ReviewComment b) {
        String file = upper(a.file);
        if (!file.equals(upper(b.file))) {
            return false;
        }
        if (Math.max(a.startLine, b.startLine) > Math.min(a.endLine, b.endLine)) {
            return false;
        }
        String titleA = normalizeTitle(a.title);
        String titleB = normalizeTitle(b.title);
        if (titleA.equals(titleB)) {
            return true;
        }
        if (!upper(a.category).equals(upper(b.category))) {
            return false;
        }
        return jaccard(tokens(titleA), tokens(titleB)) >= TITLE_SIMILARITY;
    }

    /** Case, punctuation and spacing are not meaning: two titles differing only in those are equal. */
    private static String normalizeTitle(String title) {
        if (title == null) {
            return "";
        }
        return title.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    /**
     * Tokens for the similarity check, splitting CJK into single characters.
     *
     * Chinese has no spaces, so a whitespace split would make every Chinese title exactly one token
     * and the Jaccard score would only ever be 0 or 1. Per-character tokens are the cheap
     * approximation that still detects "同一句话换个说法", which is what duplicates look like here.
     */
    private static Set<String> tokens(String normalizedTitle) {
        Set<String> out = new LinkedHashSet<>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < normalizedTitle.length(); ) {
            int cp = normalizedTitle.codePointAt(i);
            if (TokenEstimator.isCjk(cp)) {
                flush(word, out);
                out.add(new String(Character.toChars(cp)));
            } else if (Character.isLetterOrDigit(cp)) {
                word.appendCodePoint(cp);
            } else {
                flush(word, out);
            }
            i += Character.charCount(cp);
        }
        flush(word, out);
        return out;
    }

    private static void flush(StringBuilder word, Set<String> out) {
        if (word.length() > 0) {
            out.add(word.toString());
            word.setLength(0);
        }
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new LinkedHashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    private static int severityRank(String severity) {
        return SEVERITY_RANK.getOrDefault(upper(severity), 0);
    }

    private static String upper(String raw) {
        return raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
    }

    private static void drop(ReviewComment comment, String reason, Outcome outcome) {
        comment.status = "DROPPED";
        comment.filterNote = reason;
        outcome.dropped.add(comment);
    }
}
