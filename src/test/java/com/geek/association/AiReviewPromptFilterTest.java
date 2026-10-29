package com.geek.association;

import com.geek.association.model.ImpactResult;
import com.geek.association.report.ImpactReport;
import com.geek.association.review.ParsedDiff;
import com.geek.association.review.ReviewComment;
import com.geek.association.review.ReviewFilter;
import com.geek.association.review.ReviewPromptBuilder;
import com.geek.association.review.ReviewPromptBuilder.FactCatalog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two halves of "a comment must be locatable and fact-checked before it is shown".
 *
 * The prompt builder is tested through the text it produces and the answers it accepts, because
 * that text is the only interface the model has; the filter is tested as a pure function, because
 * every one of its rules is a promise that no model call is spent on a check code can make.
 */
class AiReviewPromptFilterTest {

    private static final String CHANGE_FILE = "src/main/java/com/example/order/OrderService.java";
    private static final String IMPACT_FILE = "billing-service/src/main/java/com/example/billing/OrderClient.java";
    private static final String BLIND_FILE = "src/main/java/com/example/order/OrderMapper.java";

    // ------------------------------------------------------------------ fixtures

    private static ImpactReport.ChangeView change(String type, String qualifiedName,
                                                  String summary, String file) {
        ImpactReport.ChangeView change = new ImpactReport.ChangeView();
        change.changeType = type;
        change.module = "order-service";
        change.qualifiedName = qualifiedName;
        change.signature = "()";
        change.summary = summary;
        change.file = file;
        return change;
    }

    private static ImpactReport.ImpactView impact() {
        ImpactReport.ImpactView impact = new ImpactReport.ImpactView();
        impact.riskLevel = "HIGH";
        impact.riskScore = 72;
        impact.targetModule = "billing-service";
        impact.targetSymbol = "com.example.billing.OrderClient.get";
        impact.targetSignature = "(int)";
        impact.targetFile = IMPACT_FILE;
        impact.impactType = "API_CONTRACT";
        impact.confidence = "HIGH";
        impact.depth = 2;
        impact.reason = "调用方按 int 接收返回值，long 会溢出";
        impact.path.add(new ImpactResult.PathStep(
                "com.example.order.OrderService.total", "order-service", CHANGE_FILE, 20));
        impact.path.get(0).usage = "API_CALL";
        impact.path.add(new ImpactResult.PathStep(
                "com.example.billing.OrderClient.get", "billing-service", IMPACT_FILE, 40));
        impact.path.get(1).usage = "READ";
        return impact;
    }

    private static ImpactReport.BlindSpot blindSpot(String file, int line, String expression, String reason) {
        ImpactReport.BlindSpot spot = new ImpactReport.BlindSpot();
        spot.file = file;
        spot.line = line;
        spot.expression = expression;
        spot.reason = reason;
        return spot;
    }

    private static ImpactReport sampleReport() {
        ImpactReport report = new ImpactReport();
        report.changes.add(change("METHOD_BODY_CHANGED",
                "com.example.order.OrderService.total", "改为返回加法结果", CHANGE_FILE));
        report.changes.add(change("API_CONTRACT_CHANGED",
                "com.example.order.OrderController.get", "返回类型由 int 改为 long", CHANGE_FILE));
        report.impacts.add(impact());
        report.blindSpots.add(blindSpot(BLIND_FILE, 12, "reflection.invoke(...)", "动态调用无法静态解析"));
        return report;
    }

    /** Builds a one-hunk diff from compact strings: {@code "+line"}, {@code "-line"}, {@code " line"}. */
    private static ParsedDiff diff(String path, String... markedLines) {
        ParsedDiff diff = new ParsedDiff(path);
        ParsedDiff.Hunk hunk = new ParsedDiff.Hunk(1, 1);
        int oldLine = 1;
        int newLine = 1;
        for (String marked : markedLines) {
            char marker = marked.isEmpty() ? ' ' : marked.charAt(0);
            String text = marked.isEmpty() ? "" : marked.substring(1);
            switch (marker) {
                case '+' -> hunk.lines.add(new ParsedDiff.Line(
                        ParsedDiff.LineKind.ADDED, 0, newLine++, text));
                case '-' -> hunk.lines.add(new ParsedDiff.Line(
                        ParsedDiff.LineKind.DELETED, oldLine++, 0, text));
                default -> hunk.lines.add(new ParsedDiff.Line(
                        ParsedDiff.LineKind.CONTEXT, oldLine++, newLine++, text));
            }
        }
        diff.hunks.add(hunk);
        return diff;
    }

