package com.geek.association.util;

/**
 * Formats the milestone lines operators grep when they analyse a run after the fact.
 *
 * 日志约定（见 docs/wiki/日志规范.md）：凡是"重要节点"都写一行 {@code event=<名字> key=value ...}，
 * 而不是一句自然语言。理由是排障时的问题几乎总是"某次分析/某次扫描到底发生了什么"，
 * 而 {@code grep 'event=analysis.done' logs/code-association.log} 能一次拿到全部结果，
 * 用 awk 按 {@code key=} 切字段也不用再写正则去猜句子结构。自然语言留给细节说明，
 * 结构化行留给"会被反复查询"的节点。
 *
 * 约定：
 * <ul>
 *   <li>事件名小写、点分、稳定：改名等于让历史日志与文档同时失效，所以宁可变字段不改事件；</li>
 *   <li>字段名 camelCase，与代码里的概念同名（analysisId / source / durationMs）；</li>
 *   <li>值含空格、引号或等号时用双引号包起来并转义，保证一条日志仍然能按空白切分；</li>
 *   <li>null 写 {@code -}，空串写 {@code ""}：两者在排障时含义不同（"没有这个值" vs "值就是空"）。</li>
 * </ul>
 *
 * 单行长度上限 {@value #MAX_VALUE_LENGTH} 字符：一条日志塞进几 KB 的路径或异常消息会淹没上下文，
 * 需要完整内容的地方（堆栈、报告）另有归属。
 */
public final class LogEvents {

    /** Longest rendered value; longer text is truncated with an ellipsis. */
    static final int MAX_VALUE_LENGTH = 200;

    private LogEvents() {
    }

    /**
     * Renders one milestone line body, without the timestamp/level prefix the appender adds.
     *
     * @param event stable event name, e.g. {@code analysis.done}
     * @param pairs alternating key/value pairs; keys are printed as given
     */
    public static String of(String event, Object... pairs) {
        if (event == null || event.isBlank()) {
            throw new IllegalArgumentException("event name is required");
        }
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("fields must be key/value pairs, got " + pairs.length + " values");
        }
        StringBuilder sb = new StringBuilder(32 + pairs.length * 12);
        sb.append("event=").append(event);
        for (int i = 0; i < pairs.length; i += 2) {
            sb.append(' ').append(pairs[i]).append('=').append(render(pairs[i + 1]));
        }
        return sb.toString();
    }

    /**
     * Milliseconds since a {@code System.currentTimeMillis()} stamp, for the {@code durationMs} field.
     *
     * A clock that jumped backwards (NTP correction) would otherwise produce a negative duration,
     * which reads as a bug in the analysis rather than in the clock.
     */
    public static long msSince(long startMillis) {
        return Math.max(0L, System.currentTimeMillis() - startMillis);
    }

    private static String render(Object value) {
        if (value == null) {
            return "-";
        }
        String text = value.toString();
        if (text.isEmpty()) {
            return "\"\"";
        }
        if (text.length() > MAX_VALUE_LENGTH) {
            text = text.substring(0, MAX_VALUE_LENGTH) + "...";
        }
        if (!needsQuoting(text)) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length() + 8);
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** Whitespace or a quote/equals sign would make the line ambiguous to split on spaces. */
    private static boolean needsQuoting(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '"' || c == '=' || c == '\\' || Character.isISOControl(c)) {
                return true;
            }
        }
        return false;
    }
}
