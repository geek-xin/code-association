package com.geek.association.update;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * A process-wide "do not call GitHub until then" latch.
 *
 * The quota is per IP, not per caller, so every part of this feature shares it: a scheduled check, a
 * manual refresh and a download retry that all notice a 403 would otherwise each wait out their own
 * backoff and, together, keep the installation limited for the rest of the hour. One gate that all
 * of them consult turns "we are out of quota" into a single fact with a single deadline.
 *
 * Three properties are deliberate:
 *
 * <ul>
 *   <li><b>It never throws and never blocks.</b> It is a hint, not a lock: callers ask
 *       {@link #isBlocked()} and decide for themselves whether to skip or to proceed, so a stale or
 *       absurd deadline can cost one skipped poll but can never hang a request;</li>
 *   <li><b>A missing deadline is not a lock.</b> GitHub does not always send a reset time, and
 *       {@code noteRateLimited(null)} recording "blocked forever" would disable updates for the life
 *       of the process over one unreadable header;</li>
 *   <li><b>The deadline only moves forward while limited.</b> Two threads reporting the same
 *       exhausted quota must not shorten each other's wait, so a later report wins and an earlier
 *       one is ignored. Only {@link #noteSuccess()} closes the gate early, and that is on purpose:
 *       the reset time is GitHub's prediction, while a 200 is evidence that the quota is back.</li>
 * </ul>
 */
public class RateLimitGate {

    private static final Logger log = LoggerFactory.getLogger(RateLimitGate.class);

    /**
     * When requests may go out again, or null when nothing is blocked.
     *
     * Volatile rather than synchronized: the field is read on every check and written once per
     * rate-limit event, the value is a single immutable instant, and a reader that sees the old one
     * simply behaves as it did a moment ago.
     */
    private volatile Instant blockedUntil;

    /**
     * Records that GitHub refused a request because the quota is gone.
     *
     * A null or already-past deadline is ignored: the first carries no information, and the second
     * would install a lock that is already expired - visible as {@code isBlocked() == false} but
     * confusing in the status payload. Moving the deadline backwards is refused for the same reason.
     */
    public void noteRateLimited(Instant resetAt) {
        if (resetAt == null) {
            return;
        }
        if (!resetAt.isAfter(Instant.now())) {
            log.debug("ignoring an already-elapsed rate-limit deadline: {}", resetAt);
            return;
        }
        Instant current = blockedUntil;
        if (current != null && !resetAt.isAfter(current)) {
            // Keep the later of the two: a second reporter must not shorten the wait.
            return;
        }
        blockedUntil = resetAt;
        log.info("GitHub rate limit reached, holding update requests until {}", resetAt);
    }

    /**
     * Records that a request went through, which means the quota is back.
     *
     * Clearing on success is what makes the gate self-healing: the deadline is a prediction, and a
     * real 200 is better evidence than any header. Callers only report success for requests they
     * actually made, so this cannot open the gate on the strength of a cached answer.
     */
    public void noteSuccess() {
        if (blockedUntil != null) {
            log.debug("clearing the rate-limit deadline after a successful request");
            blockedUntil = null;
        }
    }

    /** The instant requests may resume, or null when nothing is blocking them. */
    public Instant blockedUntil() {
        Instant current = blockedUntil;
        if (current == null) {
            return null;
        }
        if (!current.isAfter(Instant.now())) {
            // Report an expired deadline as "nothing to wait for" instead of a past instant: every
            // caller would have to compare against the clock anyway, and one of them would forget.
            return null;
        }
        return current;
    }

    /** Whether a request should be skipped right now. */
    public boolean isBlocked() {
        return blockedUntil() != null;
    }

    /** How many seconds remain, or 0 when not blocked; the status payload shows this to the user. */
    public long remainingSeconds() {
        Instant until = blockedUntil();
        if (until == null) {
            return 0L;
        }
        long seconds = until.getEpochSecond() - Instant.now().getEpochSecond();
        return Math.max(0L, seconds);
    }
}
