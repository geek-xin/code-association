package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.model.Analysis;
import com.geek.association.model.RepoSource;
import com.geek.association.report.AiExplainer;
import com.geek.association.report.ImpactReport;
import com.geek.association.service.AnalysisService;
import com.geek.association.source.RepositoryPreparer;
import com.geek.association.store.Store;
import com.geek.association.util.Json;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regenerating the AI section of a finished report touches three places that must agree: the stored
 * report_json, the parsed report the page is served from, and the status of the run itself.
 *
 * The cache is the one that fails silently - it is an LRU in front of SQLite, so a write that skips
 * it looks like a broken button ("I clicked 生成, nothing changed") until the process restarts. These
 * tests pin the cache update, the narrow store write, and that a补生成 does not re-stamp the run.
 */
class AiReviewPersistenceTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** A local endpoint that answers every request with one fixed chat completion. */
    private String startEndpoint(int status, String content) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String body = Json.write(Map.of("choices", java.util.List.of(
                Map.of("message", Map.of("role", "assistant", "content", content)))));
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static AnalysisService service(Store store, AppProperties properties) {
        return new AnalysisService(store, properties,
                new RepositoryPreparer(properties), new AiExplainer(properties));
    }

    private static Analysis finishedRun(Store store, ImpactReport report) {
        RepoSource source = new RepoSource();
        source.name = "order-service";
        source.type = "LOCAL_DIR";
        source.location = "/tmp/order-service";
        store.saveSource(source);

        Analysis analysis = new Analysis();
        analysis.sourceId = source.id;
        analysis.sourceName = "order-service";
        analysis.sourceType = "LOCAL_DIR";
        analysis.mode = "GIT_DIFF";
        store.createAnalysis(analysis);
        store.finishAnalysis(analysis.id, Analysis.DONE, null, 1000,
                Map.of("impactedSymbols", report.impacts.size()), report);
        return analysis;
    }

    private static ImpactReport reportWithOneImpact() {
        ImpactReport report = new ImpactReport();
        report.sourceName = "order-service";
        ImpactReport.ImpactView impact = new ImpactReport.ImpactView();
        impact.id = 1;
        impact.riskLevel = "HIGH";
        impact.riskScore = 72;
        impact.targetModule = "billing-service";
        impact.targetSymbol = "com.example.billing.OrderClient.get";
        impact.impactType = "API_CONTRACT";
        impact.confidence = "HIGH";
        impact.reason = "调用方按 int 接收返回值";
        report.impacts.add(impact);
        return report;
    }

    @Test
    void generatedReviewIsVisibleImmediatelyThroughTheCacheAndInTheStore(@TempDir Path tmp) throws Exception {
        String baseUrl = startEndpoint(200, "AI 评审正文");
        AppProperties properties = new AppProperties();
        properties.setDataDir(tmp.toString());
        properties.getAi().setEnabled(true);
        properties.getAi().setBaseUrl(baseUrl);
        properties.getAi().setModel("deepseek-chat");
        properties.getAi().setMaxImpacts(7);

        Store store = new Store(properties);
        store.init();
        Analysis analysis = finishedRun(store, reportWithOneImpact());
        AnalysisService service = service(store, properties);

        // Prime the cache with the report as it was stored, the way opening the page does.
        ImpactReport before = service.report(analysis.id);
        assertNotNull(before);
        assertNull(before.ai, "a run without the AI section starts with no section");

        Map<String, Object> ai = service.saveAiReview(analysis.id);

        assertNotNull(ai);
        assertEquals(true, ai.get("generated"));
        assertEquals("AI 评审正文", ai.get("text"));
        assertEquals("deepseek-chat", ai.get("model"));
        assertEquals(7, ai.get("maxImpacts"));

        // 1. The cache the page reads from is updated, not just the database.
        ImpactReport cached = service.report(analysis.id);
        assertNotNull(cached.ai, "report(id) must serve the new section without a restart");
        assertEquals("AI 评审正文", cached.ai.get("text"));

        // 2. The blob on disk has it too, so a restart does not lose it.
        String stored = store.analysisWithReport(analysis.id).reportJson;
        assertNotNull(stored);
        Map<String, Object> storedMap = Json.readMap(stored);
        @SuppressWarnings("unchecked")
        Map<String, Object> storedAi = (Map<String, Object>) storedMap.get("ai");
        assertNotNull(storedAi, "report_json must carry the ai section: " + stored.substring(0, 200));
        assertEquals("AI 评审正文", storedAi.get("text"));
        // The rest of the report is untouched by the narrow write.
        assertNotNull(storedMap.get("impacts"));
    }

    @Test
    void regeneratingDoesNotRestampTheFinishedRun(@TempDir Path tmp) throws Exception {
        String baseUrl = startEndpoint(200, "second attempt");
        AppProperties properties = new AppProperties();
        properties.setDataDir(tmp.toString());
        properties.getAi().setEnabled(true);
        properties.getAi().setBaseUrl(baseUrl);
        properties.getAi().setModel("m");

        Store store = new Store(properties);
        store.init();
        Analysis analysis = finishedRun(store, reportWithOneImpact());
        AnalysisService service = service(store, properties);

        Analysis before = store.analysis(analysis.id);
        service.saveAiReview(analysis.id);
        Analysis after = store.analysis(analysis.id);

        // finishAnalysis would have rewritten status/progress/finished_at; the narrow update must not.
        assertEquals(Analysis.DONE, after.status);
        assertEquals(before.finishedAt, after.finishedAt, "a补生成 must not re-stamp finished_at");
        assertEquals(before.durationMs, after.durationMs, "nor the duration of the original run");
        assertEquals(before.progressPercent, after.progressPercent);
        assertEquals(before.summaryJson, after.summaryJson);
    }

    @Test
    void failedCallIsStoredAsAGeneratedFalseSection(@TempDir Path tmp) throws Exception {
        String baseUrl = startEndpoint(401, "ignored");
        AppProperties properties = new AppProperties();
        properties.setDataDir(tmp.toString());
        properties.getAi().setEnabled(true);
        properties.getAi().setBaseUrl(baseUrl);
        properties.getAi().setModel("m");

        Store store = new Store(properties);
        store.init();
        Analysis analysis = finishedRun(store, reportWithOneImpact());
        AnalysisService service = service(store, properties);

        Map<String, Object> ai = service.saveAiReview(analysis.id);

        // The endpoint answers 200 with generated:false; the page shows the reason instead of
        // falling back to "本次没有生成", which would hide a configuration mistake.
        assertEquals(false, ai.get("generated"));
        assertTrue(ai.get("error").toString().contains("401"));
        assertNotNull(service.report(analysis.id).ai);
        assertEquals(false, service.report(analysis.id).ai.get("generated"));
    }

    @Test
    void analysisWithoutAReportReturnsNullSoTheControllerCanAnswer409(@TempDir Path tmp) {
        AppProperties properties = new AppProperties();
        properties.setDataDir(tmp.toString());
        properties.getAi().setEnabled(true);
        properties.getAi().setBaseUrl("http://127.0.0.1:9/v1");
        properties.getAi().setModel("m");

        Store store = new Store(properties);
        store.init();
        RepoSource source = new RepoSource();
        source.name = "pending";
        source.type = "LOCAL_DIR";
        source.location = "/tmp/pending";
        store.saveSource(source);
        Analysis running = new Analysis();
        running.sourceId = source.id;
        running.sourceName = "pending";
        running.sourceType = "LOCAL_DIR";
        running.mode = "GIT_DIFF";
        store.createAnalysis(running);

        AnalysisService service = service(store, properties);
        assertNull(service.saveAiReview(running.id),
                "an unfinished analysis has no report, which is the 409 case");
    }

    @Test
    void unconfiguredInstanceRefusesWithTheReason(@TempDir Path tmp) {
        AppProperties properties = new AppProperties();
        properties.setDataDir(tmp.toString());
        properties.getAi().setEnabled(true);
        // Enabled but no model: the exact state the 400 message has to describe.
        properties.getAi().setBaseUrl("https://x.test/v1");

        Store store = new Store(properties);
        store.init();
        Analysis analysis = finishedRun(store, reportWithOneImpact());
        AnalysisService service = service(store, properties);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.saveAiReview(analysis.id));
        assertTrue(refused.getMessage().contains("AI 审核未启用或未配置完成"),
                "got: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("已启用但未填写模型"),
                "the message must name what is missing: " + refused.getMessage());
    }
}
