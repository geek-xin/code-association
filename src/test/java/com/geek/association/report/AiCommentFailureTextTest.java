package com.geek.association.report;

import com.geek.association.review.AiBudget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single sentence the AI card shows when the comment pass did not run to completion.
 *
 * The end-to-end run that motivated these cases produced a card saying only "模型调用超时（5 秒）"
 * for a section that had in fact been cut short by its 8-second budget: the first concrete failure
 * won and the reason the rest was missing never reached the reader. Both facts have to survive, and
 * the budget sentence must survive the length limit, because it is the one that explains the gap.
 */
class AiCommentFailureTextTest {

    private static final String TIMEOUT =
            "第 1 个分块评审失败：模型调用超时（5 秒），请检查接口地址、网络或模型响应速度";

    @Test
    void budgetCutIsNamedAfterTheConcreteFailure() {
        String text = AiExplainer.commentFailureText(TIMEOUT, true, AiBudget.ofSeconds(8));

        assertTrue(text.contains("模型调用超时"), "the actionable failure must stay first: " + text);
        assertTrue(text.contains("AI 评审超出时间预算（8 秒）"), "the truncation reason must be added: " + text);
        assertTrue(text.indexOf("模型调用超时") < text.indexOf("AI 评审超出时间预算"),
                "the cause of the first failure comes before the reason the rest is missing");
    }

    @Test
    void budgetCutAloneExplainsTheMissingComments() {
        assertEquals(AiBudget.ofSeconds(8).expiryReason(),
                AiExplainer.commentFailureText(null, true, AiBudget.ofSeconds(8)));
        assertEquals(AiBudget.ofSeconds(8).expiryReason(),
                AiExplainer.commentFailureText("  ", true, AiBudget.ofSeconds(8)));
    }

    @Test
    void aPlainFailureIsReportedUnchanged() {
        assertEquals("HTTP 401: Invalid API key",
                AiExplainer.commentFailureText("HTTP 401: Invalid API key", false, AiBudget.ofSeconds(8)));
    }

    @Test
    void aCompletePassHasNothingToSay() {
        assertEquals("", AiExplainer.commentFailureText(null, false, AiBudget.ofSeconds(8)));
        assertEquals("", AiExplainer.commentFailureText("  ", false, AiBudget.ofSeconds(8)));
    }

    @Test
    void theBudgetSentenceSurvivesTheLengthLimit() {
        String longError = "x".repeat(400);

        String text = AiExplainer.commentFailureText(longError, true, AiBudget.ofSeconds(8));

        assertTrue(text.contains("AI 评审超出时间预算"), "the reason for the gap must not be truncated away: " + text);
        assertTrue(text.length() < 400, "the first error is what gets shortened");
    }

    @Test
    void aFailureThatCoincidedWithTheBudgetIsStillABudgetCut() {
        // The pipeline can only flag calls it skipped. When the budget shortens the timeout of the
        // last call and that call fails, nothing is skipped - yet the budget is why the comments are
        // missing, and an end-to-end run showed a report blaming only the five-second timeout.
        assertTrue(AiExplainer.budgetTruncated(false, true, TIMEOUT),
                "a failure while the section was out of time must name the budget");
        assertTrue(AiExplainer.budgetTruncated(true, false, null),
                "a skipped call is a truncation on its own");
    }

    @Test
    void aCompletePassIsNotCalledTruncated() {
        assertFalse(AiExplainer.budgetTruncated(false, true, null),
                "the budget ran out but every call answered: the list is complete");
        assertFalse(AiExplainer.budgetTruncated(false, false, TIMEOUT),
                "no budget involved, so the concrete error stands alone");
    }
}