    // ------------------------------------------------------------------ fact catalog

    @Test
    void factsCarryStableIdsAndTheFieldsThePromptNeeds() {
        ReviewPromptBuilder builder = new ReviewPromptBuilder();

        FactCatalog facts = builder.facts(sampleReport(), 12);

        assertTrue(facts.text.contains("C1"), facts.text);
        assertTrue(facts.text.contains("C2"), facts.text);
        assertTrue(facts.text.contains("I1"), facts.text);
        assertTrue(facts.text.contains("B1"), facts.text);
        // Every field the prompt promises is actually there, otherwise the model has to guess.
        assertTrue(facts.text.contains("METHOD_BODY_CHANGED"));
        assertTrue(facts.text.contains("com.example.order.OrderService.total"));
        assertTrue(facts.text.contains("改为返回加法结果"));
        assertTrue(facts.text.contains("API_CONTRACT"));
        assertTrue(facts.text.contains("调用方按 int 接收返回值"));
        assertTrue(facts.text.contains("com.example.order.OrderService.total @"
                + CHANGE_FILE + ":20[API_CALL] -> com.example.billing.OrderClient.get @"
                + IMPACT_FILE + ":40[READ]"), "every hop of the path is sent: " + facts.text);
        assertTrue(facts.text.contains("动态调用无法静态解析"));

        assertEquals(CHANGE_FILE, facts.idToFile.get("C1"));
        assertEquals(CHANGE_FILE, facts.idToFile.get("C2"));
        assertEquals(IMPACT_FILE, facts.idToFile.get("I1"));
        assertEquals(BLIND_FILE, facts.idToFile.get("B1"));
    }

    @Test
    void factsAreCappedAtFifteenChangesMaxImpactsAndEightBlindSpots() {
        ImpactReport report = sampleReport();
        for (int i = 3; i <= 20; i++) {
            report.changes.add(change("FIELD_CHANGED", "com.example.Symbol" + i,
                    "summary " + i, "src/main/java/com/example/File" + i + ".java"));
        }
        for (int i = 0; i < 4; i++) {
            ImpactReport.ImpactView extra = impact();
            extra.targetFile = "peer-" + i + ".java";
            report.impacts.add(extra);
        }
        for (int i = 0; i < 10; i++) {
            report.blindSpots.add(blindSpot("src/main/java/com/example/Blind" + i + ".java",
                    i + 1, "expr" + i, "reason " + i));
        }

        FactCatalog facts = new ReviewPromptBuilder().facts(report, 1);

        assertTrue(facts.text.contains("C15"), "15 changes are kept");
        assertFalse(facts.text.contains("C16"), "changes past the cap are not sent: " + facts.text);
        assertTrue(facts.text.contains("I1"));
        assertFalse(facts.text.contains("I2"), "maxImpacts bounds the impact rows: " + facts.text);
        assertTrue(facts.text.contains("B8"), "8 blind spots are kept");
        assertFalse(facts.text.contains("B9"), "blind spots past the cap are not sent: " + facts.text);
        assertEquals(15 + 1 + 8, facts.idToFile.size(),
                "the mapping must contain exactly the facts that were sent");
    }

    @Test
    void factsNeverPrintNullForMissingFields() {
        ImpactReport report = new ImpactReport();
        report.changes.add(change(null, null, null, null));
        report.impacts.add(new ImpactReport.ImpactView());
        report.blindSpots.add(new ImpactReport.BlindSpot());

        FactCatalog facts = new ReviewPromptBuilder().facts(report, 12);

        assertFalse(facts.text.contains("null"), "missing fields must render as empty, not \"null\": "
                + facts.text);
        assertTrue(facts.idToFile.isEmpty(), "a fact without a file cannot be mapped to one");
    }

