package com.geek.association;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The analysis page has to be able to explain a run that produced no report.
 *
 * A failed analysis stores no report_json, and the page hides #an-body whenever there is no report.
 * The progress/failure card used to live inside #an-body, so the failure reason was rendered into a
 * display:none container and the user saw "暂无数据" on a record that had, in fact, failed. Keeping
 * the card outside that container is the invariant this test locks down.
 */
class AnalysisFailureVisibilityTest {

    private static String staticAsset(String name) throws IOException {
        try (InputStream in = AnalysisFailureVisibilityTest.class.getResourceAsStream("/static/" + name)) {
            assertNotNull(in, "missing static asset: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void progressAndFailureCardsAreNotNestedInsideTheReportOnlyContainer() throws IOException {
        String html = staticAsset("index.html");

        int bodyOpen = html.indexOf("id=\"an-body\"");
        int bodyClose = html.indexOf("</div><!-- /an-body -->");
        assertTrue(bodyOpen > 0, "#an-body is missing from the analysis page");
        assertTrue(bodyClose > bodyOpen, "#an-body has no closing marker, so nesting cannot be checked");

        String insideBody = html.substring(bodyOpen, bodyClose);
        assertFalse(insideBody.contains("id=\"an-progress-card\""),
                "the progress card must sit outside #an-body: #an-body is hidden without a report, "
                        + "which is exactly the state a failed run leaves behind");
        assertFalse(insideBody.contains("id=\"an-progress-failure\""),
                "the failure reason must sit outside #an-body, or it is invisible when a run fails");
    }

    @Test
    void failureReasonHasADedicatedReadableElement() throws IOException {
        String html = staticAsset("index.html");
        // The reason is often a long exception message (paths, URLs, ref names); the one-line
        // progress detail is not enough room for it.
        assertTrue(html.contains("id=\"an-progress-failure-reason\""),
                "a failed run needs its own element for the reason, not just the progress detail line");
        assertTrue(html.contains("id=\"an-progress-title\""),
                "the card title must be addressable so it can read 分析失败 instead of 分析进度");
    }

    @Test
    void failedStatusIsRenderedAsARealStateNotAsNoData() throws IOException {
        String js = staticAsset("app.js");
        // The page derives its pill and empty state from the selected record, which is the only
        // source of truth for a run that stored no report.
        assertTrue(js.contains("function selectedAnalysis()"),
                "the analysis page needs the selected record to describe failed and in-flight runs");
        assertTrue(js.contains("an-progress-failure-reason"),
                "app.js must write the failure reason into the dedicated element");
        assertTrue(js.contains("failedStageLabel"),
                "a failed run should name the stage it died in, not print the raw FAILED status");
    }
}
