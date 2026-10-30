package com.geek.association.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 引用行所在的代码块边界。
 *
 * <p>证据面板原来只给引用行上下各 3 行，读到的往往是半句 {@code if} 或半个别名——想知道"这行到底在哪个方法里"
 * 还得回仓库打开文件。这里把"引用行属于哪一块"算出来，让片段直接以方法 / 类型 / XML 元素的边界开头和结尾。</p>
 *
 * <p>三套边界，按文件类型选一套：</p>
 * <ul>
 *   <li><b>花括号块</b>：Java / JS / TS / CSS / JSON 等。字符串、字符、行注释、块注释、Java 文本块里的花括号
 *       不参与配对，否则一行 {@code String s = "}";} 就能把整段代码切错。包含引用行的候选块从最内层向外找，
 *       第一个"声明块"（方法体、构造器、类型体、初始化块）就是答案；{@code if/for/while/...} 这类控制块
 *       只是中途站，lambda 体与匿名类体同样继续向外——读者要的是"这个方法做了什么"，不是"这个 if 里写了什么"。</li>
 *   <li><b>XML 元素</b>：MyBatis mapper、Spring 配置、HTML。按标签配对找到包含引用行的元素，取不超过上限的
 *       最外层那个（通常是一整条 {@code <select>}），注释与 CDATA 里的标签不算。</li>
 *   <li><b>SQL 语句</b>：{@code .sql} 脚本按分号切语句。一条 {@code CREATE TABLE} 常常二十多行，
 *       10 行窗口会把列定义切成两半；字符串与注释里的分号不算结束。</li>
 * </ul>
 *
 * <p>三种都不成立时返回 {@code null}（{@code .properties} / {@code .yml} 没有块边界，
 * 引用行落在两条 SQL 语句之间也一样），由调用方退化成按行窗口。</p>
 */
final class CodeBlocks {

    /** 控制块：出现在它们里面不代表读者已经拿到足够的上下文，继续向外扩。 */
    private static final String[] CONTROL_KEYWORDS = {
            "if", "else", "for", "while", "do", "switch", "case", "try", "catch", "finally", "synchronized"
    };

    /** 声明块的开头可能被拆成多行（注解、参数换行），最多回看几行找签名。 */
    private static final int SIGNATURE_LOOKBACK = 6;

    private static final List<String> MARKUP_EXTENSIONS = List.of(
            ".xml", ".html", ".htm", ".xhtml", ".xsd", ".jsp", ".ftl", ".vm", ".tld");

    private CodeBlocks() {
    }

    /**
     * 一块闭合的源码。
     *
     * @param start 起始行（1 起，含）
     * @param end   结束行（1 起，含）
     * @param label 给人看的块性质：{@code 方法} / {@code 类型} / {@code 代码块} / {@code 元素 xxx}
     */
    record Block(int start, int end, String label) {

        int size() {
            return end - start + 1;
        }
    }

    /**
     * 找出包含 {@code line} 的代码块。
     *
     * @param path     文件路径，只用来判断是不是 XML / HTML
     * @param lines    文件全文，{@code lines[i]} 是第 {@code i + 1} 行
     * @param line     引用行（1 起）
     * @param maxLines 选块时的上限；XML 用它挑"最外层但不至于太大"的元素，花括号块不因它改变选择
     * @return 找不到块边界时返回 {@code null}
     */
    static Block enclosing(String path, String[] lines, int line, int maxLines) {
        if (lines == null || lines.length == 0 || line < 1 || line > lines.length) {
            return null;
        }
        if (isMarkup(path, lines)) {
            return xmlBlock(lines, line, Math.max(1, maxLines));
        }
        if (isSql(path)) {
            return sqlBlock(lines, line);
        }
        return braceBlock(lines, line, Math.max(1, maxLines));
    }

    // ------------------------------------------------------------------ 花括号

    private static Block braceBlock(String[] lines, int line, int maxLines) {
        List<int[]> containing = containing(bracePairs(lines), line);
        if (containing.isEmpty()) {
            return null;
        }
        for (int[] pair : containing) {
            if (declarationLike(lines[pair[0] - 1])) {
                return block(lines, pair);
            }
        }
        // 没有声明块（比如字段初始化、类体直接写在文件里）：取能放下的最大一块，放不下就取最内层，
        // 由调用方按上限在引用行附近截断。
        int[] chosen = containing.get(0);
        for (int[] pair : containing) {
            if (pair[1] - pair[0] + 1 <= maxLines) {
                chosen = pair;
            }
        }
        return block(lines, chosen);
    }