    @Test
    void factsOfAnEmptyOrNullReportAreSafe() {
        ReviewPromptBuilder builder = new ReviewPromptBuilder();

        FactCatalog empty = builder.facts(new ImpactReport(), 12);
        assertTrue(empty.idToFile.isEmpty());

        FactCatalog missing = builder.facts(null, 12);
        assertEquals("", missing.text);
        assertTrue(missing.idToFile.isEmpty());
    }

    // ------------------------------------------------------------------ prompts

    @Test
    void commentsPromptCarriesFactsHunksAndTheJsonContract() {
        ReviewPromptBuilder builder = new ReviewPromptBuilder();
        FactCatalog facts = builder.facts(sampleReport(), 12);
        ParsedDiff diff = diff(CHANGE_FILE,
                " public long total(int a, int b) {",
                "-    return a - b;",
                "+    return a + b;",
                " }");

        String prompt = builder.commentsPrompt(facts, List.of(diff));

        assertTrue(prompt.contains("com.example.order.OrderService.total"), "facts are included");
        assertTrue(prompt.contains(CHANGE_FILE), "the diff's file is named");
        assertTrue(prompt.contains("@@ -1,3 +1,3 @@"), "hunk header with real line counts: " + prompt);
        assertTrue(prompt.contains("-    return a - b;"), "deleted lines keep their prefix");
        assertTrue(prompt.contains("+    return a + b;"), "added lines keep their prefix");
        assertTrue(prompt.contains("\"comments\""), "the JSON shape is spelled out");
        assertTrue(prompt.contains("existing_code"), "the locator's input field is named");
        assertTrue(prompt.contains("evidence"), "the filter's input field is named");
        assertTrue(prompt.contains("逐字"), "the model must quote code verbatim");
        assertTrue(prompt.contains("禁止评论没有提供的代码"), "it may only discuss provided code");
        assertFalse(prompt.contains("```"), "the output contract forbids markdown fences");
    }

    @Test
    void commentsPromptSaysThereIsNothingToReviewWhenNoDiffIsGiven() {
        String prompt = new ReviewPromptBuilder()
                .commentsPrompt(new FactCatalog("facts", Map.of()), List.of());

        assertTrue(prompt.contains("没有可评审的片段"), prompt);
        assertTrue(prompt.contains("{\"comments\":[]}"), "the empty answer is still demanded");
    }

    @Test
    void reflectionPromptOnlyAsksToRemoveFactualErrors() {
        ReviewComment comment = new ReviewComment();
        comment.id = "c1";
        comment.file = CHANGE_FILE;
        comment.title = "返回值类型不匹配";
        comment.detail = "调用方按 int 接收，long 会溢出";
        comment.existingCode = "return a + b;";
        ParsedDiff diff = diff(CHANGE_FILE,
                " public long total(int a, int b) {",
                "+    return a + b;",
                " }");

        String prompt = new ReviewPromptBuilder()
                .reflectionPrompt(List.of(comment), Map.of(CHANGE_FILE, diff));

        assertTrue(prompt.contains("c1"), "the comment id is in the prompt so the answer can name it");
        assertTrue(prompt.contains(comment.title));
        assertTrue(prompt.contains(comment.detail));
        assertTrue(prompt.contains("return a + b;"), "the quoted code is included");
        assertTrue(prompt.contains("+    return a + b;"), "the target hunk is included");
        assertTrue(prompt.contains("事实错误"), "the removal criterion is named");
        assertTrue(prompt.contains("价值低"), "the protected weak cases are named");
        assertTrue(prompt.contains("不要因为"), "the model is told what not to remove");
        assertTrue(prompt.contains("\"remove\""), "the answer shape is specified");
    }

