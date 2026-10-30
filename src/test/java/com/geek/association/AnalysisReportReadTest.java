package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.model.Analysis;
import com.geek.association.model.RepoSource;
import com.geek.association.report.ImpactReport;
import com.geek.association.service.AnalysisService;
import com.geek.association.store.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a row must not drag the report blob out of SQLite.
 *
 * report_json grows with the review queue (hundreds of KB for a few hundred impacts), and every
 * status poll, delete check and list row used to select it. These tests pin the split so a future
 * {@code SELECT *} cannot quietly reintroduce the read.
 */
class AnalysisReportReadTest {

    private static Store store(Path tmp) {
        AppProperties properties = new AppProperties();
        properties.setDataDir(tmp.toString());
        Store store = new Store(properties);
        store.init();
        return store;
    }

    private static Analysis finishedRun(Store store, Object report) {
        RepoSource source = new RepoSource();
        source.name = "bench";
        source.type = "LOCAL_DIR";
        source.location = "/tmp/bench";
        store.saveSource(source);

        Analysis analysis = new Analysis();
        analysis.sourceId = source.id;
        analysis.sourceName = "bench";
        analysis.sourceType = "LOCAL_DIR";
        analysis.mode = "GIT_DIFF";
        store.createAnalysis(analysis);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("impactedSymbols", 432);
        store.finishAnalysis(analysis.id, Analysis.DONE, null, 1000, summary, report);
        return analysis;
    }

    @Test
    void plainAnalysisReadLeavesTheReportBlobUnread(@TempDir Path tmp) {
        Store store = store(tmp);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("impacts", "x".repeat(200_000));
        Analysis analysis = finishedRun(store, report);

        Analysis light = store.analysis(analysis.id);
        assertNotNull(light);
        assertEquals(Analysis.DONE, light.status);
        assertNull(light.reportJson,
                "analysis(id) must not read report_json: status/progress readers never use it");

        Analysis full = store.analysisWithReport(analysis.id);
        assertNotNull(full);
        assertNotNull(full.reportJson, "analysisWithReport(id) is the read that does want the blob");
        assertTrue(full.reportJson.length() > 100_000, "the stored report must come back whole");
        // Same row either way: the split must not change any other column.
        assertEquals(light.progressStage, full.progressStage);
        assertEquals(light.progressPercent, full.progressPercent);
        assertEquals(light.summaryJson, full.summaryJson);
    }

    @Test
    void listQueriesNeverCarryTheReport(@TempDir Path tmp) {
        Store store = store(tmp);
        Analysis analysis = finishedRun(store, Map.of("impacts", "x".repeat(50_000)));

        assertNull(store.analyses(30).get(0).reportJson, "the queue list must not read report_json");
        assertNull(store.analysesOfSource(analysis.sourceId).get(0).reportJson,
                "the per-source list must not read report_json");
    }

    /**
     * 老报告没有 impactsTotal / impactsShown / impactsTruncated 这三个字段。
     *
     * <p>反序列化后它们是 0，页头会把一份完整报告显示成「0 项影响」，而影响列表就在下面。
     * 读取时用清单条数补齐：老报告从未截断，清单就是全部结果。</p>
     */
    @Test
    void legacyReportWithoutCapFieldsIsBackfilledFromTheList(@TempDir Path tmp) {
        AppProperties properties = new AppProperties();
        properties.setDataDir(tmp.toString());
        Store store = new Store(properties);
        store.init();
        Analysis analysis = finishedRun(store, Map.of("impacts",
                List.of(Map.of("id", 1), Map.of("id", 2), Map.of("id", 3))));

        ImpactReport report = new AnalysisService(store, properties, null, null).report(analysis.id);

        assertNotNull(report);
        assertEquals(3, report.impactsTotal);
        assertEquals(3, report.impactsShown);
        assertEquals(0, report.impactsTruncated);
        assertNull(report.impactsNote, "没有截断就不该出现截断说明");
    }

    @Test
    void reportJsonSurvivesTheSplitWhenActuallyRequested(@TempDir Path tmp) {
        Store store = store(tmp);
        Analysis analysis = finishedRun(store, Map.of("analysisId", 7));

        Analysis full = store.analysisWithReport(analysis.id);
        assertNotNull(full.reportJson);
        assertEquals(7, (int) com.geek.association.util.Json.readMap(full.reportJson).get("analysisId"));
    }
}
