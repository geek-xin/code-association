package com.geek.association.report;

import java.util.List;
import java.util.Map;

/**
 * Renders the report as a pull request comment.
 *
 * A CI job can post this directly to a GitLab MR or a GitHub PR: it leads with the decision
 * (merge / review / blocked), then shows the module map as a table and the highest risk paths,
 * and finally states the analysis limits so a reviewer knows what was not checked.
 */
public final class MarkdownReport {

    /** Markdown inline code delimiter, kept in a constant so the source stays readable. */
    private static final String CODE = String.valueOf((char) 96);

    private static final Map<String, String> RISK_ICON = Map.of(
            "CRITICAL", "🔴", "HIGH", "🟠", "MEDIUM", "🟡", "LOW", "🟢", "INFO", "⚪");
    private static final Map<String, String> TYPE_LABEL = Map.of(
            "COMPILE", "编译", "BEHAVIOR", "行为", "API_CONTRACT", "接口",
            "DATA", "数据", "CONFIG", "配置", "TYPE", "类型", "STRUCTURE", "结构", "SECURITY", "安全",
            "MESSAGE_CONTRACT", "消息契约", "MESSAGE_BEHAVIOR", "消息行为");

    /**
     * Severity of a review comment, mapped to the same colour language as impact risk levels so a
     * reader does not have to learn a second scale for the new section.
     */
    private static final Map<String, String> COMMENT_ICON = Map.of(
            "BLOCKER", "🔴", "CRITICAL", "🔴", "MAJOR", "🟠", "MINOR", "🟡", "INFO", "⚪");

    /** Where the line number came from; the reader needs to know how much to trust the anchor. */
    private static final Map<String, String> ANCHOR_LABEL = Map.of(
            "HUNK", "变更块内匹配", "FILE", "整文件匹配", "RELOCATED", "模型重定位", "NONE", "未定位");

    /** Hard stop for the PR comment: a runaway list must not push the analysis limits off screen. */
    private static final int MAX_COMMENTS = 50;

    private MarkdownReport() {
    }

    public static String render(ImpactReport report, int maxImpacts) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 其他程序变更影响分析\n\n");
        sb.append("**结论：** ").append(decision(report)).append("\n\n");
        sb.append("| 指标 | 值 |\n|---|---|\n");
        sb.append("| 分析模式 | ").append(nullSafe(report.mode))
                .append(report.pullRequest == null ? "" : " #" + report.pullRequest).append(" |\n");
        sb.append("| 基线 → 目标 | ").append(shortCommit(report.baseCommit)).append(" → ")
                .append(shortCommit(report.headCommit)).append(" |\n");
        if (report.commitCount > 0) {
            // The commit count is the size of the review, which the file list alone cannot convey:
            // "3 files" is a very different ask when it spans forty commits.
            sb.append("| 提交数 | ").append(report.commitCount)
                    .append(report.rangeNote == null || report.rangeNote.isBlank()
                            ? "" : "（" + report.rangeNote + "）").append(" |\n");
        }
        sb.append("| 对比程序 / 命中契约 | ").append(report.summary.comparedPrograms).append(" / ")
                .append(report.summary.matchedContracts).append(" |\n");
        sb.append("| 变更模块 / 符号 | ").append(report.summary.changedModules).append(" / ")
                .append(report.summary.changedSymbols).append(" |\n");
        sb.append("| 受影响程序模块 / 符号 | ").append(report.summary.impactedModules).append(" / ")
                .append(report.summary.impactedSymbols).append(" |\n");
        sb.append("| 风险分布 | 高 ").append(report.summary.highRisk).append(" · 中 ")
                .append(report.summary.mediumRisk).append(" · 低 ").append(report.summary.lowRisk).append(" |\n");
        sb.append("| 接口 / SQL / 配置变化 | ").append(report.summary.apiChanges).append(" / ")
                .append(report.summary.sqlChanges).append(" / ").append(report.summary.configChanges)
                .append(" · 消息契约 ").append(report.summary.messageChanges).append(" |\n\n");