    @Test
    void relocationPromptAsksForOneFencedBlockFromTheGivenHunkOnly() {
        ReviewComment comment = new ReviewComment();
        comment.title = "空指针风险";
        comment.detail = "old 可能为 null";
        comment.existingCode = "old != null";
        ParsedDiff diff = diff(CHANGE_FILE,
                " if (old != null) {",
                "+    return old.value();",
                " }");

        String prompt = new ReviewPromptBuilder().relocationPrompt(comment, diff);

        assertTrue(prompt.contains("old 可能为 null"));
        assertTrue(prompt.contains("old != null"));
        assertTrue(prompt.contains("+    return old.value();"), "the hunk is the only source");
        assertTrue(prompt.contains("```"), "a fenced block is requested");
        assertTrue(prompt.contains("空代码块"), "the empty answer is defined");
    }

    // ------------------------------------------------------------------ parsing

    @Test
    void parseCommentsReadsPlainJsonAndNormalisesEnums() {
        String response = """
                {"comments":[
                  {"id":"c1","severity":"major","category":"correctness","title":"空指针风险",
                   "detail":"old 可能为 null","suggestion":"加判空","file":"A.java",
                   "existing_code":"return old.value();","evidence":["C1","C2"],"confidence":0.8}
                ]}
                """;

        List<ReviewComment> comments = ReviewPromptBuilder.parseComments(response);

        assertEquals(1, comments.size(), response);
        ReviewComment comment = comments.get(0);
        assertEquals("c1", comment.id);
        assertEquals("MAJOR", comment.severity, "severity is normalised to upper case");
        assertEquals("CORRECTNESS", comment.category);
        assertEquals("空指针风险", comment.title);
        assertEquals("old 可能为 null", comment.detail);
        assertEquals("加判空", comment.suggestion);
        assertEquals("A.java", comment.file);
        assertEquals("return old.value();", comment.existingCode);
        assertEquals(List.of("C1", "C2"), comment.evidence);
        assertEquals(0.8, comment.confidence, 0.0001);
        assertEquals("NEW", comment.side);
        assertEquals("KEPT", comment.status);
    }

    @Test
    void parseCommentsToleratesFencesAndSurroundingProse() {
        String response = """
                下面是评审结果：
                ```json
                {"comments":[{"title":"越界","detail":"下标可能越界","file":"A.java",
                 "existing_code":"list.get(i)","severity":"BLOCKER","category":"SECURITY",
                 "evidence":["C1"]}]}
                ```
                以上仅供参考。
                """;

        List<ReviewComment> comments = ReviewPromptBuilder.parseComments(response);

        assertEquals(1, comments.size(), response);
        assertEquals("越界", comments.get(0).title);
        assertEquals("BLOCKER", comments.get(0).severity);
        assertEquals("SECURITY", comments.get(0).category);
    }

    @Test
    void parseCommentsSkipsInvalidEntriesAndDefaultsIllegalEnums() {
        String response = """
                {"comments":[
                  {"severity":"weird","category":"style","title":"保留这条","detail":"d",
                   "file":"A.java","existing_code":"code","evidence":["C1",5,{"x":1}]},
                  {"title":"没有代码","detail":"d","file":"A.java","severity":"MAJOR","category":"CORRECTNESS"},
                  {"title":"","detail":"d","file":"A.java","existing_code":"code"}
                ]}
                """;

        List<ReviewComment> comments = ReviewPromptBuilder.parseComments(response);

        assertEquals(1, comments.size(), "entries without code or title are not comments: " + response);
        ReviewComment comment = comments.get(0);
        assertEquals("MAJOR", comment.severity, "an unknown severity falls back to MAJOR");
        assertEquals("OTHER", comment.category, "an unknown category falls back to OTHER");
        assertEquals(List.of("C1"), comment.evidence, "evidence keeps strings only");
        assertEquals("c1", comment.id, "a missing id is generated so reflection can name it");
    }

    @Test
    void parseCommentsToleratesABareArrayWrapper() {
        String response = "[{\"title\":\"t\",\"detail\":\"d\",\"file\":\"A.java\","
                + "\"existing_code\":\"code\"}]";

        assertEquals(1, ReviewPromptBuilder.parseComments(response).size());
    }

