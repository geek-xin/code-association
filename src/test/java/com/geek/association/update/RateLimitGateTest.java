package com.geek.association.update;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared "do not call GitHub until then" latch.
 *
 * The interesting cases are all about time and concurrency: a deadline in the past, a deadline that
 * arrives twice, a success that lands while another thread is already limited. None of them needs a
 * network, so none of them is left to chance - the whole point of the gate is that its state is
 * boring and predictable under whatever order the threads happen to run in.
 */
class RateLimitGateTest {

    @Test
    void aFreshGateBlocksNothing() {
        RateLimitGate gate = new RateLimitGate();

        assertFalse(gate.isBlocked());
        assertNull(gate.blockedUntil());
        assertEquals(0L, gate.remainingSeconds());
    }

    @Test
    void aFutureDeadlineBlocksUntilThen() {
        RateLimitGate gate = new RateLimitGate();
        Instant reset = Instant.now().plusSeconds(600);

        gate.noteRateLimited(reset);

        assertTrue(gate.isBlocked());
        assertEquals(reset, gate.blockedUntil());
        assertTrue(gate.remainingSeconds() > 500, "the remaining time is what the UI shows");
        assertTrue(gate.remainingSeconds() <= 600);
    }

    @Test
    void aMissingDeadlineIsNotALock() {
        RateLimitGate gate = new RateLimitGate();

        gate.noteRateLimited(null);

        assertFalse(gate.isBlocked(),
                "GitHub does not always send a reset time, and blocking forever would disable updates");
        assertNull(gate.blockedUntil());
    }

    @Test
    void anAlreadyElapsedDeadlineIsNotALockEither() {
        RateLimitGate gate = new RateLimitGate();

        gate.noteRateLimited(Instant.now().minusSeconds(5));

        assertFalse(gate.isBlocked());
    }

    @Test
    void aDeadlineExpiresOnItsOwn() throws Exception {
        RateLimitGate gate = new RateLimitGate();

        gate.noteRateLimited(Instant.now().plusMillis(200));
        assertTrue(gate.isBlocked());

        Thread.sleep(400);

        assertFalse(gate.isBlocked(), "a past deadline must not keep the gate shut");
        assertNull(gate.blockedUntil(), "an expired deadline is reported as nothing to wait for");
    }

    @Test
    void aSuccessReopensTheGate() {
        RateLimitGate gate = new RateLimitGate();
        gate.noteRateLimited(Instant.now().plusSeconds(600));

        gate.noteSuccess();

        assertFalse(gate.isBlocked(), "a real 200 is better evidence than any header");
        assertNull(gate.blockedUntil());
    }

    @Test
    void aSuccessOnAnOpenGateIsHarmless() {
        RateLimitGate gate = new RateLimitGate();

        assertDoesNotThrow(gate::noteSuccess);
        assertDoesNotThrow(gate::noteSuccess);

        assertFalse(gate.isBlocked());
    }

    @Test
    void aSecondReportNeverShortensTheWait() {
        RateLimitGate gate = new RateLimitGate();
        Instant later = Instant.now().plusSeconds(900);
        gate.noteRateLimited(later);

        gate.noteRateLimited(Instant.now().plusSeconds(60));

        assertEquals(later, gate.blockedUntil(),
                "two threads reporting the same exhausted quota must not cut each other's wait short");
    }

    @Test
    void aLaterReportExtendsTheWait() {
        RateLimitGate gate = new RateLimitGate();
        Instant later = Instant.now().plusSeconds(900);
        gate.noteRateLimited(Instant.now().plusSeconds(60));

        gate.noteRateLimited(later);

        assertEquals(later, gate.blockedUntil());
    }

    @Test
    void concurrentReportersAndReadersLeaveTheGateConsistent() throws Exception {
        RateLimitGate gate = new RateLimitGate();
        int threads = 8;
        int perThread = 50;
        Instant deadline = Instant.now().plusSeconds(600);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger blockedSeen = new AtomicInteger();
        List<Throwable> failures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                boolean reporter = i % 2 == 0;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int n = 0; n < perThread; n++) {
                            if (reporter) {
                                gate.noteRateLimited(deadline);
                            } else if (gate.isBlocked()) {
                                blockedSeen.incrementAndGet();
                            }
                        }
                    } catch (Throwable e) {
                        synchronized (failures) {
                            failures.add(e);
                        }
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "the gate must never block a caller");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(List.of(), failures, "the gate is a hint, so it must never throw");
        assertTrue(blockedSeen.get() > 0, "readers must be able to observe the deadline");
        assertEquals(deadline, gate.blockedUntil());
    }

    @Test
    void theGateKeepsTheLatestDeadlineUnderRacingReports() throws Exception {
        RateLimitGate gate = new RateLimitGate();
        Instant later = Instant.now().plusSeconds(1800);
        Instant earlier = Instant.now().plusSeconds(60);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            // The later report is submitted first but not released first: whichever order the two
            // land in, the gate must end up holding the later deadline.
            pool.submit(() -> {
                start.await();
                gate.noteRateLimited(later);
                return null;
            });
            pool.submit(() -> {
                start.await();
                gate.noteRateLimited(earlier);
                return null;
            });
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(later, gate.blockedUntil());
    }

    @Test
    void theRemainingTimeNeverGoesNegative() throws Exception {
        RateLimitGate gate = new RateLimitGate();
        gate.noteRateLimited(Instant.now().plusMillis(100));

        Thread.sleep(300);

        assertEquals(0L, gate.remainingSeconds());
    }

    /**
     * The gate must answer without waiting, because it is consulted on every poll.
     *
     * Asserted as a timeout rather than by measuring the elapsed time. A wall-clock threshold
     * ("under 100ms") is a statement about the machine, not about the code: on a loaded CI box -
     * or while another build is running - a correct implementation can exceed it and fail the
     * build for no reason. A timeout fails only when the call actually blocks, which is the
     * property this test is about.
     */
    @Test
    void aGateWithALongDeadlineStillAnswersImmediately() {
        RateLimitGate gate = new RateLimitGate();
        gate.noteRateLimited(Instant.now().plus(Duration.ofHours(1)));

        Boolean blocked = assertTimeoutPreemptively(Duration.ofSeconds(2), gate::isBlocked,
                "the gate is consulted on every poll and must never wait");

        assertTrue(blocked);
    }
}
