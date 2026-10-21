package com.dia.ismdtoolbackend.client.validation;

import com.dia.ismdtoolbackend.exception.ValidationServiceUnavailableException;
import com.dia.ismdtoolbackend.testsupport.time.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class
ValidationCircuitBreakerTest {

    private static final long COOLDOWN_MS = 30_000;

    private final MutableClock clock = MutableClock.of("2026-10-21T10:00:00Z");

    private ValidationCircuitBreaker breaker(int threshold) {
        return new ValidationCircuitBreaker("v", threshold, COOLDOWN_MS, clock);
    }

    private ValidationServiceUnavailableException down() {
        return new ValidationServiceUnavailableException("Validační služba", "down");
    }

    @Test
    void startsClosed() {
        ValidationCircuitBreaker breaker = breaker(2);
        assertFalse(breaker.isOpen());
        assertEquals("ok", breaker.call(() -> "ok"));
        assertFalse(breaker.isOpen());
    }

    @Test
    void opensAfterThreshold() {
        ValidationCircuitBreaker breaker = breaker(2);

        assertThrows(ValidationServiceUnavailableException.class, () -> breaker.call(() -> { throw down(); }));
        assertFalse(breaker.isOpen(), "one failure below threshold — still closed");

        assertThrows(ValidationServiceUnavailableException.class, () -> breaker.call(() -> { throw down(); }));
        assertTrue(breaker.isOpen(), "second failure hits threshold — now open");
    }

    @Test
    void openBreakerFastFailsWithoutCallingAction() {
        ValidationCircuitBreaker breaker = breaker(1);
        assertThrows(ValidationServiceUnavailableException.class, () -> breaker.call(() -> { throw down(); }));
        assertTrue(breaker.isOpen());

        boolean[] ran = {false};
        assertThrows(ValidationServiceUnavailableException.class,
                () -> breaker.call(() -> { ran[0] = true; return "x"; }));
        assertFalse(ran[0], "open breaker must not invoke the action");
    }

    @Test
    void staysOpenUntilTheLastMillisecondOfTheCooldown() {
        ValidationCircuitBreaker breaker = breaker(1);
        assertThrows(ValidationServiceUnavailableException.class, () -> breaker.call(() -> { throw down(); }));

        clock.advanceMillis(COOLDOWN_MS - 1);

        assertTrue(breaker.isOpen());
        boolean[] ran = {false};
        assertThrows(ValidationServiceUnavailableException.class,
                () -> breaker.call(() -> { ran[0] = true; return "x"; }));
        assertFalse(ran[0], "one millisecond before the cooldown ends the breaker still fast-fails");

        clock.advanceMillis(1);

        assertFalse(breaker.isOpen(), "the cooldown has elapsed exactly");
        assertEquals("ok", breaker.call(() -> "ok"), "the call at the cooldown boundary is the trial");
    }

    @Test
    void halfOpenTrialClosesOnSuccess() {
        ValidationCircuitBreaker breaker = breaker(1);
        assertThrows(ValidationServiceUnavailableException.class, () -> breaker.call(() -> { throw down(); }));
        assertTrue(breaker.isOpen(), "breaker is open during cooldown");

        // While open, a call fast-fails without running the action.
        boolean[] ran = {false};
        assertThrows(ValidationServiceUnavailableException.class,
                () -> breaker.call(() -> { ran[0] = true; return "x"; }));
        assertFalse(ran[0], "open breaker fast-fails");

        clock.advanceMillis(COOLDOWN_MS + 1);
        assertEquals("ok", breaker.call(() -> "ok"), "trial call runs and succeeds");
        assertFalse(breaker.isOpen(), "successful trial closes the breaker");
    }

    @Test
    void halfOpenTrialFailureReopens() {
        ValidationCircuitBreaker breaker = breaker(1);
        assertThrows(ValidationServiceUnavailableException.class, () -> breaker.call(() -> { throw down(); }));
        assertTrue(breaker.isOpen());

        clock.advanceMillis(COOLDOWN_MS + 1);
        // Trial fails → breaker re-opens for another cooldown window.
        boolean[] ran = {false};
        assertThrows(ValidationServiceUnavailableException.class,
                () -> breaker.call(() -> { ran[0] = true; throw down(); }));
        assertTrue(ran[0], "the trial call actually ran");
        assertTrue(breaker.isOpen(), "failed trial re-opens the breaker");

        clock.advanceMillis(COOLDOWN_MS - 1);
        assertTrue(breaker.isOpen(), "the new cooldown window counts from the failed trial");
    }

    @Test
    void halfOpenAdmitsExactlyOneTrialUnderConcurrency() throws InterruptedException {
        // When many threads cross the cooldown boundary at once, exactly one runs the trial
        // action and the rest fast-fail. The clock stands still, so the trial window cannot
        // expire while the threads race; the trial holds until every other caller has failed.
        ValidationCircuitBreaker breaker = breaker(1);
        assertThrows(ValidationServiceUnavailableException.class, () -> breaker.call(() -> { throw down(); }));
        assertTrue(breaker.isOpen());

        clock.advanceMillis(COOLDOWN_MS + 1);

        int threads = 16;
        AtomicInteger actionRuns = new AtomicInteger(0);
        AtomicInteger fastFails = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch losersFailed = new CountDownLatch(threads - 1);
        CountDownLatch done = new CountDownLatch(threads);

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    boolean[] ranTrial = {false};
                    ready.countDown();
                    try {
                        go.await();
                        breaker.call(() -> {
                            ranTrial[0] = true;
                            actionRuns.incrementAndGet();
                            awaitQuietly(losersFailed);
                            throw down();
                        });
                    } catch (ValidationServiceUnavailableException e) {
                        fastFails.incrementAndGet();
                        if (!ranTrial[0]) {
                            losersFailed.countDown();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS), "all threads started");
            go.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "all threads finished");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, actionRuns.get(), "exactly one thread runs the half-open trial");
        assertEquals(threads, fastFails.get(), "trial failed → every caller (trial + losers) sees unavailable");
        assertTrue(breaker.isOpen(), "failed trial keeps the breaker open");
    }

    @Test
    void callerThatLosesTheTrialElectionFastFails() {
        // The loser reads the expired window, then another caller wins the trial before the
        // loser's election; the clock hook runs that winning trial between the two steps.
        Runnable[] onNextRead = {null};
        Clock hooked = new Clock() {
            @Override public ZoneId getZone() { return clock.getZone(); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() {
                Runnable hook = onNextRead[0];
                onNextRead[0] = null;
                if (hook != null) {
                    hook.run();
                }
                return clock.instant();
            }
        };
        ValidationCircuitBreaker breaker = new ValidationCircuitBreaker("v", 1, COOLDOWN_MS, hooked);
        assertThrows(ValidationServiceUnavailableException.class, () -> breaker.call(() -> { throw down(); }));
        clock.advanceMillis(COOLDOWN_MS + 1);

        boolean[] winnerRan = {false};
        onNextRead[0] = () -> assertThrows(ValidationServiceUnavailableException.class,
                () -> breaker.call(() -> { winnerRan[0] = true; throw down(); }));
        boolean[] loserRan = {false};
        ValidationServiceUnavailableException lost = assertThrows(ValidationServiceUnavailableException.class,
                () -> breaker.call(() -> { loserRan[0] = true; return "x"; }));

        assertTrue(winnerRan[0], "the winning caller ran the trial");
        assertFalse(loserRan[0], "the losing caller must not run a second trial");
        assertTrue(lost.getMessage().contains("trial in progress"), lost.getMessage());
    }

    @Test
    void rejectionStyleExceptionsDoNotTrip() {
        ValidationCircuitBreaker breaker = breaker(1);
        // A non-unavailable exception propagates and does NOT count toward opening.
        assertThrows(IllegalStateException.class, () -> breaker.call(() -> { throw new IllegalStateException("4xx"); }));
        assertFalse(breaker.isOpen());
    }

    @Test
    void publicConstructorUsesTheSystemClock() {
        ValidationCircuitBreaker breaker = new ValidationCircuitBreaker("v", 1, COOLDOWN_MS);
        assertThrows(ValidationServiceUnavailableException.class, () -> breaker.call(() -> { throw down(); }));
        assertTrue(breaker.isOpen(), "a 30 s cooldown has not elapsed on the real clock");
    }

    /** Bounded so a second (wrongly admitted) trial fails the test instead of hanging it. */
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