        if (!report.commits.isEmpty()) {
            // Commits come before the impact tables on purpose: a reviewer reads "what am I
            // approving" before "what does it break".
            sb.append("### 本次提交范围\n\n");
            for (com.geek.association.model.CommitInfo commit : report.commits) {
                sb.append("- ").append(code(commit.shortSha)).append(' ')
                        .append(commit.subject == null ? "" : commit.subject);
                if (commit.author != null && !commit.author.isBlank()) {
                    sb.append(" — ").append(commit.author);
                }
                sb.append('\n');
            }
            sb.append('\n');
        }

        if (!report.moduleImpacts.isEmpty()) {
            sb.append("### 其他程序的模块影响\n\n");
            sb.append("| 其他程序 | 模块 | 风险 | 影响符号 | 最深层级 | 影响类型 |\n|---|---|---|---|---|---|\n");
            for (ImpactReport.ModuleImpact module : report.moduleImpacts) {
                sb.append("| ").append(code(module.peerSourceName == null ? "-" : module.peerSourceName))
                        .append(" | ").append(code(module.module))
                        .append(module.changed ? " _(本次变更)_" : "").append(" | ")
                        .append(RISK_ICON.getOrDefault(module.riskLevel, "")).append(' ')
                        .append(module.riskLevel).append(' ').append(module.maxRisk).append(" | ")
                        .append(module.impactedSymbols).append(" | ").append(module.maxDepth).append(" | ")
                        .append(labels(module.impactTypes)).append(" |\n");
            }
            sb.append('\n');
        }

        sb.append("### 影响明细\n\n");
        int count = 0;
        for (ImpactReport.ImpactView impact : report.impacts) {
            if (count++ >= maxImpacts) {
                break;
            }
            sb.append(count).append(". ").append(RISK_ICON.getOrDefault(impact.riskLevel, "")).append(' ')
                    .append("**").append(impact.riskLevel).append(' ').append(Math.round(impact.riskScore))
                    .append("** · ").append(TYPE_LABEL.getOrDefault(impact.impactType, impact.impactType))
                    .append(" · 置信度 ").append(impact.confidence).append('\n');
            sb.append("   - 位置：").append(code(impact.peerSourceName == null ? impact.targetModule : impact.peerSourceName))
                    .append(" / ").append(code(impact.peerModule == null ? impact.targetModule : impact.peerModule)).append(' ')
                    .append(code(impact.targetSymbol + (impact.targetSignature == null ? "" : impact.targetSignature)))
                    .append(" — ").append(code(impact.targetFile + ":" + impact.targetLine)).append('\n');
            sb.append("   - 原因：").append(impact.reason).append('\n');
            if (impact.path != null && !impact.path.isEmpty()) {
                // stored nearest-first; rendered as "changed symbol -> consumer -> ... -> target"
                sb.append("   - 影响路径：").append(code(impact.changedSymbol));
                for (int i = 0; i < impact.path.size(); i++) {
                    var step = impact.path.get(i);
                    sb.append(" → ").append(code(step.symbol));
                    if (step.usage != null && !"NONE".equals(step.usage)) {
                        sb.append(" [").append(step.usage).append(']');
                    }
                }
                sb.append('\n');
            }
            if (impact.suggestedTests != null && !impact.suggestedTests.isEmpty()) {
                sb.append("   - 建议验证：").append(String.join(", ",
                        impact.suggestedTests.stream().map(MarkdownReport::code).toList())).append('\n');
            }
        }
        if (report.impacts.isEmpty()) {
            sb.append("本次变更未发现对其他程序的影响。\n");
        } else if (report.impactsTotal > Math.min(count, maxImpacts)) {
            // The export has its own row limit (a PR comment cannot hold thousands of rows), and the
            // stored report is capped too. State both numbers so the omitted rows are never read as
            // "the analysis found nothing else".
            int listed = Math.min(count, maxImpacts);
            sb.append("> 共 ").append(report.impactsTotal).append(" 处影响，本段列出前 ").append(listed)
                    .append(" 处（按风险排序）");
            if (report.impactsTruncated > 0) {
                sb.append("；报告清单只保留风险最高的 ").append(report.impactsShown).append(" 处");
            }
            sb.append("。\n");
        }
        sb.append('\n');

        appendAi(sb, report);

