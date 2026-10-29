package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.report.AiExplainer;
import com.geek.association.report.ImpactReport;
import com.geek.association.review.AiProgress;
import com.geek.association.review.FileContent;
import com.geek.association.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The AI layer is an optional extra on top of a finished analysis, so the behaviour worth pinning is
 * the failure behaviour: what the user is told when the key is wrong, when the endpoint answers with
 * something that is not a chat completion, and when the feature is simply not configured.
 *
 * A real {@link HttpServer} on port 0 is used instead of a mock so the actual JDK client, the actual
 * endpoint normalisation and the actual JSON encoding are exercised - those are the parts that break
 * in production, and a mocked client would assert none of them. Nothing here touches the network.
 */
class AiExplainerTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** Records what the explainer sent, so the prompt can be inspected without printing it. */
    private static final class Recorder {
        final List<String> bodies = new ArrayList<>();
        final List<String> authHeaders = new ArrayList<>();
        final List<String> paths = new ArrayList<>();
    }

    private String startEndpoint(int status, String responseBody, Recorder recorder) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            if (recorder != null) {
                recorder.paths.add(exchange.getRequestURI().getPath());
                recorder.authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
                recorder.bodies.add(read(exchange.getRequestBody()));
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static String read(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static AiExplainer explainer(String baseUrl, String model, String apiKey, boolean enabled) {
        AppProperties properties = new AppProperties();
        properties.getAi().setEnabled(enabled);
        properties.getAi().setBaseUrl(baseUrl);
        properties.getAi().setModel(model);
        properties.getAi().setApiKey(apiKey);
        properties.getAi().setTimeoutSeconds(5);
        // These tests pin the summary layer; the comment layer has its own tests, and a fake
        // endpoint that answers every request with the same JSON would only add noise here.
        properties.getAi().setComments(false);
        return new AiExplainer(properties);
    }

    /** The smallest report that still has a change, an impact and a blind spot to talk about. */
    private static ImpactReport sampleReport() {
        ImpactReport report = new ImpactReport();
        report.analysisId = 7;
        report.sourceName = "order-service";

        ImpactReport.ChangeView change = new ImpactReport.ChangeView();
        change.id = 1;
        change.changeType = "API_CONTRACT";
        change.module = "order-service";
        change.qualifiedName = "com.example.order.OrderController.get";
        change.signature = "(int)";
        change.summary = "返回类型由 int 改为 long";
        report.changes.add(change);

        ImpactReport.ImpactView impact = new ImpactReport.ImpactView();
        impact.id = 1;
        impact.riskLevel = "HIGH";
        impact.riskScore = 72;
        impact.targetModule = "billing-service";
        impact.targetSymbol = "com.example.billing.OrderClient.get";
        impact.targetSignature = "(int)";
        impact.impactType = "API_CONTRACT";
        impact.confidence = "HIGH";
        impact.depth = 2;
        impact.reason = "调用方按 int 接收返回值，long 会溢出";
        report.impacts.add(impact);

        ImpactReport.BlindSpot spot = new ImpactReport.BlindSpot();
        spot.file = "billing-service/src/main/java/com/example/billing/OrderClient.java";
        spot.line = 42;
        spot.expression = "reflection.invoke(...)";
        spot.reason = "动态调用无法静态解析";
        report.blindSpots.add(spot);
        return report;
    }

    /** Same report plus a reviewable hunk, so the comment phase actually reaches the model. */
    private static ImpactReport reportWithDiff() {
        ImpactReport report = sampleReport();
        report.changes.get(0).file = "src/main/java/com/example/order/OrderController.java";
        report.diffs.put(report.changes.get(0).file, """
                --- a/src/main/java/com/example/order/OrderController.java
                +++ b/src/main/java/com/example/order/OrderController.java
                @@ -1,3 +1,4 @@
                 class OrderController {
                -    public int get(int id) {
                +    public long get(int id) {
                     return 1;
                 }
                """);
        return report;
    }

    /** Properties for the new cases; timeoutSeconds is generous because the budget is the subject. */
    private static AppProperties properties(String baseUrl, boolean comments) {
        AppProperties properties = new AppProperties();
        properties.getAi().setEnabled(true);
        properties.getAi().setBaseUrl(baseUrl);
        properties.getAi().setModel("m");
        properties.getAi().setTimeoutSeconds(30);
        properties.getAi().setComments(comments);
        return properties;
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /**
     * Sends response headers and a few body bytes, then keeps the exchange open without ever
     * finishing the JSON. This is the shape that made analyses stick at 96%: the JDK request
     * timeout covers the response headers, not the body, so the client waits forever.
     *
     * <p>The repeated writes double as a cancellation probe: once the client gives up the socket is
     * closed and the next write throws, so the handler does not outlive the test by its sleep.
     */
    private static void stallRequestBody(HttpExchange exchange, String prefix) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(prefix.getBytes(StandardCharsets.UTF_8));
            body.flush();
            for (int i = 0; i < 80; i++) {
                Thread.sleep(100);
                body.write(' ');
                body.flush();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException expected) {
            // The client cancelled the exchange; that is the behaviour under test.
        }
    }

    @Test
    void successfulAnswerIsParsedIntoTheReportSection() throws Exception {
        Recorder recorder = new Recorder();
        // A text block still processes escapes, so the newline inside the model's answer has to be
        // written as \\n here to reach the client as a JSON escape rather than as a real line break.
        String baseUrl = startEndpoint(200, """
                {"choices":[{"message":{"role":"assistant","content":"  1) 返回类型变化会影响调用方。\\n2) 需确认溢出。  "}}]}
                """, recorder);
        AiExplainer explainer = explainer(baseUrl, "deepseek-chat", "sk-test-key-123456", true);

        Map<String, Object> ai = explainer.explain(sampleReport(), 12);

        assertNotNull(ai);
        assertEquals(true, ai.get("generated"));
        assertEquals("deepseek-chat", ai.get("model"));
        assertEquals("1) 返回类型变化会影响调用方。\n2) 需确认溢出。", ai.get("text"),
                "the model's leading/trailing whitespace is trimmed before it is stored");
        assertNotNull(ai.get("generatedAt"));
        assertTrue(ai.get("generatedAt").toString().matches("\\d{4}-\\d{2}-\\d{2}T.*Z"),
                "generatedAt must be ISO-8601 in UTC: " + ai.get("generatedAt"));
        assertEquals(12, ai.get("maxImpacts"));
        assertTrue(ai.get("disclaimer").toString().contains("待验证"));

        // The endpoint is normalised and the key is sent as a bearer token, once.
        assertEquals(List.of("/v1/chat/completions"), recorder.paths);
        assertEquals(List.of("Bearer sk-test-key-123456"), recorder.authHeaders);
    }

    @Test
    void promptContainsTheChangesAndImpactsButNoSourceBody() throws Exception {
        Recorder recorder = new Recorder();
        String baseUrl = startEndpoint(200,
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}", recorder);
        AiExplainer explainer = explainer(baseUrl, "m", "", true);

        ImpactReport report = sampleReport();
        // A diff hunk is source code, and the whole point of the privacy claim is that it stays out
        // of the prompt: only the derived facts are sent.
        report.diffs.put("OrderController.java", "SECRET_SOURCE_BODY public int get(int id) { return 1; }");

        explainer.explain(report, 12);

        String prompt = Json.mapper().readTree(recorder.bodies.get(0))
                .path("messages").get(1).path("content").asText();
        assertTrue(prompt.contains("com.example.order.OrderController.get"), "the change must be sent");
        assertTrue(prompt.contains("返回类型由 int 改为 long"), "the change summary must be sent");
        assertTrue(prompt.contains("com.example.billing.OrderClient.get"), "the impact must be sent");
        assertTrue(prompt.contains("调用方按 int 接收返回值"), "the reason must be sent");
        assertTrue(prompt.contains("动态调用无法静态解析"), "the blind spot must be sent");
        assertFalse(prompt.contains("SECRET_SOURCE_BODY"), "source code must never be sent: " + prompt);
        assertFalse(prompt.contains("public int get(int id)"), "source code must never be sent");
    }

    @Test
    void maxImpactsBoundsHowManyRowsAreSent() throws Exception {
        Recorder recorder = new Recorder();
        String baseUrl = startEndpoint(200,
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}", recorder);
        AiExplainer explainer = explainer(baseUrl, "m", "", true);

        ImpactReport report = sampleReport();
        for (int i = 2; i <= 6; i++) {
            ImpactReport.ImpactView extra = new ImpactReport.ImpactView();
            extra.id = i;
            extra.riskLevel = "LOW";
            extra.riskScore = 10;
            extra.targetModule = "peer-" + i;
            extra.targetSymbol = "Symbol" + i;
            extra.impactType = "BEHAVIOR";
            extra.confidence = "LOW";
            extra.reason = "reason " + i;
            report.impacts.add(extra);
        }

        explainer.explain(report, 2);

        String prompt = Json.mapper().readTree(recorder.bodies.get(0))
                .path("messages").get(1).path("content").asText();
        assertTrue(prompt.contains("Symbol2"), "the first impacts within the limit are sent");
        assertFalse(prompt.contains("Symbol4"), "impacts past the limit must not be sent: " + prompt);
    }

    @Test
    void httpErrorBecomesAGeneratedFalseSectionWithTheStatus() throws Exception {
        String baseUrl = startEndpoint(401, "{\"error\":{\"message\":\"Invalid API key\"}}", null);
        AiExplainer explainer = explainer(baseUrl, "deepseek-chat", "sk-wrong-key-1234567", true);

        Map<String, Object> ai = explainer.explain(sampleReport(), 12);

        assertNotNull(ai, "a failed call still returns a section, so the UI can show the reason");
        assertEquals(false, ai.get("generated"));
        assertTrue(ai.get("error").toString().contains("401"), "the status must survive: " + ai.get("error"));
        assertFalse(ai.get("error").toString().contains("sk-wrong-key-1234567"),
                "the key must never appear in an error message: " + ai.get("error"));
    }

    @Test
    void unparsableResponseIsReportedAsSuch() throws Exception {
        // A 200 with HTML is what a captive portal or a wrong path returns, and it is the most
        // confusing failure to diagnose, so it gets its own wording.
        String baseUrl = startEndpoint(200, "<html>not a chat completion</html>", null);
        AiExplainer explainer = explainer(baseUrl, "m", "", true);

        Map<String, Object> ai = explainer.explain(sampleReport(), 12);

        assertEquals(false, ai.get("generated"));
        assertTrue(ai.get("error").toString().contains("无法解析响应"), "got: " + ai.get("error"));
    }

    @Test
    void emptyContentIsAFailureNotAnEmptySection() throws Exception {
        String baseUrl = startEndpoint(200, "{\"choices\":[{\"message\":{\"content\":\"   \"}}]}", null);
        AiExplainer explainer = explainer(baseUrl, "m", "", true);

        Map<String, Object> ai = explainer.explain(sampleReport(), 12);

        assertEquals(false, ai.get("generated"));
        assertTrue(ai.get("error").toString().contains("空内容"), "got: " + ai.get("error"));
    }

    @Test
    void explainReturnsNullWhenNotReadySoTheReportIsUnchanged() {
        // null is the "no AI section" value the report already uses for an absent field, which is
        // what makes an unconfigured installation produce the same report as before the feature.
        assertNull(explainer("", "", "", true).explain(sampleReport(), 12), "no endpoint");
        assertNull(explainer("https://x.test/v1", "", "", true).explain(sampleReport(), 12), "no model");
        assertNull(explainer("https://x.test/v1", "m", "", false).explain(sampleReport(), 12), "switch off");
    }

    @Test
    void notReadyReasonNamesWhatIsMissing() {
        assertEquals("总开关未打开", explainer("https://x.test/v1", "m", "", false).notReadyReason());
        assertEquals("已启用但未填写接口地址", explainer("", "m", "", true).notReadyReason());
        assertEquals("已启用但未填写模型", explainer("https://x.test/v1", "", "", true).notReadyReason());
        AiExplainer ready = explainer("https://x.test/v1", "m", "", true);
        assertEquals("", ready.notReadyReason());
        assertTrue(ready.ready());
        assertTrue(ready.enabled(), "enabled() is the pre-existing name for the same question");
    }

    @Test
    void testConnectionSucceedsAndAsksForOnlyAFewTokens() throws Exception {
        Recorder recorder = new Recorder();
        String baseUrl = startEndpoint(200,
                "{\"choices\":[{\"message\":{\"content\":\"pong\"}}]}", recorder);
        AiExplainer explainer = explainer(baseUrl, "deepseek-chat", "sk-live-123456789", true);

        Map<String, Object> result = explainer.testConnection(baseUrl, "deepseek-chat", "sk-live-123456789");

        assertEquals(true, result.get("ok"), "got: " + result.get("message"));
        assertEquals("deepseek-chat", result.get("model"));
        assertEquals(baseUrl + "/chat/completions", result.get("endpoint"));
        assertEquals("pong", result.get("sample"));
        assertTrue(result.get("message").toString().contains("连接正常"));
        assertTrue((Integer) result.get("latencyMs") >= 0);

        var sent = Json.mapper().readTree(recorder.bodies.get(0));
        assertEquals(8, sent.path("max_tokens").asInt(), "the probe must be as cheap as possible");
        assertEquals("你是连通性测试助手。", sent.path("messages").get(0).path("content").asText());
        assertEquals("只回复 pong 四个字母。", sent.path("messages").get(1).path("content").asText());
    }

    @Test
    void testConnectionReportsFailuresWithoutThrowing() throws Exception {
        String unauthorized = startEndpoint(401, "{\"error\":\"Invalid API key\"}", null);
        Map<String, Object> failed = explainer("", "", "", false)
                .testConnection(unauthorized, "m", "sk-bad-key-12345678");
        assertEquals(false, failed.get("ok"));
        assertTrue(failed.get("message").toString().contains("401"), "got: " + failed.get("message"));
        assertFalse(failed.get("message").toString().contains("sk-bad-key-12345678"));
        assertEquals(unauthorized + "/chat/completions", failed.get("endpoint"),
                "the endpoint that was actually called is reported even on failure");
        assertEquals("", failed.get("sample"));
    }

    @Test
    void testConnectionWithNoEndpointSendsNoRequest() {
        AiExplainer explainer = explainer("", "", "", false);
        Map<String, Object> result = explainer.testConnection("", "", "");
        assertEquals(false, result.get("ok"));
        assertTrue(result.get("message").toString().contains("接口地址"));
        assertEquals("", result.get("endpoint"));
        assertEquals("", result.get("sample"));
    }

    @Test
    void testConnectionWithAClearedFieldDoesNotSilentlyTestTheSavedOne() throws Exception {
        Recorder recorder = new Recorder();
        String saved = startEndpoint(200,
                "{\"choices\":[{\"message\":{\"content\":\"pong\"}}]}", recorder);
        AiExplainer explainer = explainer(saved, "saved-model", "sk-saved-111222333", true);

        // The user cleared the address input: testing the saved address behind their back would
        // report success for an endpoint the form no longer shows.
        Map<String, Object> result = explainer.testConnection("", "saved-model", "");

        assertEquals(false, result.get("ok"));
        assertTrue(result.get("message").toString().contains("接口地址"), "got: " + result.get("message"));
        assertEquals("", result.get("endpoint"));
        assertTrue(recorder.bodies.isEmpty(), "no request may be sent when the form field is empty");
    }

    @Test
    void refusedConnectionStillSaysSomethingUseful() {
        // ConnectException's message is null on some JDKs and "Connection refused (connect failed)"
        // on others, so what is pinned here is the invariant: whatever the platform gives, the user
        // never sees a sentence that ends in a dangling colon.
        AiExplainer explainer = explainer("http://127.0.0.1:1/v1", "m", "", true);
        Map<String, Object> result = explainer.testConnection("http://127.0.0.1:1/v1", "m", "");

        assertEquals(false, result.get("ok"));
        String message = result.get("message").toString();
        assertFalse(message.endsWith("："), "the reason must never be empty: " + message);
        assertTrue(message.length() > "无法连接接口地址：".length() + 3,
                "the message must carry a reason, not just the prefix: " + message);
        assertEquals("http://127.0.0.1:1/v1/chat/completions", result.get("endpoint"),
                "the address that was tried is always reported");
    }

    @Test
    void testConnectionFallsBackToTheSavedKeyWhenTheFormSendsNone() throws Exception {
        Recorder recorder = new Recorder();
        String baseUrl = startEndpoint(200,
                "{\"choices\":[{\"message\":{\"content\":\"pong\"}}]}", recorder);
        // The form only ever holds a mask, so a blank apiKey has to mean "use the stored one".
        AiExplainer explainer = explainer(baseUrl, "saved-model", "sk-saved-987654321", true);

        Map<String, Object> result = explainer.testConnection(null, null, null);

        assertEquals(true, result.get("ok"), "got: " + result.get("message"));
        assertEquals("saved-model", result.get("model"));
        assertEquals(List.of("Bearer sk-saved-987654321"), recorder.authHeaders);
    }

    @Test
    void timeoutIsReportedInSeconds() throws Exception {
        // A handler that never answers: the client has to give up on its own timeout, and the
        // message has to name the number the user can change in the settings page.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

        AppProperties properties = new AppProperties();
        properties.getAi().setEnabled(true);
        properties.getAi().setBaseUrl(baseUrl);
        properties.getAi().setModel("m");
        properties.getAi().setTimeoutSeconds(1);
        Map<String, Object> result = new AiExplainer(properties).testConnection(baseUrl, "m", "");

        assertEquals(false, result.get("ok"));
        assertTrue(result.get("message").toString().contains("超时"), "got: " + result.get("message"));
        assertTrue(result.get("message").toString().contains("1"), "the limit is named: " + result.get("message"));
    }

    @Test
    void endpointNormalisationHandlesTheFormsUsersPaste() {
        AiExplainer explainer = explainer("", "", "", false);
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                explainer.chatEndpoint("https://api.deepseek.com/v1"));
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                explainer.chatEndpoint("https://api.deepseek.com/v1/"));
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                explainer.chatEndpoint("https://api.deepseek.com/v1/chat/completions"),
                "an endpoint that already has the path must not gain a second one");
        assertEquals("http://localhost:11434/v1/chat/completions",
                explainer.chatEndpoint("  http://localhost:11434/v1  "));
        assertEquals("", explainer.chatEndpoint(""));
    }

    @Test
    void apiKeyNeverAppearsInTheSerialisedResult() throws Exception {
        String baseUrl = startEndpoint(500, "{\"error\":\"boom\"}", null);
        AiExplainer explainer = explainer(baseUrl, "m", "sk-super-secret-000111", true);

        String json = Json.write(explainer.explain(sampleReport(), 12));
        assertFalse(json.contains("sk-super-secret-000111"), "the key leaked into the report: " + json);

        String testJson = Json.write(explainer.testConnection(baseUrl, "m", "sk-super-secret-000111"));
        assertFalse(testJson.contains("sk-super-secret-000111"), "the key leaked into the test result: " + testJson);
    }

    @Test
    void stalledResponseBodyIsCutOffByTheSectionBudget() throws Exception {
        // Regression for "分析卡在 96%": the server sends response headers and then stops writing the
        // body. HttpRequest.timeout alone does not cover that on JDK 21, so before the section
        // watchdog this call blocked until the process was killed.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions",
                exchange -> stallRequestBody(exchange, "{\"choices\":["));
        server.start();

        AppProperties properties = properties(endpoint(), false);
        properties.getAi().setTotalBudgetSeconds(2);

        long started = System.nanoTime();
        Map<String, Object> ai = new AiExplainer(properties)
                .explain(sampleReport(), 12, FileContent.NONE, AiProgress.none());
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertNotNull(ai);
        assertEquals(false, ai.get("generated"));
        assertTrue(ai.get("error").toString().contains("预算"),
                "the section budget must be the stated reason: " + ai.get("error"));
        assertTrue(elapsedMs < 10_000,
                "a stalled body must be cut in seconds, took " + elapsedMs + "ms");
    }

    @Test
    void budgetCutsCommentPhaseButKeepsSummary() throws Exception {
        // The summary is the existing promise; the comment layer is the expensive extra. When the
        // section budget runs out inside the comment calls the summary must survive, and the
        // shortfall must be visible as reviewError rather than as a failed generation.
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            if (calls.incrementAndGet() == 1) {
                byte[] bytes = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"1) 摘要\"}}]}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(bytes);
                }
                return;
            }
            stallRequestBody(exchange, "{\"choices\":[");
        });
        server.start();

        AppProperties properties = properties(endpoint(), true);
        properties.getAi().setTotalBudgetSeconds(3);

        long started = System.nanoTime();
        Map<String, Object> ai = new AiExplainer(properties)
                .explain(reportWithDiff(), 12, FileContent.NONE, AiProgress.none());
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertNotNull(ai);
        assertEquals(true, ai.get("generated"), "the summary must survive: " + ai);
        assertEquals("1) 摘要", ai.get("text"));
        assertNotNull(ai.get("reviewError"), "the cut comment phase must say why: " + ai);
        assertTrue(ai.get("reviewError").toString().contains("预算"),
                "the budget must be named: " + ai.get("reviewError"));
        assertTrue(elapsedMs < 6_000,
                "the section must not spend a full per-call timeout, took " + elapsedMs + "ms");
    }

    @Test
    void progressReportsSummaryAndCommentPhases() throws Exception {
        // The AI section used to report nothing between "开始" and "完成". This pins the contract the
        // analysis service maps to 96%..99%: at least two updates, 0..1 fractions, monotonic, and
        // one shared prefix so the panel line reads as a single stage.
        String baseUrl = startEndpoint(200,
                "{\"choices\":[{\"message\":{\"content\":\"要点\"}}]}", null);
        AppProperties properties = properties(baseUrl, true);
        properties.getAi().setTimeoutSeconds(5);

        List<String> details = new ArrayList<>();
        List<Double> fractions = new ArrayList<>();
        AiProgress recorder = (detail, fraction) -> {
            details.add(detail);
            fractions.add(fraction);
        };

        Map<String, Object> ai = new AiExplainer(properties)
                .explain(sampleReport(), 12, FileContent.NONE, recorder);

        assertNotNull(ai);
        assertEquals(true, ai.get("generated"));
        assertTrue(details.size() >= 2, "summary and comment phases must both report: " + details);
        assertTrue(details.contains("AI 评审：生成评审要点"), "got: " + details);
        assertTrue(details.contains("AI 评审：逐条评审意见"), "got: " + details);
        assertEquals("AI 评审：整理意见", details.get(details.size() - 1),
                "the section must end with the wrap-up line: " + details);
        double previous = -1;
        for (int i = 0; i < details.size(); i++) {
            String detail = details.get(i);
            double fraction = fractions.get(i);
            assertTrue(detail.startsWith("AI 评审："), "the panel shows one prefix: " + detail);
            assertTrue(fraction >= 0 && fraction <= 1, "fraction out of range: " + fraction);
            assertTrue(fraction >= previous, "fractions must not go backwards: " + fractions);
            previous = fraction;
        }

        // The three-argument form still works and is what the analysis service called before the
        // progress-aware overload existed; the two-argument form is covered throughout this class.
        Map<String, Object> threeArgs = new AiExplainer(properties)
                .explain(sampleReport(), 12, FileContent.NONE);
        assertEquals(true, threeArgs.get("generated"));
    }
}
