package com.geek.association;

import com.geek.association.config.AppProperties;
import com.geek.association.model.Analysis;
import com.geek.association.store.Store;
import com.geek.association.web.ApiController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Progress is persisted so a page reload or a second browser can still see the live stage. */
class AnalysisProgressTest {

    @Test
    void progressIsStoredAndReadBackWithTheAnalysis(@TempDir Path tmp) {
        AppProperties properties = new AppProperties();
        properties.setDataDir(tmp.toString());
        Store store = new Store(properties);

        Analysis analysis = new Analysis();
        analysis.sourceId = 7;
        analysis.sourceName = "orders";
        analysis.sourceType = "LOCAL_GIT";
        analysis.mode = "GIT_DIFF";
        store.createAnalysis(analysis);

        store.updateProgress(analysis.id, "INDEX_HEAD", 42,
                "正在索引目标版本", "src/main/java/com/example/OrderService.java",
                List.of("PREPARE", "DIFF"), List.of());

        Analysis loaded = store.analysis(analysis.id);
        assertNotNull(loaded);
        assertEquals("INDEX_HEAD", loaded.progressStage);
        assertEquals(42, loaded.progressPercent);
        assertEquals("正在索引目标版本", loaded.progressLabel);
        assertEquals("src/main/java/com/example/OrderService.java", loaded.progressDetail);
        assertEquals(List.of("PREPARE", "DIFF"), loaded.progressCompleted);
    }

    @Test
    void finishingAnAnalysisClosesTheLastStage(@TempDir Path tmp) {
        AppProperties properties = new AppProperties();
        properties.setDataDir(tmp.toString());
        Store store = new Store(properties);

        Analysis analysis = new Analysis();
        analysis.sourceId = 8;
        analysis.sourceName = "payments";
        analysis.sourceType = "LOCAL_GIT";
        analysis.mode = "GIT_DIFF";
        store.createAnalysis(analysis);

        store.finishAnalysis(analysis.id, Analysis.DONE, null, 1234, null, null);

        Analysis loaded = store.analysis(analysis.id);
        assertEquals("DONE", loaded.progressStage);
        assertEquals(100, loaded.progressPercent);
        assertTrue(loaded.progressCompleted.contains("REPORT"));
        assertTrue(!loaded.progressCompleted.contains("PEERS"),
                "peer comparison was not part of this run");
    }

    @Test
    void statusApiExposesTheLiveProgress(@TempDir Path tmp) {
        AppProperties properties = new AppProperties();
        properties.setDataDir(tmp.toString());
        Store store = new Store(properties);

        Analysis analysis = new Analysis();
        analysis.sourceId = 9;
        analysis.sourceName = "inventory";
        analysis.sourceType = "LOCAL_GIT";
        analysis.mode = "GIT_DIFF";
        store.createAnalysis(analysis);
        store.updateProgress(analysis.id, "INDEX_BASE", 61, "索引基线版本",
                "src/main/java/com/example/StockService.java", List.of("PREPARE", "DIFF", "INDEX_HEAD"),
                List.of("PEERS"));

        ApiController controller = new ApiController(store, null, null, properties, null);
        Map<String, Object> row = controller.analysis(analysis.id, false).getBody();
        assertNotNull(row);
        assertEquals("INDEX_BASE", row.get("progressStage"));
        assertEquals(61, row.get("progressPercent"));
        assertEquals("索引基线版本", row.get("progressLabel"));
        assertEquals(List.of("PREPARE", "DIFF", "INDEX_HEAD"), row.get("progressCompleted"));
        assertEquals(List.of("PEERS"), row.get("progressSkipped"));
    }

    @Test
    void finishingPreservesEarlierProgressAndAddsFinalStage(@TempDir Path tmp) {
        AppProperties properties = new AppProperties();
        properties.setDataDir(tmp.toString());
        Store store = new Store(properties);

        Analysis analysis = new Analysis();
        analysis.sourceId = 10;
        analysis.sourceName = "catalog";
        analysis.sourceType = "LOCAL_GIT";
        analysis.mode = "GIT_DIFF";
        store.createAnalysis(analysis);
        store.updateProgress(analysis.id, "INDEX_BASE", 61, "索引基线版本",
                "CatalogService.java", List.of("PREPARE", "DIFF", "INDEX_HEAD"),
                List.of("PEERS"));

        store.finishAnalysis(analysis.id, Analysis.DONE, null, 4321, null, null);

        Analysis loaded = store.analysis(analysis.id);
        assertEquals("DONE", loaded.progressStage);
        assertEquals(100, loaded.progressPercent);
        assertTrue(loaded.progressCompleted.contains("INDEX_HEAD"));
        assertTrue(loaded.progressCompleted.contains("REPORT"));
        assertTrue(loaded.progressSkipped.contains("PEERS"));
    }
}