    /**
     * 扫描全文，给出每一对匹配的 {@code { ... }} 的起止行。
     *
     * <p>不追求完整的词法分析，只做一件事：不要把字符串 / 注释里的花括号当成代码结构。
     * 代价是一行写坏（引号没闭合）时后续行会被当成字符串——这种文件本来也解析不出正确的块。</p>
     */
    private static List<int[]> bracePairs(String[] lines) {
        List<Integer> open = new ArrayList<>();
        List<int[]> pairs = new ArrayList<>();
        boolean blockComment = false;
        boolean textBlock = false;
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i];
            int n = s.length();
            char quote = 0;
            for (int j = 0; j < n; j++) {
                char c = s.charAt(j);
                if (blockComment) {
                    if (c == '*' && j + 1 < n && s.charAt(j + 1) == '/') {
                        blockComment = false;
                        j++;
                    }
                    continue;
                }
                if (textBlock) {
                    if (c == '"' && j + 2 < n && s.charAt(j + 1) == '"' && s.charAt(j + 2) == '"') {
                        textBlock = false;
                        j += 2;
                    }
                    continue;
                }
                if (quote != 0) {
                    if (c == '\\') {
                        j++;
                    } else if (c == quote) {
                        quote = 0;
                    }
                    continue;
                }
                if (c == '/' && j + 1 < n) {
                    char next = s.charAt(j + 1);
                    if (next == '/') {
                        break;
                    }
                    if (next == '*') {
                        blockComment = true;
                        j++;
                        continue;
                    }
                }
                if (c == '"') {
                    if (j + 2 < n && s.charAt(j + 1) == '"' && s.charAt(j + 2) == '"') {
                        textBlock = true;
                        j += 2;
                    } else {
                        quote = '"';
                    }
                    continue;
                }
                if (c == '\'') {
                    quote = '\'';
                    continue;
                }
                if (c == '{') {
                    open.add(i + 1);
                } else if (c == '}' && !open.isEmpty()) {
                    pairs.add(new int[]{open.remove(open.size() - 1), i + 1});
                }
            }
        }
        return pairs;
    }

    /**
     * 把开括号所在行扩成"整个声明"的起始行。
     *
     * <p>签名换行时（参数一行一个、注解单独一行），{@code {} 落在最后一行，只从那里开始贴片段就丢掉了
     * 方法名和返回类型——而"这是哪个方法"正是读者要判断的事。所以往上收连续行，直到遇到空行或上一条
     * 语句的结尾。最多回看 {@link #SIGNATURE_LOOKBACK} 行，避免把上一段代码卷进来。</p>
     */
    private static Block block(String[] lines, int[] pair) {
        int start = pair[0];
        int lowest = Math.max(1, pair[0] - SIGNATURE_LOOKBACK);
        for (int n = pair[0] - 1; n >= lowest; n--) {
            String prev = lines[n - 1].strip();
            if (prev.isEmpty() || prev.endsWith(";") || prev.endsWith("{") || prev.endsWith("}")
                    || prev.endsWith("*/") || startsWithControl(prev.toLowerCase(Locale.ROOT))) {
                break;
            }
            start = n;
        }
        return new Block(start, pair[1], label(lines, pair[0], start));
    }

    /** 包含引用行的块，最内层在前。 */
    private static List<int[]> containing(List<int[]> pairs, int line) {
        List<int[]> out = new ArrayList<>();
        for (int[] pair : pairs) {
            if (pair[0] <= line && line <= pair[1]) {
                out.add(pair);
            }
        }
        out.sort((a, b) -> Integer.compare(a[1] - a[0], b[1] - b[0]));
        return out;
    }

    /**
     * 这一行的花括号是不是"一个声明"的开头（方法、构造器、类型、初始化块），而不是控制流。
     *
     * <p>{@code } else {} 这种把上一个块的结尾和下一个块的开始写在同一行的，先去掉前导 {@code }} 再判断，
     * 否则 {@code else} 会被当成声明块，读者只能看到 else 那几行。</p>
     */
    private static boolean declarationLike(String raw) {
        String t = stripLeadingClosers(raw);
        if (t.isEmpty()) {
            return false;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        if (startsWithControl(lower)) {
            return false;
        }
        if (t.contains("->")) {
            return false;
        }
        // new Runnable() { ... }：匿名类体，继续向外找到所在方法更有用。
        return !lower.matches("(?s).*\\bnew\\b[^;]*\\{\\s*$");
    }

    private static String stripLeadingClosers(String raw) {
        String t = raw == null ? "" : raw.strip();
        while (t.startsWith("}")) {
            t = t.substring(1).strip();
        }
        return t;
    }

    private static boolean startsWithControl(String lower) {
        for (String keyword : CONTROL_KEYWORDS) {
            if (lower.startsWith(keyword)
                    && (lower.length() == keyword.length()
                    || !Character.isLetterOrDigit(lower.charAt(keyword.length())))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 给人看的块性质：从声明起始行读到开括号那一行，第一个像签名的行决定它是方法还是类型。
     *
     * <p>先看签名再看类型关键字：{@code void f(Record r) {}} 里的 {@code Record} 是参数类型，
     * 不是"record 声明"。</p>
     */
    private static String label(String[] lines, int openLine, int startLine) {
        for (int n = startLine; n <= openLine; n++) {
            String text = lines[n - 1].strip();
            String lower = text.toLowerCase(Locale.ROOT);
            if (looksLikeSignature(text, lower)) {
                return "方法";
            }
            if (containsTypeKeyword(text)) {
                return "类型";
            }
        }
        return "代码块";
    }

    private static boolean looksLikeSignature(String text, String lower) {
        return text.contains("(") && !startsWithControl(lower);
    }

    /**
     * 类型声明的关键字后面必须跟一个大写开头的名字：{@code class A} / {@code record Point} /
     * {@code interface Foo}。这样 {@code void f(Record r)} 里的参数类型不会被当成类型声明。
     */
    private static boolean containsTypeKeyword(String text) {
        return text.matches("(?s).*\\b(class|interface|enum|record)\\s+[A-Z].*");
    }

    // ---------------------------------------------------------------------- XML

    private static Block xmlBlock(String[] lines, int line, int maxLines) {
        List<Element> containing = new ArrayList<>();
        for (Element element : xmlElements(lines)) {
            if (element.start <= line && line <= element.end) {
                containing.add(element);
            }
        }
        if (containing.isEmpty()) {
            return null;
        }
        containing.sort((a, b) -> Integer.compare(a.size(), b.size()));
        // 根元素（<mapper> / <beans> / <html>）就是整个文件，把它整段贴出来等于把证据面板变成
        // 文件阅读器，而判断一条 SQL 影响要的是那一条 <select>。所以从"最外层但非根"开始挑：
        // 能放下的最大一个；都放不下就退回最内层，由调用方按上限截断。
        Element root = containing.get(containing.size() - 1);
        Element chosen = null;
        for (Element element : containing) {
            if (element != root && element.size() <= maxLines) {
                chosen = element;
            }
        }
        if (chosen == null) {
            chosen = containing.get(0);
        }
        return new Block(chosen.start, chosen.end, "元素 " + chosen.name);
    }

    private record Element(int start, int end, String name) {
        int size() {
            return end - start + 1;
        }
    }

    /**
     * 扫描全文，给出每个闭合标签的起止行。
     *
     * <p>不做完整 XML 解析：只跳过注释与 CDATA，把 {@code <name ...>} 压栈、{@code </name>} 出栈。
     * 跨行写属性（{@code >} 不在同一行）的标签会被忽略——那种写法在 mapper 里很少见，
     * 忽略的后果只是这一处退回按行窗口，不会给出错误的边界。</p>
     */
    private static List<Element> xmlElements(String[] lines) {
        List<String> names = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        List<Element> out = new ArrayList<>();
        boolean comment = false;
        boolean cdata = false;
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i];
            int n = s.length();
            int j = 0;
            while (j < n) {
                if (comment) {
                    int end = s.indexOf("-->", j);
                    if (end < 0) {
                        j = n;
                    } else {
                        comment = false;
                        j = end + 3;
                    }
                    continue;
                }
                if (cdata) {
                    int end = s.indexOf("]]>", j);
                    if (end < 0) {
                        j = n;
                    } else {
                        cdata = false;
                        j = end + 3;
                    }
                    continue;
                }
                int lt = s.indexOf('<', j);
                if (lt < 0) {
                    break;
                }
                if (s.startsWith("<!--", lt)) {
                    comment = true;
                    j = lt + 4;
                    continue;
                }
                if (s.startsWith("<![CDATA[", lt)) {
                    cdata = true;
                    j = lt + 9;
                    continue;
                }
                int gt = s.indexOf('>', lt);
                if (gt < 0) {
                    break;
                }
                String tag = s.substring(lt + 1, gt).strip();
                j = gt + 1;
                if (tag.isEmpty() || tag.startsWith("?") || tag.startsWith("!")) {
                    continue;
                }
                if (tag.startsWith("/")) {
                    close(names, starts, out, tag.substring(1).strip(), i + 1);
                    continue;
                }
                if (tag.endsWith("/")) {
                    continue;
                }
                names.add(tagName(tag));
                starts.add(i + 1);
            }
        }
        return out;
    }

    private static void close(List<String> names, List<Integer> starts, List<Element> out,
                              String name, int line) {
        for (int k = names.size() - 1; k >= 0; k--) {
            if (names.get(k).equals(name)) {
                out.add(new Element(starts.get(k), line, name));
                names.subList(k, names.size()).clear();
                starts.subList(k, starts.size()).clear();
                return;
            }
        }
    }

    private static String tagName(String tag) {
        for (int k = 0; k < tag.length(); k++) {
            char c = tag.charAt(k);
            if (Character.isWhitespace(c)) {
                return tag.substring(0, k);
            }
        }
        return tag;
    }

    // ---------------------------------------------------------------------- SQL

    private static boolean isSql(String path) {
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".sql");
    }

    /**
     * SQL 脚本按语句取整块。
     *
     * <p>一条 {@code CREATE TABLE} 常常二十多行，按 10 行窗口取会把列定义切成两半，读者反而更不确定。
     * 语句边界是分号：字符串、引号标识符、行注释（{@code --} / {@code #}）与块注释里的分号不算结束。引用行落在两条语句之间的注释或空行上时没有"所在语句"，返回 {@code null}
     * 让调用方退化成窗口。</p>
     */
    private static Block sqlBlock(String[] lines, int line) {
        for (int[] statement : sqlStatements(lines)) {
            if (statement[0] <= line && line <= statement[1]) {
                return new Block(statement[0], statement[1], "SQL 语句");
            }
        }
        return null;
    }

    /** 每条语句的起止行；没有分号结尾的最后一条也算一条（脚本经常省掉最后一个分号）。 */
    private static List<int[]> sqlStatements(String[] lines) {
        List<int[]> out = new ArrayList<>();
        int start = 0;
        boolean blockComment = false;
        // 字符串可以跨行，引号状态必须跟着文件走；行注释只到行尾，所以它留在行内。
        char quote = 0;
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i];
            int n = s.length();
            boolean lineComment = false;
            boolean seenContent = false;
            for (int j = 0; j < n && !lineComment; j++) {
                char c = s.charAt(j);
                if (blockComment) {
                    if (c == '*' && j + 1 < n && s.charAt(j + 1) == '/') {
                        blockComment = false;
                        j++;
                    }
                    continue;
                }
                if (quote != 0) {
                    if (c == quote) {
                        // SQL 里两个连续引号是转义后的一个引号，不是语句结束。
                        if (j + 1 < n && s.charAt(j + 1) == quote) {
                            j++;
                        } else {
                            quote = 0;
                        }
                    }
                    continue;
                }
                if (c == '-' && j + 1 < n && s.charAt(j + 1) == '-') {
                    lineComment = true;
                    continue;
                }
                // # 只在行首当注释（MySQL 写法）。SQL Server 的临时表 `#temp` 出现在语句中间，
                // 一律当注释会把整条建表语句吃掉。
                if (c == '#' && !seenContent) {
                    lineComment = true;
                    continue;
                }
                if (c == '/' && j + 1 < n && s.charAt(j + 1) == '*') {
                    blockComment = true;
                    j++;
                    continue;
                }
                if (c == '\'' || c == '"') {
                    quote = c;
                    if (start == 0) {
                        start = i + 1;
                    }
                    continue;
                }
                if (c == ';') {
                    if (start != 0) {
                        out.add(new int[]{start, i + 1});
                        start = 0;
                    }
                    continue;
                }
                if (!Character.isWhitespace(c)) {
                    seenContent = true;
                    if (start == 0) {
                        start = i + 1;
                    }
                }
            }
        }
        if (start != 0) {
            out.add(new int[]{start, lines.length});
        }
        return out;
    }

    // -------------------------------------------------------------------- 判定

    /** XML / HTML 只看扩展名与前几行，避免把 Java 泛型 {@code List<String>} 当成标签。 */
    private static boolean isMarkup(String path, String[] lines) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        for (String ext : MARKUP_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        for (String raw : lines) {
            String t = raw.strip();
            if (t.isEmpty()) {
                continue;
            }
            String tLower = t.toLowerCase(Locale.ROOT);
            return tLower.startsWith("<?xml") || tLower.startsWith("<!doctype");
        }
        return false;
    }
}
