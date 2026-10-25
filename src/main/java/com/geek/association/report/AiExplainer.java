package com.geek.association.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.geek.association.config.AppProperties;
import com.geek.association.model.ImpactResult;
import com.geek.association.util.Json;
import com.geek.association.util.Texts;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional, evidence-constrained explanation layer.
 *
 * The model never sees the repository and never decides what is impacted. It receives the facts the
 * analyser already proved - the change, the propagation path, how each caller consumes the value -
 * and is asked to explain them in review language. Output is stored separately from the analysis
 * facts and labelled as generated, so a reviewer always knows which part is evidence and which part
 * is interpretation.
 */
@Component
public class AiExplainer {

    private final AppProperties properties;

    public AiExplainer(AppProperties properties) {
        this.properties = properties;
    }

    public boolean enabled() {
        AppProperties.Ai ai = properties.getAi();
        return ai.isEnabled() && !Texts.isBlank(ai.getBaseUrl()) && !Texts.isBlank(ai.getModel());
    }

    public Map<String, Object> explain(ImpactReport report, int maxImpacts) {
        if (!enabled()) {
            return null;
        }
        try {
            String prompt = buildPrompt(report, maxImpacts);
            String answer = call(prompt);
            if (answer == null) {
                return null;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("generated", true);
            out.put("model", properties.getAi().getModel());
            out.put("disclaimer", "AI 解释仅基于下列静态分析事实生成，属于待验证的分析结论，不是已验证的事实。");
            out.put("text", answer);
            return out;
        } catch (Exception e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("generated", false);
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return out;
        }
    }

    private String buildPrompt(ImpactReport report, int maxImpacts) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是资深 Java 架构师。下面是一次代码变更对其他程序影响的静态分析结果（事实）。\n");
        sb.append("请只用中文，基于这些事实写一份 6 条以内的评审要点：\n");
        sb.append("1) 这次改动改变了什么业务语义；\n");
        sb.append("2) 哪些其他程序会受影响，通过什么契约、为什么；\n");
        sb.append("3) 最需要人工确认的 2-3 个点；\n");
        sb.append("4) 建议补充的测试场景。\n");
        sb.append("禁止编造事实里没有的类、方法或调用关系；不确定就写“需人工确认”。\n\n");
        sb.append("== 变更 ==\n");
        int count = 0;
        for (ImpactReport.ChangeView change : report.changes) {
            if (count++ >= 15) {
                break;
            }
            sb.append("- [").append(change.changeType).append("] ")
                    .append(change.module).append(" ").append(change.qualifiedName)
                    .append(change.signature == null ? "" : change.signature)
                    .append(" :: ").append(change.summary).append('\n');
        }
        sb.append("\n== 对其他程序的影响（按风险排序）==\n");
        count = 0;
        for (ImpactReport.ImpactView impact : report.impacts) {
            if (count++ >= maxImpacts) {
                break;
            }
            sb.append("- ").append(impact.riskLevel).append(' ').append(Math.round(impact.riskScore))
                    .append(" | ").append(impact.targetModule).append(' ')
                    .append(impact.targetSymbol).append(impact.targetSignature == null ? "" : impact.targetSignature)
                    .append(" | 类型 ").append(impact.impactType)
                    .append(" | 置信度 ").append(impact.confidence)
                    .append(" | 深度 ").append(impact.depth).append('\n');
            sb.append("  原因: ").append(impact.reason).append('\n');
            if (impact.path != null && !impact.path.isEmpty()) {
                sb.append("  路径: ");
                for (ImpactResult.PathStep step : impact.path) {
                    sb.append(step.symbol);
                    if (step.usage != null && !"NONE".equals(step.usage)) {
                        sb.append('[').append(step.usage).append(']');
                    }
                    sb.append(" -> ");
                }
                sb.append("\n");
            }
        }
        if (!report.blindSpots.isEmpty()) {
            sb.append("\n== 分析盲区（无法解析的调用点）==\n");
            int n = 0;
            for (ImpactReport.BlindSpot b : report.blindSpots) {
                if (n++ >= 8) {
                    break;
                }
                sb.append("- ").append(b.file).append(':').append(b.line).append(' ')
                        .append(b.expression).append(" (").append(b.reason).append(")\n");
            }
        }
        return sb.toString();
    }

    private String call(String prompt) throws Exception {
        AppProperties.Ai ai = properties.getAi();
        ObjectNode body = Json.mapper().createObjectNode();
        body.put("model", ai.getModel());
        body.put("temperature", 0.2);
        ArrayNode messages = body.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", "你是代码变更影响分析助手，只依据给定事实作答。");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", prompt);

        String endpoint = ai.getBaseUrl().replaceAll("/$", "");
        if (!endpoint.endsWith("/chat/completions")) {
            endpoint = endpoint + "/chat/completions";
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        if (!Texts.isBlank(ai.getApiKey())) {
            builder.header("Authorization", "Bearer " + ai.getApiKey());
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + Texts.truncate(response.body(), 300));
        }
        JsonNode root = Json.mapper().readTree(response.body());
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }
        return choices.get(0).path("message").path("content").asText(null);
    }
}
