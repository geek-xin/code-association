package com.geek.association.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.geek.association.model.ImpactResult;
import com.geek.association.report.ImpactReport;
import com.geek.association.util.Json;
import com.geek.association.util.Texts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the three prompts the review pipeline needs: review, reflection, re-location.
 *
 * The fact catalog uses stable ids (C1 / I2 / B3) so a comment can name the evidence it rests on.
 * The ids are what makes the deterministic false-positive filter possible: a comment that cites an
 * id the report does not contain is discarded without asking the model anything.
 *
 * Everything in this class is pure text transformation. It never calls a model and never throws: a
 * malformed answer has to degrade to "no comments", because a review comment is an optional extra on
 * top of an analysis that already succeeded.
 */
public final class ReviewPromptBuilder {

    /** Same cap as the summary prompt: past the first dozen rows the model starts repeating itself. */
    private static final int MAX_CHANGES = 15;

    /** Same cap as the summary prompt; blind spots are a hint, not an inventory. */
    private static final int MAX_BLIND_SPOTS = 8;

    /** Hard cap even when the model ignores the request: nobody reads a report with 200 comments. */
    private static final int MAX_COMMENTS = 30;

    /** The fence marker, written once instead of being scattered through the prompt builders. */
    private static final String FENCE = "```";

    private static final Set<String> SEVERITIES =
            Set.of("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO");

    private static final Set<String> CATEGORIES = Set.of("CORRECTNESS", "SECURITY", "PERFORMANCE",
            "RELIABILITY", "MAINTAINABILITY", "IMPACT", "OTHER");

    private static final String DEFAULT_SEVERITY = "MAJOR";
    private static final String DEFAULT_CATEGORY = "OTHER";

    /**
     * The output contract, kept in one constant because the pipeline and the tests both depend on
     * the exact field names: {@code existing_code} is what {@link CommentLocator} consumes, and
     * {@code evidence} is what {@link ReviewFilter} validates. Renaming either silently disables a
     * deterministic guarantee, so the names live next to the explanation of why they exist.
     */
    private static final String COMMENTS_RULES = """
            == 输出要求（必须遵守）==
            1. 只返回严格 JSON：不要 markdown 围栏，不要 JSON 之外的任何解释文字。
            2. 顶层形状固定为 {"comments":[...]}；没有值得评论的问题时返回 {"comments":[]}。
            3. 每条评论对象的字段：
               severity      只能是 BLOCKER / CRITICAL / MAJOR / MINOR / INFO；
               category      只能是 CORRECTNESS / SECURITY / PERFORMANCE / RELIABILITY / MAINTAINABILITY / IMPACT / OTHER；
               title         一句话说清问题；
               detail        为什么是问题、会导致什么后果；
               suggestion    具体改法，可以为空字符串；
               file          必须是上面片段中出现过的文件路径；
               existing_code 从片段中逐字复制的连续代码行，不带 '+' / '-' 前缀、不带行号、不要改缩进；
               evidence      至少引用一个与本次片段同文件的 C 编号（例如 C1），没有证据就不要写这条评论。
            4. 只评论片段里出现的变更行及其上下文；禁止评论没有提供的代码。
            5. 禁止纯风格、命名品味、测试覆盖率一类的意见；没有问题就返回空数组。
            6. 行号由定位程序计算，你绝对不要输出行号，也不要输出 side/anchor。
            7. 一条评论只针对一处代码，不要把多个无关问题合并成一条。
            示例：
            {"comments":[{"severity":"MAJOR","category":"CORRECTNESS","title":"...","detail":"...","suggestion":"...","file":"path","existing_code":"...","evidence":["C1"]}]}
            """;

    /** The compact fact list plus the id -> file mapping the filter validates against. */
    public static final class FactCatalog {
        public final String text;
        /** Fact id (C1 / I2 / B3) -> file path; facts without a file are absent. */
        public final Map<String, String> idToFile;

        public FactCatalog(String text, Map<String, String> idToFile) {
            this.text = text == null ? "" : text;
            this.idToFile = idToFile == null ? Map.of() : idToFile;
        }
    }

