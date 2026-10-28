package com.geek.association.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.geek.association.config.AppProperties;
import com.geek.association.model.ImpactResult;
import com.geek.association.util.Json;
import com.geek.association.util.LogEvents;
import com.geek.association.util.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional, evidence-constrained explanation layer.
 *
 * The model never sees the repository and never decides what is impacted. It receives the facts the
 * analyser already proved - the change, the propagation path, how each caller consumes the value -
 * and is asked to explain them in review language. Output is stored separately from the analysis
 * facts and labelled as generated, so a reviewer always knows which part is evidence and which part
 * is interpretation.
 *
 * Every public method here is a "never throws" boundary: the AI review is an optional extra on top
 * of a finished analysis, and a dead endpoint, a wrong key or a proxy in the way must degrade to a
 * readable {@code generated:false} object instead of failing the run that produced the facts.
 */
@Component
public class AiExplainer {

    private static final Logger log = LoggerFactory.getLogger(AiExplainer.class);

    /** Stored next to the text so the label travels with the content, not only with the UI. */
    private static final String DISCLAIMER =
            "AI 解释仅基于下列静态分析事实生成，属于待验证的分析结论，不是已验证的事实。";

    private static final String SYSTEM_PROMPT = "你是代码变更影响分析助手，只依据给定事实作答。";

    /** The connectivity probe asks for four letters, so a working endpoint answers in cents. */
    private static final String TEST_SYSTEM_PROMPT = "你是连通性测试助手。";
    private static final String TEST_USER_PROMPT = "只回复 pong 四个字母。";
    private static final int TEST_MAX_TOKENS = 8;

    private final AppProperties properties;

    public AiExplainer(AppProperties properties) {
        this.properties = properties;
    }

    /**
     * Historical name for "this installation can actually call a model".
     *
     * Kept because it existed before the settings page did, and its old body was already the triple
     * condition (switch + endpoint + model). It now delegates so there is one definition rather than
     * two that can drift; new code should say {@link #ready()}, which is the name the contract and
     * the UI use. The raw switch, for the "已启用但未填写模型" distinction, is
     * {@code properties.getAi().isEnabled()} and is what {@code GET /api/ai} reports as
     * {@code enabled}.
     */
    public boolean enabled() {
        return ready();
    }

    /** Whether a model call can be attempted at all; the backend's single definition of "已配置". */
    public boolean ready() {
        return properties.getAi().ready();
    }

    /** One Chinese sentence naming what is missing, or "" when {@link #ready()}. */
    public String notReadyReason() {
        return properties.getAi().notReadyReason();
    }

