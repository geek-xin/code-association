package com.geek.association.review;

/**
 * Wall-clock budget for one AI section: the summary call plus every review comment call.
 *
 * <p>Why a budget on top of the per-request timeout: {@code HttpRequest.timeout} bounds one
 * exchange, and a report makes up to a dozen of them (summary, up to six review chunks, up to six
 * re-location calls, one reflection pass). Fourteen sequential 60-second timeouts is a quarter of
 * an hour during which the analysis progress sits on one unchanging number - which reads as a hung
 * run even when every call is eventually answered.
 *
 * <p>It is also the only reliable stop for a stalled body. Measured on JDK 21: a server that sends
 * response headers and then stops writing keeps {@code HttpClient.send} blocked past
 * {@code HttpRequest.timeout}, because that timeout covers the response, not the body. The analysis
 * would stay on its last step until the process is killed. The budget is enforced around the whole
 * exchange (including the body read) for exactly this case.
 */
public final class AiBudget {

    /** {@code System.nanoTime()} is monotonic; a wall-clock deadline would jump on NTP steps. */
    private final long deadlineNanos;
    private final int seconds;

    private AiBudget(long deadlineNanos, int seconds) {
        this.deadlineNanos = deadlineNanos;
        this.seconds = seconds;
    }

    /**
     * A budget of {@code seconds}, or unlimited when the value is not positive.
     *
     * Unlimited is the honest reading of "the operator configured no limit", and it keeps the
     * comment layer usable in tests without threads or sleeping.
     */
    public static AiBudget ofSeconds(int seconds) {
        if (seconds <= 0) {
            return unbounded();
        }
        return new AiBudget(System.nanoTime() + seconds * 1_000_000_000L, seconds);
    }

    public static AiBudget unbounded() {
        return new AiBudget(Long.MAX_VALUE, 0);
    }

    /** The configured length, or 0 for unlimited; used in log lines and user-facing text. */
    public int seconds() {
        return seconds;
    }

    public boolean unlimited() {
        return deadlineNanos == Long.MAX_VALUE;
    }

    /** Milliseconds left, 0 when spent. Capped so callers can hand it to a timeout without overflow. */
    public long remainingMillis() {
        if (unlimited()) {
            return Long.MAX_VALUE;
        }
        long remaining = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        return Math.max(0, remaining);
    }

    public boolean expired() {
        return remainingMillis() <= 0;
    }

    /**
     * The timeout to give one request: the configured per-request timeout, shortened to what is
     * left.
     *
     * Never returns 0: a request that is started at the very end of the budget still gets one
     * second, so the failure the user sees is "the model timed out", not a client-side refusal to
     * send anything at all.
     */
    public int callTimeoutSeconds(int configuredSeconds) {
        int configured = configuredSeconds <= 0 ? 60 : configuredSeconds;
        if (unlimited()) {
            return configured;
        }
        long left = remainingMillis();
        int leftSeconds = (int) Math.max(1, (left + 999) / 1000);
        return Math.min(configured, leftSeconds);
    }

    /** One sentence for the report and the log: why the AI section stopped early. */
    public String expiryReason() {
        return "AI 评审超出时间预算（" + seconds + " 秒），已保留已生成的部分";
    }
}