    /** Fact catalog: changes, impacts and blind spots, capped exactly like the summary prompt. */
    public FactCatalog facts(ImpactReport report, int maxImpacts) {
        Map<String, String> idToFile = new LinkedHashMap<>();
        if (report == null) {
            return new FactCatalog("", idToFile);
        }
        StringBuilder sb = new StringBuilder();

        // C: what changed. The file is part of the fact because a comment on a different file has
        // to be detectable as a cross-file mistake without asking the model to justify itself.
        sb.append("== 变更（C）==\n");
        int changes = 0;
        if (report.changes != null) {
            for (ImpactReport.ChangeView change : report.changes) {
                if (change == null) {
                    continue;
                }
                if (changes >= MAX_CHANGES) {
                    break;
                }
                changes++;
                String id = "C" + changes;
                sb.append(id).append(" [").append(value(change.changeType)).append("] ")
                        .append(value(change.module)).append(' ')
                        .append(value(change.qualifiedName)).append(value(change.signature))
                        .append(" :: ").append(value(change.summary)).append('\n');
                sb.append("   文件: ").append(value(change.file)).append('\n');
                if (!Texts.isBlank(change.file)) {
                    idToFile.put(id, change.file.strip());
                }
            }
        }
        if (changes == 0) {
            sb.append("（无）\n");
        }

        // I: what else is affected. The whole path is included hop by hop: the review comment is
        // only credible if it can name the code that consumes the changed value, not just "something".
        sb.append("\n== 对其他程序的影响（I）==\n");
        int impacts = 0;
        int impactLimit = Math.max(0, maxImpacts);
        if (report.impacts != null) {
            for (ImpactReport.ImpactView impact : report.impacts) {
                if (impact == null) {
                    continue;
                }
                if (impacts >= impactLimit) {
                    break;
                }
                impacts++;
                String id = "I" + impacts;
                sb.append(id).append(" [").append(value(impact.riskLevel)).append(' ')
                        .append(Math.round(impact.riskScore)).append("] ")
                        .append(value(impact.targetModule)).append(' ')
                        .append(value(impact.targetSymbol)).append(value(impact.targetSignature))
                        .append(" | 类型 ").append(value(impact.impactType))
                        .append(" | 置信度 ").append(value(impact.confidence))
                        .append(" | 深度 ").append(impact.depth).append('\n');
                sb.append("   文件: ").append(value(impact.targetFile)).append('\n');
                sb.append("   原因: ").append(value(impact.reason)).append('\n');
                if (impact.path != null && !impact.path.isEmpty()) {
                    sb.append("   路径: ");
                    boolean first = true;
                    for (ImpactResult.PathStep step : impact.path) {
                        if (step == null) {
                            continue;
                        }
                        if (!first) {
                            sb.append(" -> ");
                        }
                        first = false;
                        sb.append(value(step.symbol));
                        if (!Texts.isBlank(step.file)) {
                            sb.append(" @").append(step.file.strip());
                            if (step.line > 0) {
                                sb.append(':').append(step.line);
                            }
                        }
                        if (!Texts.isBlank(step.usage) && !"NONE".equals(step.usage)) {
                            sb.append('[').append(step.usage.strip()).append(']');
                        }
                    }
                    sb.append('\n');
                }
                if (!Texts.isBlank(impact.targetFile)) {
                    idToFile.put(id, impact.targetFile.strip());
                }
            }
        }
        if (impacts == 0) {
            sb.append("（无）\n");
        }

        // B: what the analysis could not resolve. A reviewer has to know the clean result has holes.
        sb.append("\n== 分析盲区（B）==\n");
        int spots = 0;
        if (report.blindSpots != null) {
            for (ImpactReport.BlindSpot spot : report.blindSpots) {
                if (spot == null) {
                    continue;
                }
                if (spots >= MAX_BLIND_SPOTS) {
                    break;
                }
                spots++;
                String id = "B" + spots;
                sb.append(id).append(' ')
                        .append(Texts.isBlank(spot.file) ? "?" : spot.file.strip());
                if (spot.line > 0) {
                    sb.append(':').append(spot.line);
                }
                sb.append(' ').append(value(spot.expression))
                        .append(" (").append(value(spot.reason)).append(")\n");
                if (!Texts.isBlank(spot.file)) {
                    idToFile.put(id, spot.file.strip());
                }
            }
        }
        if (spots == 0) {
            sb.append("（无）\n");
        }
        return new FactCatalog(sb.toString(), idToFile);
    }