    @Test
    void parseCommentsReturnsEmptyOnGarbageInsteadOfThrowing() {
        assertTrue(ReviewPromptBuilder.parseComments("{ this is not json").isEmpty());
        assertTrue(ReviewPromptBuilder.parseComments("only prose, no payload").isEmpty());
        assertTrue(ReviewPromptBuilder.parseComments("{\"other\":[]}").isEmpty());
        assertTrue(ReviewPromptBuilder.parseComments("").isEmpty());
        assertTrue(ReviewPromptBuilder.parseComments(null).isEmpty());
    }

    @Test
    void parseCommentsCapsAtThirty() {
        StringBuilder json = new StringBuilder("{\"comments\":[");
        for (int i = 1; i <= 35; i++) {
            if (i > 1) {
                json.append(',');
            }
            json.append("{\"title\":\"t").append(i).append("\",\"detail\":\"d\",")
                    .append("\"file\":\"A.java\",\"existing_code\":\"code").append(i).append("\"}");
        }
        json.append("]}");

        assertEquals(30, ReviewPromptBuilder.parseComments(json.toString()).size());
    }

    @Test
    void extractFencedCodeReadsTheFirstBlockOrFallsBackToWholeText() {
        assertEquals("int a = 1;",
                ReviewPromptBuilder.extractFencedCode("```java\nint a = 1;\n```"));
        assertEquals("line", ReviewPromptBuilder.extractFencedCode(
                "说明\n```\nline\n```\n结束"));
        assertEquals("int a = 1;", ReviewPromptBuilder.extractFencedCode("   int a = 1;  "),
                "without a fence the whole answer is the snippet");
        assertEquals("", ReviewPromptBuilder.extractFencedCode("```\n```"),
                "an empty block means no such code");
        assertEquals("", ReviewPromptBuilder.extractFencedCode(""));
        assertEquals("", ReviewPromptBuilder.extractFencedCode(null));
        assertEquals("code", ReviewPromptBuilder.extractFencedCode("```\ncode"),
                "an unclosed fence still yields what was written");
    }

    @Test
    void parseRemovalIdsAcceptsObjectsAndPlainStrings() {
        assertEquals(List.of("c1", "c2"), ReviewPromptBuilder.parseRemovalIds(
                "{\"remove\":[{\"id\":\"c1\",\"reason\":\"目标代码不在片段中\"},{\"id\":\"c2\"}]}"));
        assertEquals(List.of("c1", "c2"),
                ReviewPromptBuilder.parseRemovalIds("{\"remove\":[\"c1\",\"c2\"]}"));
        assertEquals(List.of("c1"), ReviewPromptBuilder.parseRemovalIds(
                "结果如下：```json\n{\"remove\":[{\"id\":\"c1\"}]}\n```\n完毕"));
        assertEquals(List.of("c1"), ReviewPromptBuilder.parseRemovalIds(
                "{\"remove\":[\"c1\",\"c1\"]}"), "duplicate ids must not be counted twice");
    }

    @Test
    void parseRemovalIdsReturnsEmptyOnGarbage() {
        assertTrue(ReviewPromptBuilder.parseRemovalIds("not json").isEmpty());
        assertTrue(ReviewPromptBuilder.parseRemovalIds("{\"remove\":[]}").isEmpty());
        assertTrue(ReviewPromptBuilder.parseRemovalIds("{\"remove\":\"c1\"}").isEmpty());
        assertTrue(ReviewPromptBuilder.parseRemovalIds(null).isEmpty());
    }

    // ------------------------------------------------------------------ deterministic filter

    private static final Set<String> FACT_IDS = Set.of("C1", "C2", "I1", "B1");
    private static final Map<String, String> FACT_FILES =
            Map.of("C1", "A.java", "C2", "B.java", "I1", "B.java", "B1", "A.java");

    private static Map<String, ParsedDiff> sentDiffs() {
        Map<String, ParsedDiff> diffs = new LinkedHashMap<>();
        diffs.put("A.java", diff("A.java", " public void a() {", "+    int a = 1;", " }"));
        diffs.put("B.java", diff("B.java", " public void b() {", "+    int b = 1;", " }"));
        diffs.put("C.java", diff("C.java", " public void c() {", "+    int c = 1;", " }"));
        return diffs;
    }

