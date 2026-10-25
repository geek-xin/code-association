package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.diff.ChangeDetector;
import com.geek.association.diff.FileDiffCollector;
import com.geek.association.impact.ImpactEngine;
import com.geek.association.impact.ImpactGraph;
import com.geek.association.model.ChangeRecord;
import com.geek.association.model.CodeIndex;
import com.geek.association.model.FileChange;
import com.geek.association.model.ImpactResult;
import com.geek.association.parse.CodeIndexer;
import com.geek.association.report.ImpactReport;
import com.geek.association.report.ReportBuilder;
import com.geek.association.source.DirSource;
import com.geek.association.source.RevisionPair;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end test on a synthetic four module project.
 *
 * The head revision changes UserService.isActive from "exists and status == 1" to "exists",
 * narrows the selectById SQL columns, flips a config flag and changes the response type of
 * GET /api/users/{id}. The assertions below are the acceptance criteria of the whole engine.
 */
class ImpactAnalysisTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixture");

    @Test
    void detectsCrossModuleBehaviourImpact() {
        AppProperties properties = new AppProperties();
        properties.setMaxDepth(8);

        RevisionPair revisions = new RevisionPair();
        revisions.mode = "FIXTURE";
        revisions.base = new DirSource(FIXTURE.resolve("base"), "base", 5000);
        revisions.head = new DirSource(FIXTURE.resolve("head"), "head", 5000);

        CodeIndex baseIndex = new CodeIndexer(5000).index(revisions.base, "base", "base");
        CodeIndex headIndex = new CodeIndexer(5000).index(revisions.head, "head", "head");

        List<FileChange> files = new FileDiffCollector(revisions.base, revisions.head).collect();
        assertFalse(files.isEmpty(), "fixture diff must not be empty");

        List<ChangeRecord> changes = new ChangeDetector(baseIndex, headIndex).detect(files);
        long seq = 1;
        for (ChangeRecord c : changes) {
            c.id = seq++;
        }

        ImpactGraph graph = new ImpactGraph(baseIndex, headIndex);
        ImpactEngine engine = new ImpactEngine(properties);
        List<ImpactResult> impacts = engine.analyze(graph, changes, ImpactEngine.options(properties, false));
        long impactSeq = 1;
        for (ImpactResult i : impacts) {
            i.id = impactSeq++;
        }

        // ---- the index really understood the project ----
        assertEquals(5, headIndex.modules.size(), "aggregator plus four maven modules expected");
        assertNotNull(headIndex.symbol("order-service::com.example.order.OrderService#createOrder(Long,Money)"),
                "method key of OrderService.createOrder must exist");
        assertTrue(headIndex.symbols.values().stream().anyMatch(s -> "API_ENDPOINT".equals(s.type)
                && "/api/users/{}".equals(s.meta.get("path"))), "spring endpoint must be indexed");
        assertTrue(headIndex.symbols.values().stream().anyMatch(s -> "DATABASE_TABLE".equals(s.type)
                && "t_user".equals(s.qualifiedName)), "mybatis table must be indexed");

        // ---- change detection ----
        assertTrue(changes.stream().anyMatch(c -> ChangeDetector.METHOD_CONDITION_CHANGED.equals(c.changeType)
                        && c.qualifiedName.contains("UserService#isActive")
                        && c.semanticSummary.contains("分支条件")),
                "isActive condition change must be classified as METHOD_CONDITION_CHANGED");
        assertTrue(changes.stream().anyMatch(c -> ChangeDetector.API_CONTRACT_CHANGED.equals(c.changeType)),
                "API response type change must be detected");
        assertTrue(changes.stream().anyMatch(c -> ChangeDetector.SQL_CHANGED.equals(c.changeType)),
                "mybatis select change must be detected");
        assertTrue(changes.stream().anyMatch(c -> ChangeDetector.CONFIG_CHANGED.equals(c.changeType)),
                "config value change must be detected");

        // ---- impact propagation across modules ----
        ImpactResult createOrder = impacts.stream()
                .filter(i -> "order-service".equals(i.targetModule))
                .filter(i -> i.targetQualifiedName.endsWith("OrderService#createOrder"))
                .findFirst().orElse(null);
        assertNotNull(createOrder, "OrderService.createOrder must be reported as impacted");
        assertEquals("BEHAVIOR", createOrder.impactType);
        assertEquals("HIGH", createOrder.riskLevel);
        assertTrue(createOrder.reason.contains("分支条件"),
                "reason must explain the control dependency, was: " + createOrder.reason);

        ImpactResult auditOrder = impacts.stream()
                .filter(i -> "order-service".equals(i.targetModule))
                .filter(i -> i.targetQualifiedName.endsWith("OrderService#auditOrder"))
                .findFirst().orElse(null);
        assertNotNull(auditOrder, "OrderService.auditOrder is still reached, but with lower risk");
        assertTrue(auditOrder.riskScore < createOrder.riskScore,
                "log-only consumption must score below a branch condition");

        ImpactResult payment = impacts.stream()
                .filter(i -> "payment-service".equals(i.targetModule))
                .filter(i -> i.targetQualifiedName.endsWith("PaymentService#validateUser"))
                .findFirst().orElse(null);
        assertNotNull(payment, "PaymentService.validateUser must be reached");

        ImpactResult controller = impacts.stream()
                .filter(i -> i.targetQualifiedName.endsWith("OrderController#create"))
                .findFirst().orElse(null);
        assertNotNull(controller, "the controller of the impacted service must be reached through propagation");
        assertTrue(controller.depth >= 2, "controller is an indirect impact");

        // ---- the API change must reach the caller in another module ----
        assertTrue(impacts.stream().anyMatch(i -> "API_CONTRACT".equals(i.impactType)),
                "the changed endpoint must produce an API contract impact");

        // ---- report shape ----
        Map<String, String> diffs = new FileDiffCollector(revisions.base, revisions.head).unifiedDiffs(files, 20, 120);
        ImpactReport report = new ReportBuilder().build(revisions, headIndex, baseIndex, changes, impacts, diffs);
        assertEquals(1, report.summary.changedModules, "only user-service was modified");
        assertEquals(3, report.summary.impactedModules, "order-service, payment-service and user-service are impacted");
        assertTrue(report.summary.highRisk > 0, "report must contain high risk items");
        assertFalse(report.moduleImpacts.isEmpty(), "module impact map must not be empty");
        assertFalse(report.moduleGraph.isEmpty(), "module graph must contain cross module edges");
        assertTrue(report.moduleGraph.stream().anyMatch(e -> "DEPENDS_ON".equals(e.type)),
                "maven module dependency must be part of the graph");
        assertTrue(report.notes.stream().anyMatch(n -> n.contains("跨模块关系")),
                "report notes must use Chinese analysis conclusions");
    }
}