    /** One review prompt for a group of files whose hunks fit the token budget. */
    public String commentsPrompt(FactCatalog facts, List<ParsedDiff> diffs) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是资深 Java 代码评审员，只依据下面给出的静态分析事实和变更片段写评审意见。\n");
        sb.append("这些事实来自静态分析：不要推测事实之外的调用关系，也不要假装读过仓库里的其他文件。\n\n");
        sb.append("== 事实目录（evidence 只能引用这里的编号）==\n");
        sb.append(facts == null ? "" : facts.text);
        sb.append("\n== 本次送审的变更片段（+ 新增 / - 删除 / 空格 上下文）==\n");
        int rendered = 0;
        if (diffs != null) {
            for (ParsedDiff diff : diffs) {
                if (diff == null) {
                    continue;
                }
                rendered++;
                appendDiff(sb, diff, Integer.MAX_VALUE);
            }
        }
        if (rendered == 0) {
            sb.append("（没有可评审的片段）\n");
        }
        sb.append('\n').append(COMMENTS_RULES);
        return sb.toString();
    }

    /**
     * Reflection prompt: "which of these comments are factually wrong?" - not "which are weak".
     *
     * The distinction is the whole point of the pass. A model asked "which comments are bad" deletes
     * the ones it merely finds uninteresting, turning false positives into false negatives; so the
     * prompt names the three factual contradictions it may remove and explicitly protects the rest.
     */
    public String reflectionPrompt(List<ReviewComment> comments, Map<String, ParsedDiff> diffs) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是资深评审复核员。下面每条意见都声称针对某段代码；你的唯一任务是删除“事实错误”的意见。\n");
        sb.append("只按这三条标准删除：\n");
        sb.append("1) 意见指向的代码不在给出的片段里；\n");
        sb.append("2) 片段内容直接反驳意见的论断；\n");
        sb.append("3) 意见引用的符号或文件不存在。\n");
        sb.append("不要因为“价值低 / 无法验证 / 风格偏好 / 与其他意见重复”删除；拿不准时保留。\n");
        sb.append("只返回严格 JSON（不要围栏）：{\"remove\":[{\"id\":\"c1\",\"reason\":\"...\"}]}；");
        sb.append("没有要删的就返回 {\"remove\":[]}。\n");
        int index = 0;
        // One snippet per file, not one per comment: several comments usually target the same
        // changed file, and repeating its diff is the easiest way to blow the token budget the
        // reflection pass is supposed to respect.
        Map<String, ParsedDiff> usedDiffs = new LinkedHashMap<>();
        if (comments != null) {
            for (ReviewComment comment : comments) {
                if (comment == null) {
                    continue;
                }
                index++;
                sb.append("\n--- 意见 ").append(index).append(" ---\n");
                sb.append("id: ").append(value(comment.id)).append('\n');
                sb.append("文件: ").append(value(comment.file)).append('\n');
                sb.append("标题: ").append(value(comment.title)).append('\n');
                sb.append("正文: ").append(Texts.truncate(value(comment.detail), 600)).append('\n');
                if (!Texts.isBlank(comment.suggestion)) {
                    sb.append("建议: ").append(Texts.truncate(comment.suggestion, 300)).append('\n');
                }
                sb.append("引用代码:\n");
                sb.append(FENCE).append('\n')
                        .append(Texts.truncate(value(comment.existingCode), 800))
                        .append('\n').append(FENCE).append('\n');
                if (diffs != null && comment.file != null) {
                    ParsedDiff diff = diffs.get(comment.file);
                    if (diff != null) {
                        usedDiffs.putIfAbsent(comment.file, diff);
                    }
                }
            }
        }
        if (index == 0) {
            sb.append("\n（没有需要复核的意见）\n");
        } else if (!usedDiffs.isEmpty()) {
            sb.append("\n== 相关变更片段（按文件去重）==\n");
            for (ParsedDiff diff : usedDiffs.values()) {
                appendDiff(sb, diff, 60);
            }
        }
        return sb.toString();
    }

    /**
     * One re-location prompt for a comment whose snippet could not be placed.
     *
     * The model only re-extracts text from the hunk it is given; line numbers stay in the locator,
     * so a confused answer can at worst return an empty block, never a wrong anchor.
     */
    public String relocationPrompt(ReviewComment comment, ParsedDiff diff) {
        StringBuilder sb = new StringBuilder();
        sb.append("你在为一条评审意见做代码重定位。只允许从下面给出的变更片段里选择代码。\n");
        sb.append("任务：重新提取这条意见真正对应的连续代码行，逐字复制，不带 '+' / '-' 前缀，不带行号。\n");
        sb.append("只返回一个围栏代码块（内容即代码）：\n");
        sb.append(FENCE).append('\n').append("<代码>\n").append(FENCE).append('\n');
        sb.append("片段中不存在对应代码时返回空代码块：\n");
        sb.append(FENCE).append('\n').append(FENCE).append('\n');
        sb.append("不要解释，不要使用片段以外的代码，不要猜测行号。\n");
        if (comment != null) {
            sb.append("\n== 意见 ==\n");
            sb.append("标题: ").append(value(comment.title)).append('\n');
            sb.append("正文: ").append(Texts.truncate(value(comment.detail), 600)).append('\n');
            sb.append("原先引用的代码:\n");
            sb.append(FENCE).append('\n')
                    .append(Texts.truncate(value(comment.existingCode), 800))
                    .append('\n').append(FENCE).append('\n');
        }
        sb.append("\n== 文件片段 ==\n");
        if (diff == null) {
            sb.append("（没有可用片段）\n");
        } else {
            appendDiff(sb, diff, 160);
        }
        return sb.toString();
    }

    /** Parses the review answer into comments; tolerates fenced JSON and an object wrapper. */
    public static List<ReviewComment> parseComments(String response) {
        List<ReviewComment> out = new ArrayList<>();
        JsonNode root = extractJson(response);
        if (root == null) {
            return out;
        }
        JsonNode array = null;
        if (root.isArray()) {
            // Tolerated even though the contract asks for an object: some providers strip the wrapper.
            array = root;
        } else if (root.isObject()) {
            JsonNode comments = root.get("comments");
            if (comments != null && comments.isArray()) {
                array = comments;
            }
        }
        if (array == null) {
            return out;
        }
        int position = 0;
        for (JsonNode node : array) {
            if (out.size() >= MAX_COMMENTS) {
                break;
            }
            if (node == null || !node.isObject()) {
                continue;
            }
            position++;
            String title = field(node, "title");
            // Camel-case is accepted because providers occasionally reformat JSON keys; the wire
            // contract still says existing_code, which is what the prompt asked for.
            String existingCode = field(node, "existing_code");
            if (existingCode.isEmpty()) {
                existingCode = field(node, "existingCode");
            }
            // A comment without quoted code or a title cannot be placed or read, so it is not a
            // "bad comment" to filter later - it is not a comment at all.
            if (title.isEmpty() || existingCode.isEmpty()) {
                continue;
            }
            ReviewComment comment = new ReviewComment();
            String id = field(node, "id");
            // A generated id keeps the reflection correlation possible even when the model omits it.
            comment.id = id.isEmpty() ? "c" + position : id;
            comment.severity = normalizeSeverity(field(node, "severity"));
            comment.category = normalizeCategory(field(node, "category"));
            comment.title = title;
            comment.detail = field(node, "detail");
            comment.suggestion = field(node, "suggestion");
            comment.file = field(node, "file");
            comment.existingCode = existingCode;
            comment.evidence = stringArray(node.get("evidence"));
            comment.confidence = confidence(node.get("confidence"));
            out.add(comment);
        }
        return out;
    }

    /** Extracts the first fenced code block, which is how the re-location task returns the snippet. */
    public static String extractFencedCode(String response) {
        if (response == null) {
            return "";
        }
        int start = response.indexOf(FENCE);
        if (start < 0) {
            return response.strip();
        }
        int lineEnd = response.indexOf('\n', start + FENCE.length());
        if (lineEnd < 0) {
            // The whole fence is on one line (e.g. fence, code, fence); the language marker, if any,
            // cannot be separated from the code, so the content is returned as written.
            int close = response.indexOf(FENCE, start + FENCE.length());
            return close < 0 ? "" : response.substring(start + FENCE.length(), close).strip();
        }
        int close = response.indexOf(FENCE, lineEnd + 1);
        String body = close < 0 ? response.substring(lineEnd + 1) : response.substring(lineEnd + 1, close);
        return body.strip();
    }

    /** Parses the reflection answer into the ids the model says are factually wrong. */
    public static List<String> parseRemovalIds(String response) {
        List<String> out = new ArrayList<>();
        JsonNode root = extractJson(response);
        if (root == null || !root.isObject()) {
            return out;
        }
        JsonNode remove = root.get("remove");
        if (remove == null || !remove.isArray()) {
            return out;
        }
        for (JsonNode item : remove) {
            String id = null;
            if (item != null && item.isTextual()) {
                id = item.asText("").strip();
            } else if (item != null && item.isObject()) {
                JsonNode idNode = item.get("id");
                if (idNode != null && idNode.isValueNode() && !idNode.isNull()) {
                    id = idNode.asText("").strip();
                }
            }
            // A duplicate id would make the pipeline count the same deletion twice; the first wins.
            if (id != null && !id.isEmpty() && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * Renders one file's hunks with real diff prefixes.
     *
     * The model has to see the difference between context, added and deleted lines, because a
     * comment on deleted code is placed on the old side while everything else is placed on the new
     * side; stripping the prefixes would erase the only signal that distinction has.
     */
    private static void appendDiff(StringBuilder sb, ParsedDiff diff, int maxLines) {
        sb.append("\n### 文件: ").append(Texts.isBlank(diff.path) ? "?" : diff.path).append('\n');
        if (diff.hunks == null || diff.hunks.isEmpty()) {
            sb.append("（该文件没有可用片段）\n");
            return;
        }
        int printed = 0;
        for (ParsedDiff.Hunk hunk : diff.hunks) {
            if (hunk == null) {
                continue;
            }
            List<ParsedDiff.Line> lines = hunk.lines == null ? List.of() : hunk.lines;
            int oldCount = 0;
            int newCount = 0;
            for (ParsedDiff.Line line : lines) {
                if (line == null) {
                    continue;
                }
                if (line.kind != ParsedDiff.LineKind.ADDED) {
                    oldCount++;
                }
                if (line.kind != ParsedDiff.LineKind.DELETED) {
                    newCount++;
                }
            }
            if (printed >= maxLines) {
                sb.append("...（片段已截断）\n");
                return;
            }
            sb.append("@@ -").append(hunk.oldStart).append(',').append(oldCount)
                    .append(" +").append(hunk.newStart).append(',').append(newCount).append(" @@\n");
            printed++;
            for (ParsedDiff.Line line : lines) {
                if (line == null) {
                    continue;
                }
                if (printed >= maxLines) {
                    sb.append("...（片段已截断）\n");
                    return;
                }
                char prefix = switch (line.kind) {
                    case ADDED -> '+';
                    case DELETED -> '-';
                    case CONTEXT -> ' ';
                };
                sb.append(prefix).append(line.text == null ? "" : line.text).append('\n');
                printed++;
            }
        }
    }

    /** Finds the first JSON object/array in an answer that may also contain prose or a fence. */
    private static JsonNode extractJson(String response) {
        if (Texts.isBlank(response)) {
            return null;
        }
        String trimmed = response.strip();
        JsonNode direct = tryParse(trimmed);
        if (direct != null) {
            return direct;
        }
        String fenced = extractFencedCode(trimmed);
        if (!fenced.isEmpty() && !fenced.equals(trimmed)) {
            JsonNode node = tryParse(fenced);
            if (node != null) {
                return node;
            }
        }
        // Scanning every opening brace rather than only the first keeps a "}" in the prose before
        // the payload from truncating the JSON we try to parse.
        for (int i = 0; i < response.length(); i++) {
            char open = response.charAt(i);
            if (open != '{' && open != '[') {
                continue;
            }
            int end = response.lastIndexOf(open == '{' ? '}' : ']');
            if (end <= i) {
                continue;
            }
            JsonNode node = tryParse(response.substring(i, end + 1).strip());
            if (node != null) {
                return node;
            }
        }
        return null;
    }

    private static JsonNode tryParse(String candidate) {
        if (Texts.isBlank(candidate)) {
            return null;
        }
        try {
            JsonNode node = Json.mapper().readTree(candidate);
            // A bare string or number cannot carry comments; treat it as "no usable answer".
            return node != null && (node.isObject() || node.isArray()) ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String field(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return "";
        }
        return value.asText("").strip();
    }

    private static List<String> stringArray(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode item : node) {
            // Evidence is a list of fact ids; a number or an object in the array is model noise and
            // would otherwise become a filter reason the reviewer cannot understand.
            if (item != null && item.isTextual() && !item.asText("").isBlank()) {
                out.add(item.asText("").strip());
            }
        }
        return out;
    }

    private static double confidence(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0.5;
        }
        double value;
        if (node.isNumber()) {
            value = node.asDouble();
        } else if (node.isTextual()) {
            try {
                value = Double.parseDouble(node.asText("").strip());
            } catch (NumberFormatException e) {
                return 0.5;
            }
        } else {
            return 0.5;
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.5;
        }
        // The field is displayed as a confidence, not a provider-specific score; clamping keeps a
        // stray "95" from being rendered as 9500%.
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String normalizeSeverity(String raw) {
        String upper = upper(raw);
        return SEVERITIES.contains(upper) ? upper : DEFAULT_SEVERITY;
    }

    private static String normalizeCategory(String raw) {
        String upper = upper(raw);
        return CATEGORIES.contains(upper) ? upper : DEFAULT_CATEGORY;
    }

    private static String upper(String raw) {
        return raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
    }

    /** Null-safe fact text: the prompt must never contain "null" in place of a missing field. */
    private static String value(String raw) {
        return raw == null ? "" : raw.strip();
    }
}