    private static ReviewComment comment(String id, String file, int start, int end,
                                         String severity, String category, String title) {
        ReviewComment comment = new ReviewComment();
        comment.id = id;
        comment.file = file;
        comment.startLine = start;
        comment.endLine = end;
        comment.severity = severity;
        comment.category = category;
        comment.title = title;
        comment.detail = "detail of " + title;
        comment.existingCode = "line for " + title;
        if ("A.java".equals(file)) {
            comment.evidence = new ArrayList<>(List.of("C1"));
        } else if ("B.java".equals(file)) {
            comment.evidence = new ArrayList<>(List.of("C2"));
        }
        return comment;
    }

    private static ReviewComment valid(String id, String file, int start, int end,
                                       String severity, String title) {
        return comment(id, file, start, end, severity, "CORRECTNESS", title);
    }

    private static List<String> ids(List<ReviewComment> comments) {
        List<String> ids = new ArrayList<>();
        for (ReviewComment comment : comments) {
            ids.add(comment.id);
        }
        return ids;
    }

    private static List<String> notes(List<ReviewComment> comments) {
        List<String> notes = new ArrayList<>();
        for (ReviewComment comment : comments) {
            notes.add(comment.filterNote);
        }
        return notes;
    }

    private static ReviewFilter.Outcome apply(List<ReviewComment> comments, int maxComments) {
        return new ReviewFilter().apply(new ImpactReport(), comments, sentDiffs(),
                FACT_IDS, FACT_FILES, maxComments);
    }

    @Test
    void filterKeepsAValidCommentAndMarksItKept() {
        ReviewComment comment = valid("c1", "A.java", 1, 2, "MAJOR", "空指针风险");

        ReviewFilter.Outcome outcome = apply(List.of(comment), 12);

        assertEquals(List.of("c1"), ids(outcome.kept));
        assertTrue(outcome.dropped.isEmpty());
        assertEquals("KEPT", comment.status);
        assertNull(comment.filterNote);
    }

    @Test
    void filterAcceptsACommentWithoutEvidence() {
        ReviewComment comment = comment("c1", "C.java", 1, 1, "MINOR", "MAINTAINABILITY",
                "常量可以提取");
        comment.evidence = new ArrayList<>();

        ReviewFilter.Outcome outcome = apply(List.of(comment), 12);

        assertEquals(List.of("c1"), ids(outcome.kept),
                "evidence is optional; only a non-empty list has to be truthful");
    }

    @Test
    void filterRejectsEveryDeterministicRuleWithItsOwnReason() {
        List<ReviewComment> comments = new ArrayList<>();

        comments.add(valid("c1", "Z.java", 1, 1, "MAJOR", "不在本次送审文件里"));

        ReviewComment unknownEvidence = valid("c2", "A.java", 1, 1, "MAJOR", "引用不存在的事实");
        unknownEvidence.evidence = List.of("C9");
        comments.add(unknownEvidence);

        ReviewComment crossFile = valid("c3", "A.java", 1, 1, "MAJOR", "引用别的文件的事实");
        crossFile.evidence = List.of("C2");
        comments.add(crossFile);

        comments.add(valid("c4", "A.java", 0, 3, "MAJOR", "起始行非法"));
        comments.add(valid("c5", "A.java", 5, 4, "MAJOR", "结束行小于起始行"));

        ReviewComment emptyDetail = valid("c6", "A.java", 1, 1, "MAJOR", "正文为空");
        emptyDetail.detail = "";
        comments.add(emptyDetail);

        ReviewComment emptyTitle = valid("c7", "A.java", 1, 1, "MAJOR", "标题为空");
        emptyTitle.title = "";
        comments.add(emptyTitle);

        ReviewComment emptyCode = valid("c8", "A.java", 1, 1, "MAJOR", "引用代码为空");
        emptyCode.existingCode = "";
        comments.add(emptyCode);

        comments.add(valid("c9", "A.java", 1, 1, "SEVERE", "非法严重级"));

        ReviewComment badCategory = valid("c10", "A.java", 1, 1, "MAJOR", "非法类别");
        badCategory.category = "STYLE";
        comments.add(badCategory);

        ReviewFilter.Outcome outcome = apply(comments, 12);

        assertTrue(outcome.kept.isEmpty(), "all of these must be dropped: " + ids(outcome.kept));
        Map<String, String> byId = new LinkedHashMap<>();
        for (ReviewComment comment : outcome.dropped) {
            byId.put(comment.id, comment.filterNote);
        }
        assertEquals("file-not-sent", byId.get("c1"));
        assertEquals("unknown-evidence", byId.get("c2"));
        assertEquals("cross-file-evidence", byId.get("c3"));
        assertEquals("bad-range", byId.get("c4"));
        assertEquals("bad-range", byId.get("c5"));
        assertEquals("empty-text", byId.get("c6"));
        assertEquals("empty-text", byId.get("c7"));
        assertEquals("empty-existing-code", byId.get("c8"));
        assertEquals("bad-severity", byId.get("c9"));
        assertEquals("bad-category", byId.get("c10"));
        for (ReviewComment comment : outcome.dropped) {
            assertEquals("DROPPED", comment.status);
        }
    }

