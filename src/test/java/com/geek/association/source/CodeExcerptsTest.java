package com.geek.association.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
