package com.geek.association.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window that puts source lines under an evidence reference.
 *
 * <p>Two things matter here and both are easy to get wrong silently: line numbers must stay the
 * file's real numbers (an off-by-one sends the reviewer to the wrong statement), and a path from a
 * report must never be able to climb out of the source root.</p>
 */
class CodeExcerptsTest {

    @Test
    void windowIsNumberedWithTheRealLineNumbers() {
        String text = lines(20);
        CodeExcerpts.Excerpt ex = CodeExcerpts.fromText("A.java", text, 15, 2, "abc12345");

        assertEquals(13, ex.startLine());
        assertEquals(17, ex.endLine());
        assertEquals(List.of(13, 14, 15, 16, 17), ex.lines().stream().map(CodeExcerpts.Line::number).toList());
        assertEquals("line 15", ex.lines().get(2).text());
        assertEquals("abc12345", ex.revision());
        assertEquals(null, ex.note());
    }

    @Test
    void windowDoesNotRunBeforeTheFirstLine() {
        CodeExcerpts.Excerpt ex = CodeExcerpts.fromText("A.java", lines(10), 2, 5, null);
        assertEquals(1, ex.startLine());
        assertEquals(1, ex.lines().get(0).number());
    }

    @Test
    void contextIsCappedSoOneEvidenceRowCannotBecomeAFileReader() {
        CodeExcerpts.Excerpt ex = CodeExcerpts.fromText("A.java", lines(100), 50, 99, null);
        assertEquals(50 - CodeExcerpts.MAX_CONTEXT, ex.startLine());
        assertEquals(50 + CodeExcerpts.MAX_CONTEXT, ex.endLine());
    }

    @Test
    void aLinePastTheEndIsReportedInsteadOfInvented() {
        CodeExcerpts.Excerpt ex = CodeExcerpts.fromText("A.java", lines(3), 9, 2, null);
        assertTrue(ex.lines().isEmpty());
        assertTrue(ex.note().contains("3"), ex.note());
    }

    @Test
    void crlfLineEndingsAreNotPartOfTheCode() {
        CodeExcerpts.Excerpt ex = CodeExcerpts.fromText("A.java", "a\r\nb\r\nc", 2, 0, null);
        assertEquals("b", ex.lines().get(0).text());
    }

    @Test
    void veryLongLinesAreCutAndMarked() {
        String longLine = "x".repeat(CodeExcerpts.MAX_LINE_CHARS + 50);
        CodeExcerpts.Excerpt ex = CodeExcerpts.fromText("A.java", longLine, 1, 0, null);
        assertEquals(CodeExcerpts.MAX_LINE_CHARS, ex.lines().get(0).text().length());
        assertTrue(ex.lines().get(0).truncated());
    }

    @Test
    void fileWindowsAreReadFromDiskWithTheSameRules(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("A.java");
        Files.writeString(file, lines(12));
        CodeExcerpts.Excerpt ex = CodeExcerpts.fromFile(file, "A.java", 5, 1);

        assertEquals(List.of(4, 5, 6), ex.lines().stream().map(CodeExcerpts.Line::number).toList());
        assertEquals("line 5", ex.lines().get(1).text());
        assertEquals(null, ex.revision());
    }

    @Test
    void aShortFileSaysWhyTheWindowStopsEarly(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("A.java");
        Files.writeString(file, lines(4));
        CodeExcerpts.Excerpt ex = CodeExcerpts.fromFile(file, "A.java", 4, 3);

        assertEquals(4, ex.endLine());
        assertTrue(ex.note().contains("4"), ex.note());
    }

    @Test
    void pathsThatLeaveTheSourceRootAreRejected() {
        for (String bad : List.of("../secret", "a/../../b", "/etc/passwd", "C:/Windows/win.ini", "")) {
            assertThrows(IllegalArgumentException.class, () -> CodeExcerpts.normalizeRelativePath(bad),
                    () -> "应该拒绝越界路径：" + bad);
        }
    }

