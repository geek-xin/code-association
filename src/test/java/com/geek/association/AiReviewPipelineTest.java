package com.geek.association;

import com.geek.association.report.ImpactReport;
import com.geek.association.review.AiReviewPipeline;
import com.geek.association.review.FileContent;
import com.geek.association.review.ModelClient;
import com.geek.association.review.ReviewComment;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pipeline-level guarantees: chunking, placement, re-location, reflection and the promise that a
 * model failure degrades instead of throwing. The model is a lambda, so every case is deterministic
 * and offline.
 */
class AiReviewPipelineTest {

    @Test
    void twoChunksAreMergedInRankedOrder() {
        LinkedHashMap<String, String> diffs = new LinkedHashMap<>();
        for (int i = 1; i <= 7; i++) {
            diffs.put("f" + i + ".java", diff("f" + i + ".java", "int value" + i + " = " + i + ";"));
        }
        FakeClient client = new FakeClient(
                comments(comment("c1", "MAJOR", "f1.java", "int value1 = 1;")),
                comments(comment("c2", "MINOR", "f7.java", "int value7 = 7;")));

        AiReviewPipeline.Result result = new AiReviewPipeline(client)
                .review(report(diffs), FileContent.NONE, 15, 10, false);

        assertEquals(2, client.prompts.size(), "seven files must split into two review calls");
        assertEquals(2, result.comments.size());
        assertEquals("f1.java", result.comments.get(0).file);
        assertEquals("f7.java", result.comments.get(1).file);
        assertEquals(7, result.stats.get("filesReviewed"));
        assertEquals(2, result.stats.get("commentsGenerated"));
        assertEquals(2, result.stats.get("commentsKept"));
        assertTrue((Integer) result.stats.get("estimatedTokens") > 0);
        assertEquals(null, result.error);
    }

    @Test
    void commentIsAnchoredToTheAddedHunkLine() {
        String diff = diff("a.java", "int total = a + b;", "return total;");
        FakeClient client = new FakeClient(
                comments(comment("c1", "MAJOR", "a.java", "return total;")));

        AiReviewPipeline.Result result = new AiReviewPipeline(client)
                .review(report(Map.of("a.java", diff)), FileContent.NONE, 15, 10, false);

        assertEquals(1, result.comments.size());
        ReviewComment comment = result.comments.get(0);
        assertEquals("HUNK", comment.anchor);
        assertEquals("NEW", comment.side);
        assertEquals(4, comment.startLine, "the added line is the fourth new-side line");
        assertEquals(4, comment.endLine);
        assertEquals(0.95, comment.confidence, 0.0001);
    }

    @Test
    void unplacedCommentIsRelocatedFromAFencedSnippet() {
        String diff = diff("a.java", "int total = a + b;");
        FakeClient client = new FakeClient(
                comments(comment("c1", "CRITICAL", "a.java", "return fromNowhere();")),
                "```java\nint total = a + b;\n```");

        AiReviewPipeline.Result result = new AiReviewPipeline(client)
                .review(report(Map.of("a.java", diff)), FileContent.NONE, 15, 10, false);

        assertEquals(2, client.prompts.size(), "one review call plus one re-location call");
        assertEquals(1, result.comments.size());
        ReviewComment comment = result.comments.get(0);
        assertEquals("RELOCATED", comment.anchor);
        assertEquals(3, comment.startLine);
        assertEquals(0.9, comment.confidence, 0.0001);
        assertEquals(1, result.stats.get("relocationCalls"));
        assertEquals(0, result.stats.get("commentsDroppedUnlocated"));
    }

    @Test
    void commentStillUnplacedAfterRelocationIsDropped() {
        String diff = diff("a.java", "int total = a + b;");
        FakeClient client = new FakeClient(
                comments(comment("c1", "MAJOR", "a.java", "return fromNowhere();")),
                "```java\nint alsoMissing();\n```");

        AiReviewPipeline.Result result = new AiReviewPipeline(client)
                .review(report(Map.of("a.java", diff)), FileContent.NONE, 15, 10, false);

        assertEquals(0, result.comments.size());
        assertEquals(1, result.stats.get("commentsDroppedUnlocated"));
        assertEquals(1, result.stats.get("relocationCalls"));
        assertEquals(0, result.stats.get("commentsKept"));
    }