    /**
     * Asks the model to explain the facts in {@code report}, or returns {@code null} when the
     * feature is not configured.
     *
     * {@code null} is the "this run has no AI section" value the report and the UI already use for
     * an absent field, so an unconfigured installation keeps producing exactly the report it
     * produced before the feature existed. A configured-but-failing call returns a
     * {@code generated:false} object instead, because "we tried and it failed" has to be visible.
     *
     * @param maxImpacts how many impact rows may be sent; the prompt is the cost of the call
     */
    public Map<String, Object> explain(ImpactReport report, int maxImpacts) {
        if (!ready()) {
            return null;
        }
        AppProperties.Ai ai = properties.getAi();
        try {
            String prompt = buildPrompt(report, maxImpacts);
            String answer = call(prompt, ai.getTimeoutSeconds(), SYSTEM_PROMPT);
            if (Texts.isBlank(answer)) {
                return failure("模型返回了空内容");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("generated", true);
            out.put("model", ai.getModel());
            out.put("disclaimer", DISCLAIMER);
            out.put("text", answer.strip());
            // Stamped here rather than by the caller: the timestamp describes the generation, and
            // a补生成 hours later must not inherit the analysis time.
            out.put("generatedAt", Instant.now().toString());
            out.put("maxImpacts", maxImpacts);
            return out;
        } catch (Exception e) {
            log.warn("AI review failed: {}", describeFailure(e, ai.getTimeoutSeconds(), ai));
            return failure(describeFailure(e, ai.getTimeoutSeconds(), ai));
        }
    }

    /**
     * Sends the smallest possible request to prove the endpoint, key and model work together.
     *
     * Always returns a payload rather than throwing, and the HTTP status of the endpoint is always
     * 200: this is the result of a test the user asked for, not a server-side failure. Blank
     * arguments fall back to the saved configuration so the settings form can test an unsaved draft
     * field by field; a blank {@code apiKey} means "use the stored key", because the form shows a
     * mask and cannot send the secret back.
     */
    public Map<String, Object> testConnection(String baseUrl, String model, String apiKey) {
        AppProperties.Ai ai = properties.getAi();
        // null means "the caller did not send this field", which falls back to the saved value;
        // an empty string means the user cleared the input, and testing the saved value behind
        // their back would make "测试连接" report on an endpoint the form no longer shows.
        String url = baseUrl == null ? ai.getBaseUrl() : baseUrl.strip();
        String usedModel = model == null ? ai.getModel() : model.strip();
        // The key is the one exception: the form only ever holds a mask, so blank has to mean
        // "keep the stored key" or a test from the settings page could never authenticate.
        String key = Texts.isBlank(apiKey) ? ai.getApiKey() : apiKey.strip();
        int timeoutSeconds = ai.getTimeoutSeconds();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("endpoint", chatEndpoint(url));
        out.put("model", usedModel == null ? "" : usedModel);
        out.put("sample", "");
        out.put("latencyMs", 0);
        if (Texts.isBlank(url) || Texts.isBlank(usedModel)) {
            out.put("ok", false);
            out.put("message", Texts.isBlank(url) ? "未填写接口地址，无法测试连接" : "未填写模型，无法测试连接");
            // 没有发出请求也是一次测试结果：记一行，事后才分得清"没测"与"测了但失败"。
            log.info(LogEvents.of("ai.test",
                    "ok", false,
                    "endpoint", redact((String) out.get("endpoint"), key),
                    "model", out.get("model"),
                    "latencyMs", 0,
                    "error", out.get("message")));
            return out;
        }

        long started = System.currentTimeMillis();
        try {
            String answer = call(TEST_USER_PROMPT, timeoutSeconds, TEST_SYSTEM_PROMPT,
                    url, usedModel, key, TEST_MAX_TOKENS);
            out.put("latencyMs", (int) (System.currentTimeMillis() - started));
            out.put("sample", Texts.truncate(Texts.oneLine(answer), 80));
            out.put("ok", true);
            out.put("message", "连接正常，模型已返回响应");
            log.info(LogEvents.of("ai.test",
                    "ok", true,
                    "endpoint", redact((String) out.get("endpoint"), key),
                    "model", usedModel,
                    "latencyMs", out.get("latencyMs"),
                    "error", null));
            return out;
        } catch (Exception e) {
            out.put("latencyMs", (int) (System.currentTimeMillis() - started));
            out.put("ok", false);
            out.put("message", describeFailure(e, timeoutSeconds, ai, key, url));
            // 结构化一行：ok=false 与 error 都是"反复查询"的字段（哪个地址/模型配错了），
            // 地址与原因都先脱敏再写，绝不把 key 带进日志。
            log.warn(LogEvents.of("ai.test",
                    "ok", false,
                    "endpoint", redact((String) out.get("endpoint"), key),
                    "model", usedModel,
                    "latencyMs", out.get("latencyMs"),
                    "error", out.get("message")));
            return out;
        }
    }

    /**
     * The address a chat completion is actually posted to.
     *
     * Users paste the base URL from a provider's docs, which may or may not carry a trailing slash
     * and may or may not already include the path. Normalising in one place is what keeps the
     * settings page's validation, the "test connection" preview and the real call from disagreeing
     * about which URL is being used.
     */
    public String chatEndpoint(String baseUrl) {
        if (Texts.isBlank(baseUrl)) {
            return "";
        }
        String url = baseUrl.strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/chat/completions")) {
            return url;
        }
        return url + "/chat/completions";
    }

    private Map<String, Object> failure(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated", false);
        out.put("error", message);
        return out;
    }

    /**
     * Turns an exception into the sentence the user reads.
     *
     * The messages are written for a person looking at a settings form: which status came back,
     * whether it timed out, whether the body was not the JSON shape an OpenAI-compatible endpoint
     * returns. Anything the provider said is kept, because "HTTP 401: Invalid API key" tells the
     * user what to fix while "IOException" does not.
     */
    private String describeFailure(Exception e, int timeoutSeconds, AppProperties.Ai ai) {
        return describeFailure(e, timeoutSeconds, ai, ai.getApiKey(), ai.getBaseUrl());
    }

