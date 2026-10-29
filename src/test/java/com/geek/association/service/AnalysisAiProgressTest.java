package com.geek.association.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The AI section's slice of the progress bar.
 *
 * The bug this pins: the section wrote 96% once, then spent up to a dozen model calls - minutes, on
 * a slow endpoint - with no further write. From the outside that is the same picture as a hung run,
 * which is exactly how users reported it ("分析经常卡在 96%"). The band has to move inside 96..99
 * and 100 must stay reserved for the report that was actually stored.
 */
class AnalysisAiProgressTest {

    @Test
    void aiStageMovesInsideItsLastFourPercent() {
        assertEquals(96, AnalysisService.aiPercent(0.0));
        assertEquals(98, AnalysisService.aiPercent(0.5));
        assertEquals(99, AnalysisService.aiPercent(1.0));
    }

    @Test
    void aiStageNeverReachesTheFinalPercent() {
        // 100 is written by finishAnalysis only: a bar that arrives before the report exists is the
        // "stuck at the end" picture in reverse, and the page would show a report that is not there.
        assertEquals(99, AnalysisService.aiPercent(7.0));
        assertEquals(99, AnalysisService.aiPercent(Double.MAX_VALUE));
    }

    @Test
    void aiStageStartsAtItsOwnBoundary() {
        // Negative and NaN must not move the bar below the stage start or above the band.
        assertEquals(96, AnalysisService.aiPercent(-2.0));
        assertEquals(96, AnalysisService.aiPercent(Double.NaN));
    }
}