    @Test
    void filterMergesNearDuplicatesAndKeepsTheMoreSevere() {
        ReviewComment major = valid("c1", "A.java", 1, 3, "MAJOR", "调用方未处理空返回值");
        // Same title after normalisation, weaker severity: must be absorbed into c1.
        ReviewComment weakerSameTitle = valid("c2", "A.java", 2, 4, "MINOR", "调用方未处理 空返回值");
        // Different wording, same meaning, same category: Jaccard similarity catches it.
        ReviewComment similarWording = valid("c3", "A.java", 2, 3, "MINOR", "调用方未处理空返回值导致异常");
        // Similar wording but another category: a different dimension, not a duplicate.
        ReviewComment otherCategory = valid("c4", "A.java", 1, 2, "MINOR", "调用方未处理空返回值导致崩溃");
        otherCategory.category = "PERFORMANCE";

        ReviewFilter.Outcome outcome = apply(
                List.of(major, weakerSameTitle, similarWording, otherCategory), 12);

        assertEquals(List.of("c1", "c4"), ids(outcome.kept),
                "the strongest wording survives and a different category is not swallowed");
        assertEquals(List.of("c2", "c3"), ids(outcome.dropped));
        assertEquals(List.of("duplicate", "duplicate"), notes(outcome.dropped));
    }

    @Test
    void filterCapsEachFileAtFourComments() {
        List<ReviewComment> comments = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            comments.add(valid("c" + i, "A.java", i * 2 - 1, i * 2, "MAJOR", "问题" + i));
        }

        ReviewFilter.Outcome outcome = apply(comments, 100);

        assertEquals(4, outcome.kept.size(), "one file may not flood the report");
        assertEquals(List.of("c5", "c6"), ids(outcome.dropped));
        assertEquals(List.of("cap", "cap"), notes(outcome.dropped));
    }

    @Test
    void filterCapsGloballyBySeverityNotByModelOrder() {
        ReviewComment info = comment("c1", "A.java", 1, 1, "INFO", "CORRECTNESS", "提示项");
        ReviewComment major = comment("c2", "B.java", 1, 1, "MAJOR", "CORRECTNESS", "重要项");
        // C.java has no fact id of its own, so evidence stays empty; the cap is what should drop it.
        ReviewComment blocker = comment("c3", "C.java", 1, 1, "BLOCKER", "CORRECTNESS", "阻断项");
        blocker.evidence = new ArrayList<>();

        ReviewFilter.Outcome outcome = apply(List.of(info, major, blocker), 2);

        assertEquals(List.of("c2", "c3"), ids(outcome.kept),
                "the two most severe comments survive, regardless of the model's order");
        assertEquals(List.of("c1"), ids(outcome.dropped));
        assertEquals(List.of("cap"), notes(outcome.dropped));
    }

    @Test
    void filterWithNoCommentsIsAnEmptyOutcome() {
        ReviewFilter.Outcome outcome = new ReviewFilter().apply(new ImpactReport(), List.of(),
                sentDiffs(), FACT_IDS, FACT_FILES, 12);

        assertTrue(outcome.kept.isEmpty());
        assertTrue(outcome.dropped.isEmpty());
    }
}