    private String describeFailure(Exception e, int timeoutSeconds, AppProperties.Ai ai, String apiKey,
                                   String attemptedBaseUrl) {
        String message;
        if (e instanceof HttpTimeoutException) {
            message = "连接超时（" + timeoutSeconds + " 秒），请检查接口地址与网络";
        } else if (e instanceof java.net.ConnectException) {
            // The JDK's message for a refused connection is usually null, so the address that was
            // tried is the only useful thing to report: "connection refused" without a host leaves
            // the user guessing which of the two fields is wrong.
            String detail = redact(e.getMessage(), apiKey);
            message = Texts.isBlank(detail)
                    ? "无法连接接口地址（连接被拒绝），请确认服务已启动且地址正确："
                            + chatEndpoint(attemptedBaseUrl)
                    : "无法连接接口地址：" + detail;
        } else if (e instanceof IllegalArgumentException && e.getMessage() != null
                && e.getMessage().startsWith("invalid URI")) {
            message = "接口地址格式不正确：" + ai.getBaseUrl();
        } else if (e instanceof java.io.IOException) {
            message = "请求失败：" + redact(e.getMessage(), apiKey);
        } else if (e.getMessage() != null && e.getMessage().startsWith("HTTP ")) {
            message = redact(e.getMessage(), apiKey);
        } else if (e instanceof ModelResponseException) {
            message = "无法解析响应：" + redact(e.getMessage(), apiKey);
        } else {
            String detail = Texts.isBlank(e.getMessage()) ? e.getClass().getSimpleName() : e.getMessage();
            message = "调用模型失败：" + redact(detail, apiKey);
        }
        return Texts.truncate(message, 300);
    }

    /**
     * Removes the key from any text that leaves this class.
     *
     * Provider error bodies sometimes echo the request, and a log line or an HTTP response is the
     * last place a secret should surface. Cheap enough to apply unconditionally.
     */
    private static String redact(String text, String apiKey) {
        if (text == null) {
            return "";
        }
        if (Texts.isBlank(apiKey)) {
            return text;
        }
        return text.replace(apiKey, "***");
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

    /** The configured call: the path every real explanation takes. */
    private String call(String prompt, int timeoutSeconds, String systemPrompt) throws Exception {
        AppProperties.Ai ai = properties.getAi();
        return call(prompt, timeoutSeconds, systemPrompt,
                ai.getBaseUrl(), ai.getModel(), ai.getApiKey(), null);
    }

    /**
     * One OpenAI-compatible chat completion, shared by the explanation and the connectivity test.
     *
     * Keeping the transport in one method is what makes "测试连接" trustworthy: the probe goes
     * through the same endpoint normalisation, auth header and timeout as the real call, so a
     * green test cannot be followed by a red generation for a reason the test never exercised.
     */
    private String call(String prompt, int timeoutSeconds, String systemPrompt,
                        String baseUrl, String model, String apiKey, Integer maxTokens) throws Exception {
        ObjectNode body = Json.mapper().createObjectNode();
        body.put("model", model);
        body.put("temperature", 0.2);
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }
        ArrayNode messages = body.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", systemPrompt);
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", prompt);

        int timeout = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(chatEndpoint(baseUrl)))
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        if (!Texts.isBlank(apiKey)) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(timeout, 15)))
                .build();
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": "
                    + Texts.oneLine(Texts.truncate(response.body(), 300)));
        }
        JsonNode root;
        try {
            root = Json.mapper().readTree(response.body());
        } catch (Exception e) {
            throw new ModelResponseException(Texts.oneLine(Texts.truncate(response.body(), 200)));
        }
        JsonNode choices = root == null ? null : root.path("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new ModelResponseException(Texts.oneLine(Texts.truncate(response.body(), 200)));
        }
        return choices.get(0).path("message").path("content").asText(null);
    }

    /**
     * A 2xx answer that is not an OpenAI-compatible chat response.
     *
     * Its own type so the failure text can say "无法解析响应" instead of blaming the network: a
     * captive portal or a wrong path returns 200 with HTML, and that is the most confusing case to
     * diagnose from a generic error.
     */
    private static final class ModelResponseException extends Exception {
        ModelResponseException(String body) {
            super(body == null || body.isBlank() ? "响应不是 OpenAI 兼容的 chat completion 结构" : body);
        }
    }
}