    @Test
    void reflectionRemovesOnlyTheCitedComment() {
        String diff = diff("a.java", "int first = 1;", "int second = 2;");
        FakeClient client = new FakeClient(
                comments(
                        comment("c1", "MAJOR", "a.java", "int first = 1;"),
                        comment("c2", "MINOR", "a.java", "int second = 2;")),
                removal("c1"));

        AiReviewPipeline.Result result = new AiReviewPipeline(client)
                .review(report(Map.of("a.java", diff)), FileContent.NONE, 15, 10, true);

        assertEquals(1, result.comments.size());
        assertEquals("c2", result.comments.get(0).id);
        assertEquals(1, result.stats.get("commentsDroppedReflection"));
        assertEquals(true, result.stats.get("reflectionRan"));
    }

    @Test
    void unparseableReflectionKeepsEveryComment() {
        String diff = diff("a.java", "int first = 1;", "int second = 2;");
        FakeClient client = new FakeClient(
                comments(
                        comment("c1", "MAJOR", "a.java", "int first = 1;"),
                        comment("c2", "MINOR", "a.java", "int second = 2;")),
                "抱歉，这段回答不是 JSON。");

        AiReviewPipeline.Result result = new AiReviewPipeline(client)
                .review(report(Map.of("a.java", diff)), FileContent.NONE, 15, 10, true);

        assertEquals(2, result.comments.size(), "a reflection failure must never delete comments");
        assertEquals(0, result.stats.get("commentsDroppedReflection"));
    }

    @Test
    void oversizedFileIsSkippedAndCounted() {
        LinkedHashMap<String, String> diffs = new LinkedHashMap<>();
        diffs.put("Big.java", bigDiff());
        diffs.put("small.java", diff("small.java", "int safe = 1;"));
        FakeClient client = new FakeClient(
                comments(comment("c1", "MAJOR", "small.java", "int safe = 1;")));

        AiReviewPipeline.Result result = new AiReviewPipeline(client)
                .review(report(diffs), FileContent.NONE, 15, 10, false);

        assertEquals(1, client.prompts.size(), "the oversized diff must not reach the model");
        assertEquals(1, result.stats.get("filesReviewed"));
        assertTrue((Integer) result.stats.get("filesSkipped") >= 1);
        assertEquals(1, result.comments.size());
        assertTrue(notes(result).stream().anyMatch(note -> note.contains("token")),
                "the stats must explain why a file was skipped: " + notes(result));
    }

    @Test
    void modelFailureIsReportedInsteadOfThrown() {
        FakeClient client = new FakeClient(new RuntimeException("boom"));

        AiReviewPipeline.Result result = new AiReviewPipeline(client)
                .review(report(Map.of("a.java", diff("a.java", "int x = 1;"))),
                        FileContent.NONE, 15, 10, false);

        assertEquals(0, result.comments.size());
        assertNotNull(result.error);
        assertTrue(result.error.contains("boom"), result.error);
        assertFalse(notes(result).isEmpty());
        assertEquals(0, result.stats.get("commentsKept"));
    }

    @Test
    void statsHaveTheFixedJsonFriendlyKeys() {
        FakeClient client = new FakeClient(comments());

        AiReviewPipeline.Result result = new AiReviewPipeline(client)
                .review(report(Map.of("a.java", diff("a.java", "int x = 1;"))),
                        FileContent.NONE, 15, 10, false);

        assertEquals(Set.of("filesReviewed", "filesSkipped", "commentsGenerated", "commentsKept",
                        "commentsDroppedUnlocated", "commentsDroppedFiltered",
                        "commentsDroppedReflection", "commentsMerged", "relocationCalls",
                        "reflectionRan", "estimatedTokens", "notes"),
                result.stats.keySet());
        for (Map.Entry<String, Object> entry : result.stats.entrySet()) {
            String key = entry.getKey();
            if ("notes".equals(key)) {
                assertTrue(entry.getValue() instanceof List<?>, key);
            } else if ("reflectionRan".equals(key)) {
                assertTrue(entry.getValue() instanceof Boolean, key);
            } else {
                assertTrue(entry.getValue() instanceof Integer, key + " must be an int");
            }
        }
    }