    @Test
    void windowsSeparatorsAreNormalizedToForwardSlashes() {
        assertEquals("a/b/C.java", CodeExcerpts.normalizeRelativePath("a\\b\\C.java"));
        assertEquals("a/C.java", CodeExcerpts.normalizeRelativePath("./a/C.java"));
        assertFalse(CodeExcerpts.normalizeRelativePath("a/C.java").startsWith("/"));
    }

    // --------------------------------------------------------------- block mode

    @Test
    void blockModeReturnsTheWholeMethodWithACaption() {
        String src = """
                class A {
                    void one() {
                        int a = 1;
                    }

                    void two() {
                        call();
                    }
                }
                """;

        CodeExcerpts.Excerpt ex = CodeExcerpts.blockFromText("A.java", src, 7, "abc12345");

        assertTrue(ex.block());
        assertEquals("方法", ex.blockLabel());
        assertEquals(6, ex.startLine());
        assertEquals(8, ex.endLine());
        assertEquals(List.of(6, 7, 8), numbers(ex));
        assertEquals("方法 · 第 6–8 行", ex.caption());
        assertEquals(9, ex.totalLines());
        assertEquals("abc12345", ex.revision());
        assertNull(ex.note());
    }

    @Test
    void aOneLineMethodIsNamedByItsSingleLine() {
        String src = "class A {\n    void m() { call(); }\n}\n";

        CodeExcerpts.Excerpt ex = CodeExcerpts.blockFromText("A.java", src, 2, null);

        assertTrue(ex.block());
        assertEquals("方法 · 第 2 行", ex.caption());
    }

    @Test
    void blockModeReadsFromDiskWithTheSameRules(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("A.java");
        Files.writeString(file, "class A {\n    void m() {\n        call();\n    }\n}\n");

        CodeExcerpts.Excerpt ex = CodeExcerpts.blockFromFile(file, "A.java", 3);

        assertTrue(ex.block());
        assertEquals(2, ex.startLine());
        assertEquals(4, ex.endLine());
    }

    @Test
    void aSqlStatementIsReturnedAsOneBlock() {
        String src = "create table t (\n  id int,\n  name varchar(8)\n);\n";

        CodeExcerpts.Excerpt ex = CodeExcerpts.blockFromText("V1.sql", src, 2, null);

        assertTrue(ex.block());
        assertEquals(1, ex.startLine());
        assertEquals(4, ex.endLine());
        assertEquals("SQL 语句 · 第 1–4 行", ex.caption());
    }

    @Test
    void blockModeFallsBackToAWindowWhenTheFileHasNoBlocks() {
        CodeExcerpts.Excerpt ex = CodeExcerpts.blockFromText("app.properties", "a=1\nb=2\nc=3", 2, null);

        assertFalse(ex.block());
        assertEquals(1, ex.startLine());
        assertEquals(3, ex.endLine());
        assertEquals("引用行上下 " + CodeExcerpts.BLOCK_FALLBACK_CONTEXT + " 行", ex.caption());
    }

    @Test
    void aBlockLongerThanTheCapIsCutAroundTheCitedLineAndSaysSo() {
        StringBuilder sb = new StringBuilder("class Big {\n");
        for (int i = 2; i <= 400; i++) {
            sb.append("    // line ").append(i).append('\n');
        }
        sb.append("}\n");

        CodeExcerpts.Excerpt ex = CodeExcerpts.blockFromText("Big.java", sb.toString(), 300, null);

        assertTrue(ex.block());
        assertEquals(CodeExcerpts.MAX_BLOCK_LINES, ex.lines().size());
        assertTrue(ex.startLine() <= 300 && 300 <= ex.endLine());
        assertEquals("类型 · 共 401 行，展示第 220–379 行", ex.caption());
        assertEquals(401, ex.totalLines());
    }

    @Test
    void blockModeStillReportsALinePastTheEnd() {
        CodeExcerpts.Excerpt ex = CodeExcerpts.blockFromText("A.java", "class A {\n}", 9, null);

        assertTrue(ex.lines().isEmpty());
        assertTrue(ex.note().contains("2"), ex.note());
    }

    private static List<Integer> numbers(CodeExcerpts.Excerpt ex) {
        return ex.lines().stream().map(CodeExcerpts.Line::number).toList();
    }

    private static String lines(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            sb.append("line ").append(i);
            if (i < count) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
