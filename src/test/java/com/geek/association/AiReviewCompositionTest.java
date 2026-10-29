package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.report.AiExplainer;
import com.geek.association.report.ImpactReport;
import com.geek.association.util.Json;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the composition of the two AI layers: the summary call keeps working exactly as before, and
 * the optional comment layer adds placed comments, its stats, and - when it fails - a note rather
 * than a failed generation.
 *
 * A real HTTP server is used because the wire contract is the part that would break silently: the
 * prompt routing below is by content, so a changed prompt wording shows up as a wrong response and
 * a failing assertion instead of a green test with the wrong call order.
 */
class AiReviewCompositionTest {

    private HttpServer server;
    private final List<String> bodies = new ArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private String start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = read(exchange.getRequestBody());
            synchronized (bodies) {
                bodies.add(body);
            }
            String prompt = Json.mapper().readTree(body)
                    .path("messages").get(1).path("content").asText();
            String answer;
            int status = 200;
            if (prompt.contains("复核员")) {
                answer = "{\"remove\":[]}";
            } else if (prompt.contains("existing_code")) {
                answer = Json.write(Map.of("comments", List.of(Map.of(
                        "id", "c1",
                        "severity", "MAJOR",
                        "category", "CORRECTNESS",
                        "title", "返回类型变宽后调用方可能溢出",
                        "detail", "get 由 int 改为 long，调用方按 int 接收会截断。",
                        "suggestion", "同步检查调用方的接收类型。",
                        "file", "src/main/java/com/example/order/OrderController.java",
                        "existing_code", "public long get(int id) {",
                        "evidence", List.of("C1")))));
            } else {
                answer = "1) 返回类型变化\n2) 需人工确认调用方";
            }
            write(exchange, status, Json.write(Map.of("choices", List.of(
                    Map.of("message", Map.of("role", "assistant", "content", answer))))));
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static String read(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void write(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static ImpactReport reportWithDiff() {
        ImpactReport report = new ImpactReport();
        report.analysisId = 3;
        report.sourceName = "order-service";
        ImpactReport.ChangeView change = new ImpactReport.ChangeView();
        change.id = 1;
        change.changeType = "API_CONTRACT";
        change.module = "order-service";
        change.qualifiedName = "com.example.order.OrderController.get";
        change.file = "src/main/java/com/example/order/OrderController.java";
        change.summary = "返回类型由 int 改为 long";
        report.changes.add(change);
        report.diffs.put(change.file, """
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

    private static AiExplainer explainer(String url, boolean comments) {
        AppProperties properties = new AppProperties();
        properties.getAi().setEnabled(true);
        properties.getAi().setBaseUrl(url);
        properties.getAi().setModel("deepseek-chat");
        properties.getAi().setTimeoutSeconds(5);
        properties.getAi().setComments(comments);
        return new AiExplainer(properties);
    }

    @Test
    void summaryAndPlacedCommentAreReturnedTogether() throws Exception {
        String url = start();
        Map<String, Object> ai = explainer(url, true).explain(reportWithDiff(), 12);

        assertNotNull(ai);
        assertEquals(true, ai.get("generated"));
        assertTrue(ai.get("text").toString().contains("返回类型变化"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> comments = (List<Map<String, Object>>) ai.get("comments");
        assertEquals(1, comments.size(), "the quoted line must be placed: " + comments);
        Map<String, Object> comment = comments.get(0);
        assertEquals("src/main/java/com/example/order/OrderController.java", comment.get("file"));
        assertEquals(2, comment.get("startLine"), "the added line is new line 2: " + comment);
        assertEquals("HUNK", comment.get("anchor"));
        assertEquals("NEW", comment.get("side"));

        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) ai.get("reviewStats");
        assertEquals(1, stats.get("filesReviewed"));
        assertEquals(1, stats.get("commentsKept"));
        assertEquals(true, stats.get("reflectionRan"));
        assertEquals(0, stats.get("relocationCalls"));
        assertEquals(3, bodies.size(), "summary + review + reflection: " + bodies.size());
    }

    @Test
    void commentsOffSendsOnlyTheSummaryAndKeepsTheOldPayload() throws Exception {
        String url = start();
        Map<String, Object> ai = explainer(url, false).explain(reportWithDiff(), 12);

        assertEquals(true, ai.get("generated"));
        assertFalse(ai.containsKey("comments"));
        assertFalse(ai.containsKey("reviewStats"));
        assertEquals(1, bodies.size());
    }

    @Test
    void failedCommentStageLeavesTheSummaryUsable() throws Exception {
        HttpServer failing = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        failing.createContext("/v1/chat/completions", exchange -> {
            String body = read(exchange.getRequestBody());
            // The first call is the summary; every later call is the comment layer.
            String prompt = Json.mapper().readTree(body).path("messages").get(1).path("content").asText();
            if (prompt.contains("existing_code")) {
                write(exchange, 500, "{\"error\":\"boom\"}");
            } else {
                write(exchange, 200, Json.write(Map.of("choices", List.of(
                        Map.of("message", Map.of("content", "summary only"))))));
            }
        });
        failing.start();
        try {
            String url = "http://127.0.0.1:" + failing.getAddress().getPort() + "/v1";
            Map<String, Object> ai = explainer(url, true).explain(reportWithDiff(), 12);

            assertEquals(true, ai.get("generated"));
            assertEquals("summary only", ai.get("text"));
            assertEquals(List.of(), ai.get("comments"));
            @SuppressWarnings("unchecked")
            Map<String, Object> stats = (Map<String, Object>) ai.get("reviewStats");
            assertEquals(0, stats.get("commentsKept"));
            assertNotNull(ai.get("reviewError"));
        } finally {
            failing.stop(0);
        }
    }
}