    @Test
    void commentsAreSortedBySeverityAndTruncated() {
        String diff = diff("a.java", "int first = 1;", "int second = 2;", "int third = 3;");
        FakeClient client = new FakeClient(comments(
                comment("c1", "INFO", "a.java", "int first = 1;"),
                comment("c2", "BLOCKER", "a.java", "int second = 2;"),
                comment("c3", "MAJOR", "a.java", "int third = 3;")));

        AiReviewPipeline.Result result = new AiReviewPipeline(client)
                .review(report(Map.of("a.java", diff)), FileContent.NONE, 15, 2, false);

        assertEquals(2, result.comments.size());
        assertEquals("c2", result.comments.get(0).id);
        assertEquals("c3", result.comments.get(1).id);
    }

    // ------------------------------------------------------------------ fixtures

    private static List<String> notes(AiReviewPipeline.Result result) {
        Object notes = result.stats.get("notes");
        assertTrue(notes instanceof List<?>, "notes must be a JSON-friendly list");
        List<String> out = new ArrayList<>();
        for (Object note : (List<?>) notes) {
            out.add(String.valueOf(note));
        }
        return out;
    }

    private static ImpactReport report(Map<String, String> diffs) {
        ImpactReport report = new ImpactReport();
        report.diffs.putAll(diffs);
        return report;
    }

    /** A unified diff with one hunk: two context lines, the given added lines, one trailing context. */
    private static String diff(String path, String... added) {
        StringBuilder out = new StringBuilder();
        out.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
        out.append("index 1111111..2222222 100644\n");
        out.append("--- a/").append(path).append('\n');
        out.append("+++ b/").append(path).append('\n');
        out.append("@@ -1,3 +1,").append(3 + added.length).append(" @@\n");
        out.append(" package demo;\n");
        out.append(" \n");
        for (String line : added) {
            out.append('+').append(line).append('\n');
        }
        out.append(" int keep = 0;\n");
        return out.toString();
    }

    private static String bigDiff() {
        StringBuilder added = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            added.append("private static final String CONSTANT_").append(i)
                    .append(" = \"some long configuration value used only to inflate the diff\";")
                    .append('\n');
        }
        String[] lines = added.toString().split("\n");
        return diff("Big.java", lines);
    }

    private static String comments(String... comments) {
        return "{\"comments\":[" + String.join(",", comments) + "]}";
    }

    private static String comment(String id, String severity, String file, String code) {
        return "{"
                + "\"id\":" + quote(id) + ","
                + "\"severity\":" + quote(severity) + ","
                + "\"category\":\"CORRECTNESS\","
                + "\"title\":" + quote(severity.toLowerCase() + " issue " + id) + ","
                + "\"detail\":" + quote("detail for " + id) + ","
                + "\"suggestion\":" + quote("fix " + id) + ","
                + "\"file\":" + quote(file) + ","
                + "\"existing_code\":" + quote(code) + ","
                + "\"existingCode\":" + quote(code) + ","
                + "\"evidence\":[],"
                + "\"confidence\":0.5"
                + "}";
    }

    /** The reflection contract: an object with a remove array of {id, reason}. */
    private static String removal(String id) {
        return "{\"remove\":[{\"id\":" + quote(id) + ",\"reason\":\"quoted code does not exist\"}]}";
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * A ModelClient that answers in scripted order and fails loudly on an unexpected call, so the
     * tests also pin down how many requests a report costs.
     */
    private static final class FakeClient implements ModelClient {
        private final Deque<Object> responses = new ArrayDeque<>();
        final List<String> prompts = new ArrayList<>();

        FakeClient(Object... responses) {
            for (Object response : responses) {
                this.responses.add(response);
            }
        }

        @Override
        public String complete(String systemPrompt, String prompt, Integer maxTokens) throws Exception {
            prompts.add(prompt);
            Object response = responses.poll();
            if (response == null) {
                throw new IllegalStateException("unexpected model call " + prompts.size());
            }
            if (response instanceof RuntimeException runtime) {
                throw runtime;
            }
            return (String) response;
        }
    }
}
