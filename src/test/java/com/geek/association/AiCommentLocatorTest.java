package com.geek.association;

import com.geek.association.review.CommentLocator;
import com.geek.association.review.FileContent;
import com.geek.association.review.ParsedDiff;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定位是"模型说位置"和"位置可信"之间唯一的一道确定性防线，所以每个回退分支都要有断言：
 * 行号算错、歧义时装作找到、或者清洗规则把合法引用误伤，都会让一条评论指到别的代码上，
 * 而这种错误在界面上看起来和真正的评审意见一模一样。
 *
 * 这里也钉住两条降级约定：历史报告没有 head 文件内容（{@link FileContent#NONE}）时整文件层
 * 必须跳过而不是报错；超过 50 行的引用按"截断前 50 行"重试（见 overlongSnippet...）。
 */
class AiCommentLocatorTest {

    private static final String PATH = "src/main/java/com/example/A.java";

    private static ParsedDiff parse(String... lines) {
        return ParsedDiff.parse(PATH, String.join("\n", lines));
    }

    private static String text(String... lines) {
        return String.join("\n", lines);
    }

    private static CommentLocator.Result locate(ParsedDiff diff, String snippet) {
        return new CommentLocator().locate(diff, snippet, FileContent.NONE);
    }

    private static CommentLocator.Result locateInFile(ParsedDiff diff, String snippet, String fileContent) {
        return new CommentLocator().locate(diff, snippet, path -> fileContent);
    }

    private static void assertLine(ParsedDiff.Line line, ParsedDiff.LineKind kind,
                                   int oldLine, int newLine, String content) {
        assertEquals(kind, line.kind);
        assertEquals(oldLine, line.oldLine);
        assertEquals(newLine, line.newLine);
        assertEquals(content, line.text);
    }

    /** 报告里的 diff 是 LineDiff 生成的：header 无 count，两个 hunk，没有结尾换行标记。 */
    @Test
    void parsesHunkLineNumbersWithMissingCountsAndMultipleHunks() {
        ParsedDiff diff = parse(
                "--- a/" + PATH,
                "+++ b/" + PATH,
                "@@ -10,4 +20,5 @@ public void run() {",
                " contextA",
                "-deletedB",
                "+addedB",
                " contextC",
                "\\ No newline at end of file",
                "@@ -100 +111,2 @@",
                "-old",
                "+new1",
                "+new2");

        assertEquals(2, diff.hunks.size());
        ParsedDiff.Hunk first = diff.hunks.get(0);
        assertEquals(10, first.oldStart);
        assertEquals(20, first.newStart);
        assertEquals(4, first.lines.size(), "the no-newline marker is metadata, not a diff line");
        assertLine(first.lines.get(0), ParsedDiff.LineKind.CONTEXT, 10, 20, "contextA");
        assertLine(first.lines.get(1), ParsedDiff.LineKind.DELETED, 11, 0, "deletedB");
        assertLine(first.lines.get(2), ParsedDiff.LineKind.ADDED, 0, 21, "addedB");
        assertLine(first.lines.get(3), ParsedDiff.LineKind.CONTEXT, 12, 22, "contextC");

        ParsedDiff.Hunk second = diff.hunks.get(1);
        assertEquals(100, second.oldStart);
        assertEquals(111, second.newStart);
        assertLine(second.lines.get(0), ParsedDiff.LineKind.DELETED, 100, 0, "old");
        assertLine(second.lines.get(1), ParsedDiff.LineKind.ADDED, 0, 111, "new1");
        assertLine(second.lines.get(2), ParsedDiff.LineKind.ADDED, 0, 112, "new2");
        assertTrue(diff.hasChanges());
        assertFalse(diff.truncated());
    }

    /** 中间的空行是未改动行；它必须同时推进 old/new 两个计数器，否则后面的行号整体偏移。 */
    @Test
    void blankContextLineKeepsBothCountersAligned() {
        ParsedDiff diff = indentedHunk();
        ParsedDiff.Hunk hunk = diff.hunks.get(0);
        assertEquals(6, hunk.lines.size());
        assertLine(hunk.lines.get(0), ParsedDiff.LineKind.CONTEXT, 1, 1, "class A {");
        assertLine(hunk.lines.get(1), ParsedDiff.LineKind.CONTEXT, 2, 2, "");
        assertLine(hunk.lines.get(2), ParsedDiff.LineKind.CONTEXT, 3, 3, "    void run() {");
        assertLine(hunk.lines.get(3), ParsedDiff.LineKind.ADDED, 0, 4, "        int x = 1;");
        assertLine(hunk.lines.get(4), ParsedDiff.LineKind.CONTEXT, 4, 5, "    }");
        assertLine(hunk.lines.get(5), ParsedDiff.LineKind.CONTEXT, 5, 6, "}");
    }

    private static ParsedDiff indentedHunk() {
        return parse(
                "--- a/" + PATH,
                "+++ b/" + PATH,
                "@@ -1,5 +1,6 @@",
                " class A {",
                "",
                "     void run() {",
                "+        int x = 1;",
                "     }",
                " }");
    }

    @Test
    void indentationAndBlankLinesInSnippetStillMatch() {
        CommentLocator.Result result = locate(indentedHunk(), text(
                "    void run() {",
                "",
                "            int x = 1;",
                "    }"));

        assertTrue(result.resolved);
        assertEquals("HUNK", result.anchor);
        assertEquals("NEW", result.side);
        assertEquals(3, result.startLine);
        assertEquals(5, result.endLine);
        assertEquals(1, result.candidates);
    }

    @Test
    void diffPrefixesAndCodeFencesAreStrippedBeforeMatching() {
        CommentLocator.Result prefixed = locate(indentedHunk(), "+        int x = 1;");
        assertTrue(prefixed.resolved);
        assertEquals(4, prefixed.startLine);
        assertEquals(4, prefixed.endLine);

        // 模型常把回答包在围栏里，并且把 prompt 里看到的 diff 前缀一起抄回来。
        CommentLocator.Result fenced = locate(indentedHunk(), text(
                "```java",
                "    void run() {",
                "        int x = 1;",
                "    }",
                "```"));
        assertTrue(fenced.resolved);
        assertEquals(3, fenced.startLine);
        assertEquals(5, fenced.endLine);

        CommentLocator.Result both = locate(indentedHunk(), text(
                "```diff",
                "+        int x = 1;",
                "```"));
        assertTrue(both.resolved);
        assertEquals(4, both.startLine);
        assertEquals(4, both.endLine);
    }

    /** 评论引用的是改动行之外的上下文时，只有整文件层能定位；历史报告没有文件内容就只能放弃。 */
    @Test
    void snippetOutsideHunksFallsBackToWholeFileAndNeedsHeadContent() {
        ParsedDiff diff = parse(
                "--- a/" + PATH,
                "+++ b/" + PATH,
                "@@ -1,3 +1,3 @@",
                " class B {",
                "-    int a = 1;",
                "+    int a = 2;",
                " }");
        String fileContent = text(
                "class B {",
                "    int a = 2;",
                "",
                "    int helper() {",
                "        return 0;",
                "    }",
                "}");
        String snippet = text("int helper() {", "        return 0;", "    }");

        CommentLocator.Result located = locateInFile(diff, snippet, fileContent);
        assertTrue(located.resolved);
        assertEquals("FILE", located.anchor);
        assertEquals("NEW", located.side);
        assertEquals(4, located.startLine);
        assertEquals(6, located.endLine);

        CommentLocator.Result historical = locate(diff, snippet);
        assertFalse(historical.resolved, "历史报告只有 hunk，没有 head 文件，整文件层必须跳过");
        assertEquals("NONE", historical.anchor);
    }

    @Test
    void deletedSnippetIsPlacedOnTheOldSide() {
        ParsedDiff diff = parse(
                "@@ -5,3 +5,2 @@",
                "     int a = 1;",
                "-    int legacy = 2;",
                "     int b = 3;");

        CommentLocator.Result result = locate(diff, "    int legacy = 2;");

        assertTrue(result.resolved);
        assertEquals("HUNK", result.anchor);
        assertEquals("OLD", result.side, "删除行在新文件里不存在，只能标到旧侧");
        assertEquals(6, result.startLine);
        assertEquals(6, result.endLine);
    }

    @Test
    void snippetThatDoesNotExistAnywhereStaysUnplaced() {
        ParsedDiff diff = parse(
                "@@ -1,2 +1,2 @@",
                " class C {",
                "-    int a = 1;",
                "+    int a = 2;");

        CommentLocator.Result result = locate(diff, "    int doesNotExist = 42;");

        assertFalse(result.resolved);
        assertEquals("NONE", result.anchor);
        assertEquals(0, result.startLine);
        assertEquals(0, result.endLine);
        assertEquals(0, result.candidates);
    }

    /** 整文件层出现两处等距匹配时宁可丢弃：选第一处就是让评论指向另一段代码。 */
    @Test
    void equidistantWholeFileMatchesAreRejected() {
        ParsedDiff diff = parse(
                "@@ -3,3 +3,3 @@",
                "     int a = 1;",
                "-    int changed = 2;",
                "+    int changed = 3;",
                "     int b = 2;");
        String fileContent = text(
                "return null;",
                "class E {",
                "    int a = 1;",
                "    int changed = 3;",
                "    int b = 2;",
                "    int c = 4;",
                "return null;",
                "}");

        CommentLocator.Result result = locateInFile(diff, "return null;", fileContent);

        assertFalse(result.resolved, "两处到改动行的距离相同，不能猜");
        assertEquals("NONE", result.anchor);
        assertEquals(2, result.candidates);
    }

    @Test
    void malformedHunkHeadersEndTheHunkWithoutThrowing() {
        ParsedDiff diff = parse(
                "--- a/" + PATH,
                "+++ b/" + PATH,
                "@@ -oops +nope @@",
                "+outsideAnyHunk",
                "@@ -3 +4 @@",
                " context",
                "+added",
                "@@ broken @@",
                "+ignored",
                "@@ -10,2 +20,2 @@",
                "-gone",
                "+kept");

        assertEquals(2, diff.hunks.size(), "只有两个能解析的 header 建立了 hunk");
        ParsedDiff.Hunk first = diff.hunks.get(0);
        assertEquals(3, first.oldStart);
        assertEquals(4, first.newStart);
        assertEquals(2, first.lines.size(), "畸形 header 之后的加号行不再属于这个 hunk");
        assertLine(first.lines.get(1), ParsedDiff.LineKind.ADDED, 0, 5, "added");

        ParsedDiff.Hunk second = diff.hunks.get(1);
        assertEquals(10, second.oldStart);
        assertEquals(20, second.newStart);
        assertLine(second.lines.get(0), ParsedDiff.LineKind.DELETED, 10, 0, "gone");
        assertLine(second.lines.get(1), ParsedDiff.LineKind.ADDED, 0, 20, "kept");
        assertFalse(diff.truncated());
    }

    /** 截断标记之后的编号不可信，必须停止该 hunk，否则评论会落到没被展示的代码上。 */
    @Test
    void truncationMarkersStopTheHunkInsteadOfGuessingLineNumbers() {
        ParsedDiff truncated = parse(
                "--- a/" + PATH,
                "+++ b/" + PATH,
                "@@ -1 +1 @@",
                " context",
                "@@ truncated @@",
                "+neverCounted",
                "@@ -5 +6 @@",
                "+kept");

        assertEquals(2, truncated.hunks.size());
        assertEquals(1, truncated.hunks.get(0).lines.size());
        assertTrue(truncated.truncated());
        assertLine(truncated.hunks.get(1).lines.get(0), ParsedDiff.LineKind.ADDED, 0, 6, "kept");

        ParsedDiff tooLarge = parse(
                "@@ -1 +1 @@",
                "@@ diff too large, showing first 2 lines of new content @@",
                "+neverCounted");
        assertEquals(1, tooLarge.hunks.size());
        assertTrue(tooLarge.hunks.get(0).lines.isEmpty());
        assertTrue(tooLarge.truncated());
    }

    /**
     * 超过 50 行的引用按"保留前 50 行再试"处理：这是一条有意的取舍，不是漏测。
     * 前 50 行仍精确指向同一段代码，而整段匹配通常会因为末尾多出来的围栏/复述而失败。
     */
    @Test
    void overlongSnippetIsCutToFiftyLinesBeforeMatching() {
        ParsedDiff diff = parse(
                "@@ -60 +60 @@",
                "-int value60;",
                "+int value60 = 60;");
        StringBuilder fileContent = new StringBuilder();
        for (int i = 1; i <= 60; i++) {
            fileContent.append(i == 60 ? "int value60 = 60;" : "int value" + i + ";").append('\n');
        }
        StringBuilder snippet = new StringBuilder();
        for (int i = 1; i <= 60; i++) {
            snippet.append("    int value").append(i).append(';').append('\n');
        }

        CommentLocator.Result result = locateInFile(diff, snippet.toString(), fileContent.toString());

        assertTrue(result.resolved, "前 50 行本身足以唯一定位");
        assertEquals("FILE", result.anchor);
        assertEquals(1, result.startLine);
        assertEquals(CommentLocator.MAX_SNIPPET_LINES, result.endLine);
    }

    /** LineDiff.unified() 每个 hunk 行都以换行结尾；结尾的空 token 不是一行，不能变成幽灵空行。 */
    @Test
    void trailingNewlineDoesNotBecomeAPhantomContextLine() {
        ParsedDiff diff = parse(
                "@@ -1 +1 @@",
                " context",
                "+added",
                "");

        assertEquals(2, diff.hunks.get(0).lines.size());
        assertEquals(2, diff.newSide().size());
        assertEquals(1, diff.oldSide().size());
    }

    @Test
    void nullAndEmptyInputsDegradeToUnplaced() {
        CommentLocator locator = new CommentLocator();
        assertFalse(locator.locate(null, "int x = 1;", FileContent.NONE).resolved);

        ParsedDiff diff = parse("@@ -1 +1 @@", "+int x = 1;");
        assertFalse(locator.locate(diff, null, FileContent.NONE).resolved);
        assertFalse(locator.locate(diff, "   \n\n\t", FileContent.NONE).resolved,
                "没有一行有效代码时按不可定位处理");
    }
}
