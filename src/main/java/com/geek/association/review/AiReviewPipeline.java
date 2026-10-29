package com.geek.association.review;

import com.geek.association.report.ImpactReport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The review pipeline: chunk the diff, ask for comments, place them, filter them, reflect on what is
 * left. Deterministic code owns every decision that can be made without understanding the code
 * (which files, how many tokens, where a quoted line lives, which comments duplicate), and the model
 * only does the part that needs semantics.
 *
 * <p>The budgets below exist because a single large prompt degrades in two ways at once: the model
 * starts missing changes in the middle of the diff, and one provider error loses every comment. The
 * pipeline therefore trades a few extra calls for isolation - a failed chunk leaves the other chunks
 * intact, exactly like a failed file does for the static analysis.
 */
public final class AiReviewPipeline {

    /**
     * The review system prompt. It repeats the "quote verbatim" rule because the line number is
     * derived from that quote, so a paraphrase is not a style problem but an unusable comment.
     */
    private static final String SYSTEM_PROMPT = """
            你是资深代码评审者。只依据用户提供的变更片段和事实目录作答，不臆测未提供的代码。
            每条意见必须逐字引用变更片段中真实存在的代码行（existing_code），不要改写、不要补全。
            没有把握时宁可不提意见；不要为了凑数而评论。只输出 JSON，不要输出解释文字。""";

    /** One file above this estimate is not reviewed: its hunks would crowd out every other file. */
    static final int MAX_FILE_TOKENS = 4000;
    /** One request is capped so the model still sees the whole chunk instead of a truncated tail. */
    static final int MAX_CHUNK_TOKENS = 8000;
    static final int MAX_FILES_PER_CHUNK = 6;
    static final int MAX_FILES = 20;
    static final int MAX_CHUNKS = 6;
    /** A chunk that produces more than this is repeating itself; the filter would dedupe most of it. */
    static final int MAX_COMMENTS_PER_CHUNK = 10;
    /** A re-location answer is one snippet, not a review, so it gets a small cap. */
    static final int REVIEW_MAX_TOKENS = 3000;
    static final int RELOCATION_MAX_TOKENS = 800;
    static final int REFLECTION_MAX_TOKENS = 2000;
    /** The reflection prompt is bounded too: its job is a yes/no per comment, not a second review. */
    static final int MAX_REFLECTION_INPUT_TOKENS = 12000;
    /**
     * Six is the article's limit: after a handful of failed re-locations the snippet is more likely
     * to be invented than moved, and every extra call is paid for by the user.
     */
    static final int MAX_RELOCATION_CALLS = 6;

