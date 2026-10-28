package com.geek.association.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 里程碑日志的格式契约。
 *
 * 这些断言存在的理由不是"格式好看"，而是日志是事后唯一能查的证据：一旦引号规则或字段顺序变了，
 * 文档里的 grep / awk 配方与历史日志同时失效，而没有人会在改这一行时想到这一点。
 */
class LogEventsTest {

    @Test
    void rendersEventThenKeyValuePairs() {
        String line = LogEvents.of("analysis.done", "analysisId", 42, "source", "order-service",
                "durationMs", 1234);
        assertEquals("event=analysis.done analysisId=42 source=order-service durationMs=1234", line);
    }

    @Test
    void quotesValuesThatWouldBreakSpaceSplitting() {
        assertEquals("event=scan.done reason=\"no new commits\"",
                LogEvents.of("scan.done", "reason", "no new commits"));
        assertEquals("event=x path=\"C:\\\\repo\"",
                LogEvents.of("x", "path", "C:\\repo"));
        assertEquals("event=x ref=\"a=b\"", LogEvents.of("x", "ref", "a=b"));
        assertEquals("event=x name=\"say \\\"hi\\\"\"", LogEvents.of("x", "name", "say \"hi\""));
    }

    @Test
    void escapesControlCharactersOntoOneLine() {
        assertEquals("event=x detail=\"a\\nb\"", LogEvents.of("x", "detail", "a\nb"));
    }

    @Test
    void distinguishesMissingFromEmpty() {
        // "没有这个值" 与 "值就是空" 在排障时是两件事：前者是没传，后者是传了空。
        assertEquals("event=x base=-", LogEvents.of("x", "base", null));
        assertEquals("event=x base=\"\"", LogEvents.of("x", "base", ""));
    }

    @Test
    void truncatesValuesThatWouldSwampTheLine() {
        String longText = "x".repeat(LogEvents.MAX_VALUE_LENGTH + 50);
        String line = LogEvents.of("x", "detail", longText);
        assertTrue(line.endsWith("..."), line);
        assertEquals("event=x detail=".length() + LogEvents.MAX_VALUE_LENGTH + 3, line.length());
    }

    @Test
    void rejectsMalformedCallsEarly() {
        // 打错的字段应该在校验里炸掉，而不是写出一条没人能解析的日志。
        assertThrows(IllegalArgumentException.class, () -> LogEvents.of("", "a", 1));
        assertThrows(IllegalArgumentException.class, () -> LogEvents.of(null));
        assertThrows(IllegalArgumentException.class, () -> LogEvents.of("x", "a"));
    }

    @Test
    void durationIsNeverNegative() {
        assertTrue(LogEvents.msSince(System.currentTimeMillis()) >= 0);
        // 时钟回拨（NTP 校正）不能让日志出现 durationMs=-3000，那读起来像分析本身的 bug。
        assertEquals(0, LogEvents.msSince(System.currentTimeMillis() + 60_000));
    }
}
