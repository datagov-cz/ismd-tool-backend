package com.dia.ismdtoolbackend.client.validation;

import com.dia.ismdtoolbackend.exception.ValidationServiceUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ValidationCircuitBreakerTest {

    private ValidationServiceUnavailableException down() {
        return new ValidationServiceUnavailableException("Validační služba", "down");
    }

    @Test
    void startsClosed() {
        ValidationCircuitBreaker breaker = new ValidationCircuitBreaker("v", 2, 30_000);
        assertFalse(breaker.isOpen());
        assertEquals("ok", breaker.call(() -> "ok"));
        assertFalse(breaker.isOpen());
    }

    @Test
    void opensAfterThreshold() {
        ValidationCircuitBreaker breaker = new ValidationCircuitBreaker("v", 2, 30_000);

        assertThrows(ValidationServiceUnavailableException.class, () -> breaker.call(() -> { throw down(); }));
        assertFalse(breaker.isOpen(), "one failure below threshold — still closed");

        assertThrows(ValidationServiceUnavailableException.class, () -> breaker.call(() -> { throw down(); }));
        assertTrue(breaker.isOpen(), "second failure hits threshold — now open");
    }

    @Test
    void openBreakerFastFailsWithoutCallingAction() {
        ValidationCircuitBreaker breaker = new ValidationCircuitBreaker("v", 1, 30_000);
        assertThrows(ValidationServiceUnavailableException.class, () -> breaker.call(() -> { throw down(); }));
        assertTrue(breaker.isOpen());

        boolean[] ran = {false};
        assertThrows(ValidationServiceUnavailableException.class,
                () -> breaker.call(() -> { ran[0] = true; return "x"; }));
        assertFalse(ran[0], "open breaker must not invoke the action");
    }

    @Test
    void halfOpenTrialClosesOnSuccess() throws InterruptedException {
        // Small but non-zero cooldown so the breaker is genuinely OPEN, then expires into a trial.
        ValidationCircuitBreaker breaker = new ValidationCircuitBreaker("v", 1, 50);
        assertThrows(ValidationServiceUnavailableException.class, () -> breaker.call(() -> { throw down(); }));
        assertTrue(breaker.isOpen(), "breaker is open during cooldown");

        // While open, a call fast-fails without running the action.
        boolean[] ran = {false};
        assertThrows(ValidationServiceUnavailableException.class,
                () -> breaker.call(() -> { ran[0] = true; return "x"; }));
        assertFalse(ran[0], "open breaker fast-fails");

        Thread.sleep(70); // past cooldown → next call is the half-open trial
        assertEquals("ok", breaker.call(() -> "ok"), "trial call runs and succeeds");
        assertFalse(breaker.isOpen(), "successful trial closes the breaker");
    }

    @Test
    void halfOpenTrialFailureReopens() throws InterruptedException {
        ValidationCircuitBreaker breaker = new ValidationCircuitBreaker("v", 1, 50);
        assertThrows(ValidationServiceUnavailableException.class, () -> breaker.call(() -> { throw down(); }));
        assertTrue(breaker.isOpen());

        Thread.sleep(70); // past cooldown → trial allowed
        // Trial fails → breaker re-opens for another cooldown window.
        boolean[] ran = {false};
        assertThrows(ValidationServiceUnavailableException.class,
                () -> breaker.call(() -> { ran[0] = true; throw down(); }));
        assertTrue(ran[0], "the trial call actually ran");
        assertTrue(breaker.isOpen(), "failed trial re-opens the breaker");
    }

    @Test
    void halfOpenAdmitsExactlyOneTrialUnderConcurrency() throws InterruptedException {
        // The crux of the half-open contract: when many threads cross the cooldown boundary at
        // once, EXACTLY ONE runs the trial action; the rest must fast-fail. A naive read-only gate
        // would let all of them stampede the (possibly still-dead) validator.
        ValidationCircuitBreaker breaker = new ValidationCircuitBreaker("v", 1, 50);
        assertThrows(ValidationServiceUnavailableException.class, () -> breaker.call(() -> { throw down(); }));
        assertTrue(breaker.isOpen());

        Thread.sleep(70); // cooldown expired → first caller would be the trial

        int threads = 16;
        AtomicInteger actionRuns = new AtomicInteger(0);
        AtomicInteger fastFails = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    // The single elected trial blocks briefly so the others are guaranteed to
                    // observe the in-progress trial window and fast-fail, not slip through.
                    breaker.call(() -> {
                        actionRuns.incrementAndGet();
                        try { Thread.sleep(30); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                        throw down();
                    });
                } catch (ValidationServiceUnavailableException e) {
                    fastFails.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(2, TimeUnit.SECONDS);
        go.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS), "all threads finished");
        pool.shutdownNow();

        assertEquals(1, actionRuns.get(), "exactly one thread runs the half-open trial");
        assertEquals(threads, fastFails.get(), "trial failed → every caller (trial + losers) sees unavailable");
        assertTrue(breaker.isOpen(), "failed trial keeps the breaker open");
    }

    @Test
    void rejectionStyleExceptionsDoNotTrip() {
        ValidationCircuitBreaker breaker = new ValidationCircuitBreaker("v", 1, 30_000);
        // A non-unavailable exception propagates and does NOT count toward opening.
        assertThrows(IllegalStateException.class, () -> breaker.call(() -> { throw new IllegalStateException("4xx"); }));
        assertFalse(breaker.isOpen());
    }
}