    /**
     * Text files worth reviewing. Everything else (images, jars, lock files) is skipped: a diff of a
     * binary is noise in the prompt and cannot carry a line-anchored comment.
     */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "java", "xml", "yml", "yaml", "properties", "sql", "json", "gradle",
            "kt", "kts", "js", "ts", "tsx", "go", "py", "md");

    /**
     * Paths that are generated, vendored or test fixtures. Reviewing them mostly produces comments
     * the author cannot act on, and those false positives are what destroys trust in the feature.
     */
    private static final List<String> EXCLUDED_PATH_PARTS = List.of(
            "node_modules", "/target/", "/build/", "/dist/", "/vendor/", "/.git/",
            "src/test/", "fixtures", "snapshots", ".min.");

    /** What one review produced: the comments that survived plus the counts the UI explains them with. */
    public static final class Result {
        public final List<ReviewComment> comments = new ArrayList<>();
        public final Map<String, Object> stats = new LinkedHashMap<>();
        /** Non-fatal failure (a chunk call failed); comments from other chunks are still returned. */
        public String error;
    }

    /** One reviewable file before chunking: its parsed hunks, its rank and its token cost. */
    private static final class Candidate {
        final String path;
        final ParsedDiff diff;
        final int changes;
        final int tokens;

        Candidate(String path, ParsedDiff diff, int changes, int tokens) {
            this.path = path;
            this.diff = diff;
            this.changes = changes;
            this.tokens = tokens;
        }
    }

    private final ModelClient client;
    private final ReviewPromptBuilder prompts = new ReviewPromptBuilder();
    private final ReviewFilter filter = new ReviewFilter();
    private final CommentLocator locator = new CommentLocator();

    public AiReviewPipeline(ModelClient client) {
        this.client = client;
    }

    /**
     * Reviews the changed hunks stored in {@code report.diffs}.
     *
     * Never throws: a model or parse failure is recorded in {@link Result#error} and in the stats,
     * because the AI section is an optional extra and one bad chunk must not hide the others.
     */
    public Result review(ImpactReport report, FileContent files, int maxImpacts,
                         int maxComments, boolean reflection) {
        Result result = new Result();
        initStats(result);
        FileContent head = files == null ? FileContent.NONE : files;
        try {
            run(report, head, maxImpacts, maxComments, reflection, result);
        } catch (RuntimeException e) {
            record(result, "评审流水线异常", e);
        }
        result.stats.put("commentsKept", result.comments.size());
        return result;
    }

    private void run(ImpactReport report, FileContent files, int maxImpacts,
                     int maxComments, boolean reflection, Result result) {
        if (report == null || report.diffs == null || report.diffs.isEmpty()) {
            note(result, "没有可评审的变更片段");
            return;
        }

        List<Candidate> candidates = collectCandidates(report, result);
        List<List<Candidate>> chunks = chunk(candidates, result);
        int reviewed = 0;
        for (List<Candidate> chunk : chunks) {
            reviewed += chunk.size();
        }
        result.stats.put("filesReviewed", reviewed);

        // One pass of model calls, then deterministic placement. Keeping generation and placement in
        // separate phases makes the failure budget obvious: a chunk either produced comments or it
        // did not, and placement never depends on how many chunks were sent.
        List<ReviewComment> generated = new ArrayList<>();
        ReviewPromptBuilder.FactCatalog facts = null;
        for (int i = 0; i < chunks.size(); i++) {
            List<Candidate> chunk = chunks.get(i);
            List<ParsedDiff> chunkDiffs = new ArrayList<>();
            for (Candidate candidate : chunk) {
                chunkDiffs.add(candidate.diff);
            }
            ReviewPromptBuilder.FactCatalog chunkFacts;
            try {
                chunkFacts = prompts.facts(report, maxImpacts);
            } catch (RuntimeException e) {
                record(result, "事实目录构建失败", e);
                chunkFacts = new ReviewPromptBuilder.FactCatalog("", Map.of());
            }
            facts = chunkFacts;
            try {
                String prompt = prompts.commentsPrompt(chunkFacts, chunkDiffs);
                addEstimate(result, prompt);
                String answer = client.complete(SYSTEM_PROMPT, prompt, REVIEW_MAX_TOKENS);
                if (answer == null || answer.isBlank()) {
                    record(result, "第 " + (i + 1) + " 个分块模型返回空内容", null);
                    continue;
                }
                List<ReviewComment> parsed = ReviewPromptBuilder.parseComments(answer);
                if (parsed.size() > MAX_COMMENTS_PER_CHUNK) {
                    parsed = new ArrayList<>(parsed.subList(0, MAX_COMMENTS_PER_CHUNK));
                }
                generated.addAll(parsed);
            } catch (Exception e) {
                // Continue with the next chunk: partial comments are strictly better than none, and
                // the first failure is enough to tell the user something was degraded.
                record(result, "第 " + (i + 1) + " 个分块评审失败", e);
            }
        }
        // Each chunk's parser numbers its own comments from c1, so two chunks can both return c1.
        // Reflection removes by id; without this pass a single verdict could delete two comments.
        ensureUniqueIds(generated);
        result.stats.put("commentsGenerated", generated.size());

        Map<String, ParsedDiff> diffs = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            diffs.put(candidate.path, candidate.diff);
        }
        if (facts == null) {
            try {
                facts = prompts.facts(report, maxImpacts);
            } catch (RuntimeException e) {
                record(result, "事实目录构建失败", e);
                facts = new ReviewPromptBuilder.FactCatalog("", Map.of());
            }
        }

        List<ReviewComment> located = new ArrayList<>();
        List<ReviewComment> unlocated = new ArrayList<>();
        for (ReviewComment comment : generated) {
            if (place(comment, diffs, files)) {
                located.add(comment);
            } else {
                unlocated.add(comment);
            }
        }

        int droppedUnlocated = relocateAll(unlocated, located, diffs, files, result);
        result.stats.put("commentsDroppedUnlocated", droppedUnlocated);

        List<ReviewComment> kept;
        try {
            ReviewFilter.Outcome outcome = filter.apply(report, located, diffs,
                    facts.idToFile.keySet(), facts.idToFile, maxComments);
            result.stats.put("commentsDroppedFiltered", outcome.dropped.size());
            result.stats.put("commentsMerged", countMerged(outcome.dropped));
            kept = new ArrayList<>(outcome.kept);
        } catch (RuntimeException e) {
            // A filter bug must not delete every comment: fall back to the un-filtered list and let
            // the caller see the failure in the stats.
            record(result, "确定性过滤失败", e);
            kept = new ArrayList<>(located);
        }

        kept = reflect(kept, diffs, reflection, result);
        kept.sort(commentOrder());
        int cap = maxComments <= 0 ? 10 : maxComments;
        if (kept.size() > cap) {
            kept = new ArrayList<>(kept.subList(0, cap));
        }
        result.comments.addAll(kept);
    }

    /** Selects, ranks and budget-checks the files; skipped files are counted and explained. */
    private List<Candidate> collectCandidates(ImpactReport report, Result result) {
        Map<String, Integer> changeCounts = changeCounts(report);
        List<Candidate> candidates = new ArrayList<>();
        Map<String, Integer> skipReasons = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : report.diffs.entrySet()) {
            String path = entry.getKey();
            String diffText = entry.getValue();
            if (path == null || path.isBlank()) {
                skipReasons.merge("路径为空", 1, Integer::sum);
                continue;
            }
            String reason = skipReason(path, diffText);
            if (reason != null) {
                skipReasons.merge(reason, 1, Integer::sum);
                continue;
            }
            ParsedDiff diff = ParsedDiff.parse(path, diffText);
            if (diff == null || !diff.hasChanges()) {
                skipReasons.merge("无变更行", 1, Integer::sum);
                continue;
            }
            candidates.add(new Candidate(path, diff,
                    changeCounts.getOrDefault(path, 0), TokenEstimator.estimate(diffText)));
        }

        // Files with more changes carry more risk, so they are reviewed first; the path tie-break
        // only exists so the same report always chunks the same way.
        candidates.sort(Comparator
                .comparingInt((Candidate candidate) -> candidate.changes).reversed()
                .thenComparing(candidate -> candidate.path));

        int skipped = sum(skipReasons);
        if (candidates.size() > MAX_FILES) {
            skipped += candidates.size() - MAX_FILES;
            candidates = new ArrayList<>(candidates.subList(0, MAX_FILES));
            note(result, "文件数超过 " + MAX_FILES + " 个，其余未送审");
        }
        result.stats.put("filesSkipped", skipped);
        for (Map.Entry<String, Integer> reason : skipReasons.entrySet()) {
            note(result, "跳过 " + reason.getValue() + " 个文件：" + reason.getKey());
        }
        return candidates;
    }

    /** Packs ranked files into token-bounded chunks; files past the chunk cap are not reviewed. */
    private List<List<Candidate>> chunk(List<Candidate> candidates, Result result) {
        List<List<Candidate>> chunks = new ArrayList<>();
        List<Candidate> current = new ArrayList<>();
        int currentTokens = 0;
        for (Candidate candidate : candidates) {
            boolean full = current.size() >= MAX_FILES_PER_CHUNK;
            boolean overBudget = !current.isEmpty()
                    && currentTokens + candidate.tokens > MAX_CHUNK_TOKENS;
            if (full || overBudget) {
                chunks.add(current);
                current = new ArrayList<>();
                currentTokens = 0;
            }
            current.add(candidate);
            currentTokens += candidate.tokens;
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        if (chunks.size() > MAX_CHUNKS) {
            int unsent = 0;
            for (List<Candidate> extra : chunks.subList(MAX_CHUNKS, chunks.size())) {
                unsent += extra.size();
            }
            chunks = new ArrayList<>(chunks.subList(0, MAX_CHUNKS));
            int previous = (Integer) result.stats.get("filesSkipped");
            result.stats.put("filesSkipped", previous + unsent);
            note(result, "超过 " + MAX_CHUNKS + " 个分块上限，" + unsent + " 个文件未送审");
        }
        return chunks;
    }

    /** Locates one comment; the model's file field decides which diff to search first. */
    private boolean place(ReviewComment comment, Map<String, ParsedDiff> diffs, FileContent files) {
        String code = comment.existingCode;
        if (code == null || code.isBlank()) {
            return false;
        }
        ParsedDiff diff = diffFor(comment.file, diffs);
        if (diff != null) {
            CommentLocator.Result located = locator.locate(diff, code, files);
            if (applied(comment, diff, located)) {
                return true;
            }
        }
        // A model that omitted or mistyped the path is not necessarily wrong about the code, so the
        // remaining files of the report are tried before spending a re-location call.
        if (comment.file == null || comment.file.isBlank()) {
            for (ParsedDiff candidate : diffs.values()) {
                if (candidate == diff) {
                    continue;
                }
                CommentLocator.Result located = locator.locate(candidate, code, files);
                if (applied(comment, candidate, located)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean applied(ReviewComment comment, ParsedDiff diff, CommentLocator.Result located) {
        if (located == null || !located.resolved || located.startLine <= 0) {
            return false;
        }
        comment.file = diff.path;
        comment.side = blank(located.side) ? "NEW" : located.side;
        comment.startLine = located.startLine;
        comment.endLine = Math.max(located.endLine, located.startLine);
        String anchor = blank(located.anchor) || "NONE".equalsIgnoreCase(located.anchor)
                ? "HUNK" : located.anchor;
        comment.anchor = anchor;
        comment.confidence = confidenceFor(anchor);
        comment.status = "KEPT";
        return true;
    }

    /**
     * Gives each unplaced comment one more chance: ask the model to re-quote the snippet, then place
     * that. A comment that still cannot be placed is dropped - a wrong line is worse than no line.
     *
     * @return how many comments remain unplaced and must be discarded
     */
    private int relocateAll(List<ReviewComment> unlocated, List<ReviewComment> located,
                            Map<String, ParsedDiff> diffs, FileContent files, Result result) {
        int calls = 0;
        int dropped = 0;
        for (ReviewComment comment : unlocated) {
            if (calls >= MAX_RELOCATION_CALLS) {
                dropped++;
                continue;
            }
            ParsedDiff diff = diffFor(comment.file, diffs);
            if (diff == null) {
                dropped++;
                continue;
            }
            calls++;
            try {
                String prompt = prompts.relocationPrompt(comment, diff);
                addEstimate(result, prompt);
                String answer = client.complete(SYSTEM_PROMPT, prompt, RELOCATION_MAX_TOKENS);
                String snippet = ReviewPromptBuilder.extractFencedCode(answer);
                if (snippet == null || snippet.isBlank()) {
                    dropped++;
                    continue;
                }
                CommentLocator.Result hit = locator.locate(diff, snippet, files);
                if (hit != null && hit.resolved && hit.startLine > 0) {
                    comment.existingCode = snippet;
                    comment.side = blank(hit.side) ? "NEW" : hit.side;
                    comment.startLine = hit.startLine;
                    comment.endLine = Math.max(hit.endLine, hit.startLine);
                    comment.anchor = "RELOCATED";
                    comment.confidence = confidenceFor("RELOCATED");
                    comment.status = "KEPT";
                    located.add(comment);
                } else {
                    dropped++;
                }
            } catch (Exception e) {
                record(result, "评论重定位失败", e);
                dropped++;
            }
        }
        result.stats.put("relocationCalls", calls);
        return dropped;
    }

    /**
     * One reflection call for the comments that survived the deterministic filter.
     *
     * Only factually wrong comments are removed; a weak or unverifiable comment stays. A failed call
     * or an unparseable answer keeps everything, because "the model did not answer" is not evidence
     * that a comment is wrong.
     */
    private List<ReviewComment> reflect(List<ReviewComment> kept, Map<String, ParsedDiff> diffs,
                                        boolean reflection, Result result) {
        if (!reflection || kept.isEmpty()) {
            return kept;
        }
        try {
            // Severity first, then fit the prompt to the budget. Partial reflection is safe (an
            // unreflected comment stays); an over-long prompt is not (the provider truncates it,
            // and the model then answers about comments it can no longer see).
            List<ReviewComment> toReflect = new ArrayList<>(kept);
            toReflect.sort(commentOrder());
            String prompt = prompts.reflectionPrompt(toReflect, diffs);
            while (TokenEstimator.estimate(prompt) > MAX_REFLECTION_INPUT_TOKENS && toReflect.size() > 1) {
                toReflect = new ArrayList<>(toReflect.subList(0, Math.max(1, toReflect.size() / 2)));
                prompt = prompts.reflectionPrompt(toReflect, diffs);
            }
            if (toReflect.size() < kept.size()) {
                note(result, "反思输入超 token 预算，仅复核前 " + toReflect.size() + " 条");
            }
            addEstimate(result, prompt);
            String answer = client.complete(SYSTEM_PROMPT, prompt, REFLECTION_MAX_TOKENS);
            if (answer == null || answer.isBlank()) {
                record(result, "反思模型返回空内容，保留全部评论", null);
                return kept;
            }
            result.stats.put("reflectionRan", true);
            List<String> removals = ReviewPromptBuilder.parseRemovalIds(answer);
            if (removals.isEmpty()) {
                note(result, "反思没有指出事实错误，保留全部评论");
                return kept;
            }
            Set<String> removalIds = new LinkedHashSet<>(removals);
            List<ReviewComment> remaining = new ArrayList<>();
            int dropped = 0;
            for (ReviewComment comment : kept) {
                if (comment.id != null && removalIds.contains(comment.id)) {
                    dropped++;
                } else {
                    remaining.add(comment);
                }
            }
            result.stats.put("commentsDroppedReflection", dropped);
            return remaining;
        } catch (Exception e) {
            // Never delete because the reflection step failed: that would turn an outage into
            // silently lost findings.
            record(result, "反思步骤失败，保留全部评论", e);
            return kept;
        }
    }

    /** Re-ids comments so one reflection verdict maps to exactly one comment. */
    private static void ensureUniqueIds(List<ReviewComment> comments) {
        Set<String> used = new LinkedHashSet<>();
        int seq = 1;
        for (ReviewComment comment : comments) {
            String id = comment.id == null ? "" : comment.id.strip();
            if (id.isEmpty() || used.contains(id)) {
                do {
                    id = "c" + seq++;
                } while (used.contains(id));
            }
            used.add(id);
            comment.id = id;
        }
    }

    private static Comparator<ReviewComment> commentOrder() {
        return Comparator
                .comparingInt((ReviewComment comment) -> severityRank(comment.severity)).reversed()
                .thenComparing(comment -> comment.file == null ? "" : comment.file)
                .thenComparingInt(comment -> Math.max(comment.startLine, 0));
    }

    private static int severityRank(String severity) {
        if (severity == null) {
            return 0;
        }
        return switch (severity.toUpperCase(Locale.ROOT)) {
            case "BLOCKER" -> 5;
            case "CRITICAL" -> 4;
            case "MAJOR" -> 3;
            case "MINOR" -> 2;
            case "INFO" -> 1;
            default -> 0;
        };
    }

    private static double confidenceFor(String anchor) {
        if ("HUNK".equalsIgnoreCase(anchor)) {
            return 0.95;
        }
        if ("FILE".equalsIgnoreCase(anchor)) {
            return 0.85;
        }
        if ("RELOCATED".equalsIgnoreCase(anchor)) {
            return 0.9;
        }
        return 0.5;
    }

    private static String skipReason(String path, String diffText) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String part : EXCLUDED_PATH_PARTS) {
            if (lower.contains(part)) {
                return "排除路径（" + part + "）";
            }
        }
        // Lock files are generated by package managers and carry no reviewable logic. A bare
        // "lock" substring would also exclude BlockService.java / unlock(), so the name has to be
        // matched as a lock-file suffix or a known package-manager file.
        if (lower.endsWith(".lock") || lower.contains("package-lock") || lower.contains("pnpm-lock")
                || lower.contains("yarn.lock")) {
            return "排除路径（lock 文件）";
        }
        String extension = extension(path);
        if (!TEXT_EXTENSIONS.contains(extension)) {
            return "非评审扩展名（" + (extension.isEmpty() ? "无" : extension) + "）";
        }
        String lowerDiff = diffText == null ? "" : diffText.toLowerCase(Locale.ROOT);
        if (lowerDiff.contains("truncated") || lowerDiff.contains("diff too large")) {
            return "diff 被截断";
        }
        if (TokenEstimator.estimate(diffText) > MAX_FILE_TOKENS) {
            return "单文件 diff 超 token 预算";
        }
        return null;
    }

    private static String extension(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        if (dot <= slash || dot == path.length() - 1) {
            return "";
        }
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Map<String, Integer> changeCounts(ImpactReport report) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (report.changes == null) {
            return counts;
        }
        for (ImpactReport.ChangeView change : report.changes) {
            if (change == null || change.file == null || change.file.isBlank()) {
                continue;
            }
            counts.merge(change.file, 1, Integer::sum);
        }
        return counts;
    }

    private static ParsedDiff diffFor(String file, Map<String, ParsedDiff> diffs) {
        if (file == null || file.isBlank()) {
            return null;
        }
        ParsedDiff exact = diffs.get(file);
        if (exact != null) {
            return exact;
        }
        String normalized = normalizePath(file);
        for (Map.Entry<String, ParsedDiff> entry : diffs.entrySet()) {
            if (normalizePath(entry.getKey()).equals(normalized)) {
                return entry.getValue();
            }
        }
        for (Map.Entry<String, ParsedDiff> entry : diffs.entrySet()) {
            String key = entry.getKey();
            if (key.endsWith("/" + normalized) || normalized.endsWith("/" + key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String normalizePath(String path) {
        String normalized = path.strip();
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.startsWith("a/") || normalized.startsWith("b/")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static int countMerged(List<ReviewComment> dropped) {
        int merged = 0;
        for (ReviewComment comment : dropped) {
            if (comment.filterNote != null
                    && comment.filterNote.toLowerCase(Locale.ROOT).contains("duplicate")) {
                merged++;
            }
        }
        return merged;
    }

    private static void addEstimate(Result result, String prompt) {
        int previous = (Integer) result.stats.get("estimatedTokens");
        result.stats.put("estimatedTokens", previous + TokenEstimator.estimate(prompt));
    }

    private static void initStats(Result result) {
        result.stats.put("filesReviewed", 0);
        result.stats.put("filesSkipped", 0);
        result.stats.put("commentsGenerated", 0);
        result.stats.put("commentsKept", 0);
        result.stats.put("commentsDroppedUnlocated", 0);
        result.stats.put("commentsDroppedFiltered", 0);
        result.stats.put("commentsDroppedReflection", 0);
        result.stats.put("commentsMerged", 0);
        result.stats.put("relocationCalls", 0);
        result.stats.put("reflectionRan", false);
        result.stats.put("estimatedTokens", 0);
        result.stats.put("notes", new ArrayList<String>());
    }

    @SuppressWarnings("unchecked")
    private static void note(Result result, String message) {
        ((List<String>) result.stats.get("notes")).add(message);
    }

    private static void record(Result result, String context, Throwable error) {
        String detail = error == null || blank(error.getMessage())
                ? context : context + "：" + error.getMessage();
        note(result, detail);
        if (result.error == null || result.error.isBlank()) {
            result.error = truncate(detail, 300);
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private static int sum(Map<String, Integer> values) {
        int total = 0;
        for (int value : values.values()) {
            total += value;
        }
        return total;
    }
}
