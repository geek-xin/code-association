package com.geek.association.review;

/**
 * How far one optional AI stage has got, as one short Chinese line plus a fraction of the work the
 * <em>caller</em> is responsible for.
 *
 * <p>The fraction is relative on purpose. The review pipeline knows how many chunks and comment
 * calls it still has to make; only the analysis service knows that the whole AI section is the last
 * few percent of the run. Keeping the mapping with the owner of the scale is what lets both speak
 * the same interface without one of them hard-coding the other's weights.
 *
 * <p>This exists because the AI section used to report nothing at all: one write of "96%" before the
 * first model call and nothing until the report was stored. For a run whose comment layer makes a
 * dozen calls over several minutes that is indistinguishable from a hang.
 */
@FunctionalInterface
public interface AiProgress {

    /**
     * @param detail   the line shown under the progress bar, e.g. "AI 评审：分块 2/6"
     * @param fraction 0..1 of this caller's own work; values outside are clamped by the consumer
     */
    void update(String detail, double fraction);

    /** For callers with nothing to show: the two-argument {@code explain()}, and tests. */
    static AiProgress none() {
        return (detail, fraction) -> {
        };
    }
}
