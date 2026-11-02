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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

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
        assertThat(breaker.isOpen()).isFalse();
        assertThat(breaker.call(() -> "ok")).isEqualTo("ok");
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    void opensAfterThreshold() {
        ValidationCircuitBreaker breaker = breaker(2);

        assertThatThrownBy(() -> breaker.call(() -> { throw down(); }))
                .isInstanceOf(ValidationServiceUnavailableException.class);
        assertThat(breaker.isOpen()).as("one failure below threshold — still closed").isFalse();

        assertThatThrownBy(() -> breaker.call(() -> { throw down(); }))
                .isInstanceOf(ValidationServiceUnavailableException.class);
        assertThat(breaker.isOpen()).as("second failure hits threshold — now open").isTrue();
    }

    @Test
    void openBreakerFastFailsWithoutCallingAction() {
        ValidationCircuitBreaker breaker = breaker(1);
        assertThatThrownBy(() -> breaker.call(() -> { throw down(); }))
                .isInstanceOf(ValidationServiceUnavailableException.class);
        assertThat(breaker.isOpen()).isTrue();

        boolean[] ran = {false};
        assertThatThrownBy(() -> breaker.call(() -> { ran[0] = true; return "x"; }))
                .isInstanceOf(ValidationServiceUnavailableException.class);
        assertThat(ran[0]).as("open breaker must not invoke the action").isFalse();
    }

    @Test
    void staysOpenUntilTheLastMillisecondOfTheCooldown() {
        ValidationCircuitBreaker breaker = breaker(1);
        assertThatThrownBy(() -> breaker.call(() -> { throw down(); }))
                .isInstanceOf(ValidationServiceUnavailableException.class);

        clock.advanceMillis(COOLDOWN_MS - 1);

        assertThat(breaker.isOpen()).isTrue();
        boolean[] ran = {false};
        assertThatThrownBy(() -> breaker.call(() -> { ran[0] = true; return "x"; }))
                .isInstanceOf(ValidationServiceUnavailableException.class);
        assertThat(ran[0]).as("one millisecond before the cooldown ends the breaker still fast-fails").isFalse();

        clock.advanceMillis(1);

        assertThat(breaker.isOpen()).as("the cooldown has elapsed exactly").isFalse();
        assertThat(breaker.call(() -> "ok")).as("the call at the cooldown boundary is the trial").isEqualTo("ok");
    }

    @Test
    void halfOpenTrialClosesOnSuccess() {
        ValidationCircuitBreaker breaker = breaker(1);
        assertThatThrownBy(() -> breaker.call(() -> { throw down(); }))
                .isInstanceOf(ValidationServiceUnavailableException.class);
        assertThat(breaker.isOpen()).as("breaker is open during cooldown").isTrue();

        // While open, a call fast-fails without running the action.
        boolean[] ran = {false};
        assertThatThrownBy(() -> breaker.call(() -> { ran[0] = true; return "x"; }))
                .isInstanceOf(ValidationServiceUnavailableException.class);
        assertThat(ran[0]).as("open breaker fast-fails").isFalse();

        clock.advanceMillis(COOLDOWN_MS + 1);
        assertThat(breaker.call(() -> "ok")).as("trial call runs and succeeds").isEqualTo("ok");
        assertThat(breaker.isOpen()).as("successful trial closes the breaker").isFalse();
    }

    @Test
    void halfOpenTrialFailureReopens() {
        ValidationCircuitBreaker breaker = breaker(1);
        assertThatThrownBy(() -> breaker.call(() -> { throw down(); }))
                .isInstanceOf(ValidationServiceUnavailableException.class);
        assertThat(breaker.isOpen()).isTrue();

        clock.advanceMillis(COOLDOWN_MS + 1);
        // Trial fails → breaker re-opens for another cooldown window.
        boolean[] ran = {false};
        assertThatThrownBy(() -> breaker.call(() -> { ran[0] = true; throw down(); }))
                .isInstanceOf(ValidationServiceUnavailableException.class);
        assertThat(ran[0]).as("the trial call actually ran").isTrue();
        assertThat(breaker.isOpen()).as("failed trial re-opens the breaker").isTrue();

        clock.advanceMillis(COOLDOWN_MS - 1);
        assertThat(breaker.isOpen()).as("the new cooldown window counts from the failed trial").isTrue();
    }

    @Test
    void halfOpenAdmitsExactlyOneTrialUnderConcurrency() throws InterruptedException {
        // When many threads cross the cooldown boundary at once, exactly one runs the trial
        // action and the rest fast-fail. The clock stands still, so the trial window cannot
        // expire while the threads race; the trial holds until every other caller has failed.
        ValidationCircuitBreaker breaker = breaker(1);
        assertThatThrownBy(() -> breaker.call(() -> { throw down(); }))
                .isInstanceOf(ValidationServiceUnavailableException.class);
        assertThat(breaker.isOpen()).isTrue();

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

            assertThat(ready.await(5, TimeUnit.SECONDS)).as("all threads started").isTrue();
            go.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).as("all threads finished").isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(actionRuns.get()).as("exactly one thread runs the half-open trial").isEqualTo(1);
        assertThat(fastFails.get())
                .as("trial failed → every caller (trial + losers) sees unavailable")
                .isEqualTo(threads);
        assertThat(breaker.isOpen()).as("failed trial keeps the breaker open").isTrue();
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
        assertThatThrownBy(() -> breaker.call(() -> { throw down(); }))
                .isInstanceOf(ValidationServiceUnavailableException.class);
        clock.advanceMillis(COOLDOWN_MS + 1);

        boolean[] winnerRan = {false};
        onNextRead[0] = () -> assertThatThrownBy(() -> breaker.call(() -> { winnerRan[0] = true; throw down(); }))
                .isInstanceOf(ValidationServiceUnavailableException.class);
        boolean[] loserRan = {false};
        ValidationServiceUnavailableException lost = assertThatExceptionOfType(ValidationServiceUnavailableException.class)
                .isThrownBy(() -> breaker.call(() -> { loserRan[0] = true; return "x"; }))
                .actual();

        assertThat(winnerRan[0]).as("the winning caller ran the trial").isTrue();
        assertThat(loserRan[0]).as("the losing caller must not run a second trial").isFalse();
        assertThat(lost).as(lost.getMessage()).hasMessageContaining("trial in progress");
    }

    @Test
    void rejectionStyleExceptionsDoNotTrip() {
        ValidationCircuitBreaker breaker = breaker(1);
        // A non-unavailable exception propagates and does NOT count toward opening.
        assertThatThrownBy(() -> breaker.call(() -> { throw new IllegalStateException("4xx"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    void publicConstructorUsesTheSystemClock() {
        ValidationCircuitBreaker breaker = new ValidationCircuitBreaker("v", 1, COOLDOWN_MS);
        assertThatThrownBy(() -> breaker.call(() -> { throw down(); }))
                .isInstanceOf(ValidationServiceUnavailableException.class);
        assertThat(breaker.isOpen()).as("a 30 s cooldown has not elapsed on the real clock").isTrue();
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