        if (!report.notes.isEmpty()) {
            sb.append("### 分析边界\n\n");
            for (String note : report.notes) {
                sb.append("- ").append(note).append('\n');
            }
            sb.append('\n');
        }
        sb.append("<sub>由 code-association 生成 · 风险与置信度独立 · 静态分析结论需人工确认关键项</sub>\n");
        return sb.toString();
    }

    /**
     * The optional AI section, printed only when a model actually produced something.
     *
     * Placed after the evidence and before the analysis boundaries so the reading order stays
     * "facts, then interpretation, then what was not checked". A failed or absent generation prints
     * nothing at all: a PR comment that says "AI 生成失败" on every pipeline run of an unconfigured
     * installation is noise, and the exit code must not change either way.
     */
    private static void appendAi(StringBuilder sb, ImpactReport report) {
        if (report.ai == null || !Boolean.TRUE.equals(report.ai.get("generated"))) {
            return;
        }
        Object text = report.ai.get("text");
        sb.append("### AI 评审说明（待验证）\n\n");
        sb.append("> AI 生成内容，仅基于下列静态分析事实，不是已验证的结论。\n\n");
        sb.append(text == null ? "" : text.toString().strip()).append("\n\n");
        String model = report.ai.get("model") == null ? "" : report.ai.get("model").toString();
        Object generatedAt = report.ai.get("generatedAt");
        sb.append("<sub>模型 ").append(model);
        if (generatedAt != null) {
            sb.append(" · 生成于 ").append(generatedAt);
        }
        sb.append("</sub>\n\n");
        appendAiComments(sb, report.ai.get("comments"), report.ai.get("reviewStats"));
    }

    /**
     * The line-anchored comments, printed only when there is at least one.
     *
     * Same rule as the summary: an absent or empty list prints nothing, so an installation that
     * never enabled comments (or a run that found none) produces byte-for-byte the old output.
     * The section is explicitly "待验证" because the model wrote the wording; only the line number
     * is deterministic, and that is what the anchor note tells the reader.
     */
    private static void appendAiComments(StringBuilder sb, Object rawComments, Object rawStats) {
        if (!(rawComments instanceof List<?> comments) || comments.isEmpty()) {
            return;
        }
        sb.append("### AI 逐条评审意见（待验证）\n\n");
        // The model never writes the line number: it quotes code and the pipeline places it. Saying
        // so here keeps "line 42" from reading as an analysis fact.
        sb.append("> 行号由变更片段与文件内容匹配得出，不是模型写的；无法定位到具体行的评论已丢弃。\n\n");
        int index = 0;
        for (Object item : comments) {
            if (index >= MAX_COMMENTS) {
                break;
            }
            if (!(item instanceof Map<?, ?> comment)) {
                continue;
            }
            index++;
            String severity = upper(comment.get("severity"));
            String title = text(comment.get("title"));
            sb.append(index).append(". ").append(COMMENT_ICON.getOrDefault(severity, "⚪"))
                    .append(" **").append(severity.isEmpty() ? "INFO" : severity).append("** · ")
                    .append(title).append('\n');
            sb.append("   - 位置：").append(code(location(comment))).append(locationNote(comment)).append('\n');
            String detail = text(comment.get("detail"));
            if (!detail.isBlank()) {
                sb.append("   - 正文：").append(detail).append('\n');
            }
            String suggestion = text(comment.get("suggestion"));
            if (!suggestion.isBlank()) {
                sb.append("   - 建议：").append(suggestion).append('\n');
            }
            String existingCode = text(comment.get("existingCode")).strip();
            if (!existingCode.isEmpty()) {
                if (existingCode.contains("\n")) {
                    // A multi-line quote stays verbatim: collapsing it would change the very thing
                    // the reader is asked to verify against the file.
                    sb.append("   - 代码：\n\n     ").append(CODE).append(CODE).append(CODE)
                            .append("text\n");
                    for (String line : existingCode.split("\n", -1)) {
                        sb.append("     ").append(line).append('\n');
                    }
                    sb.append("     ").append(CODE).append(CODE).append(CODE).append('\n');
                } else {
                    sb.append("   - 代码：").append(code(existingCode)).append('\n');
                }
            }
        }
        if (index < comments.size()) {
            sb.append("\n还有 ").append(comments.size() - index).append(" 条评论未在 PR 评论中展开。\n");
        }
        sb.append('\n');
        appendReviewStats(sb, rawStats);
    }

    /** One line of counts: where the comments went, so a short list is explainable. */
    private static void appendReviewStats(StringBuilder sb, Object rawStats) {
        if (!(rawStats instanceof Map<?, ?> stats)) {
            return;
        }
        List<String> parts = new java.util.ArrayList<>();
        addCount(parts, "审阅文件", stats.get("filesReviewed"));
        addCount(parts, "跳过文件", stats.get("filesSkipped"));
        addCount(parts, "保留评论", stats.get("commentsKept"));
        addCount(parts, "未定位丢弃", stats.get("commentsDroppedUnlocated"));
        addCount(parts, "过滤", stats.get("commentsDroppedFiltered"));
        addCount(parts, "反思删除", stats.get("commentsDroppedReflection"));
        addCount(parts, "合并", stats.get("commentsMerged"));
        addCount(parts, "重定位调用", stats.get("relocationCalls"));
        if (Boolean.TRUE.equals(stats.get("reflectionRan"))) {
            parts.add("反思已执行");
        }
        Object tokens = stats.get("estimatedTokens");
        if (tokens instanceof Number number && number.longValue() > 0) {
            parts.add("约 " + number.longValue() + " tokens");
        }
        if (!parts.isEmpty()) {
            sb.append("统计：").append(String.join(" · ", parts)).append("\n\n");
        }
    }

    private static void addCount(List<String> parts, String label, Object value) {
        if (value instanceof Number number && number.longValue() > 0) {
            parts.add(label + " " + number.longValue());
        }
    }

    /** "path:start-end", or "path:start" when the comment covers one line. */
    private static String location(Map<?, ?> comment) {
        String file = text(comment.get("file"));
        int start = integer(comment.get("startLine"));
        int end = integer(comment.get("endLine"));
        if (start <= 0) {
            return file;
        }
        return file + ":" + start + (end > start ? "-" + end : "");
    }

    /** The old/new side and the anchor quality, in one parenthesis after the location. */
    private static String locationNote(Map<?, ?> comment) {
        List<String> parts = new java.util.ArrayList<>();
        parts.add("OLD".equalsIgnoreCase(text(comment.get("side"))) ? "删除行" : "新增行");
        String anchor = upper(comment.get("anchor"));
        parts.add("定位 " + ANCHOR_LABEL.getOrDefault(anchor, "未定位"));
        Object evidence = comment.get("evidence");
        if (evidence instanceof List<?> ids && !ids.isEmpty()) {
            parts.add("证据 " + String.join("、", ids.stream().map(Object::toString).toList()));
        }
        Object confidence = comment.get("confidence");
        if (confidence instanceof Number number && number.doubleValue() > 0) {
            parts.add("置信度 " + Math.round(Math.min(1.0, number.doubleValue()) * 100) + "%");
        }
        return "（" + String.join(" · ", parts) + "）";
    }

    private static int integer(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String upper(Object value) {
        return value == null ? "" : value.toString().strip().toUpperCase();
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String code(String value) {
        return CODE + (value == null ? "" : value) + CODE;
    }

    private static String decision(ImpactReport report) {
        if (report.summary.highRisk > 0) {
            return "**存在对其他程序的高风险影响，建议人工评审后再合并**";
        }
        if (report.summary.mediumRisk > 0) {
            return "存在中等风险影响，建议执行针对性回归测试";
        }
        if (report.summary.impactedSymbols > 0) {
            return "影响范围有限，常规测试即可";
        }
        return "未发现对其他程序的影响";
    }

    private static String labels(List<String> types) {
        if (types == null || types.isEmpty()) {
            return "-";
        }
        return String.join(" / ", types.stream().map(t -> TYPE_LABEL.getOrDefault(t, t)).toList());
    }

    private static String shortCommit(String commit) {
        if (commit == null || commit.isBlank()) {
            return "-";
        }
        return commit.length() > 8 ? commit.substring(0, 8) : commit;
    }

    private static String nullSafe(String value) {
        return value == null ? "-" : value;
    }
}
