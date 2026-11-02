package com.dia.ismdtoolbackend.client.validation;

import com.dia.ismdtoolbackend.exception.ValidationServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Per-service circuit breaker for validator calls - kept a separate type so the validator path trips only on
 * {@link ValidationServiceUnavailableException}.
 *
 * <p>Three states implicit in two fields ({@code consecutiveFailures}, {@code openedAt}):
 * <ul>
 *   <li><strong>Closed</strong> — calls flow through; failures increment a counter.</li>
 *   <li><strong>Open</strong> — after {@link #failureThreshold} consecutive failures, calls
 *       fail fast for {@link #cooldownMillis}.</li>
 *   <li><strong>Half-open</strong> (implicit) — once cooldown expires, one trial call passes
 *       through. Success closes the breaker; failure re-opens it for another cooldown window.</li>
 * </ul>
 *
 * <p>Only {@link ValidationServiceUnavailableException} counts as a failure — a validator 4xx
 * rejection ({@code OntologyValidationException}) propagates untouched and does not trip the
 * breaker.
 *
 * <p>Stateful, thread-safe.
 */
@Slf4j
public final class ValidationCircuitBreaker {

    private final String endpointLabel;
    private final int failureThreshold;
    private final long cooldownMillis;
    private final Clock clock;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong openedAt = new AtomicLong(0);

    public ValidationCircuitBreaker(String endpointLabel, int failureThreshold, long cooldownMillis) {
        this(endpointLabel, failureThreshold, cooldownMillis, Clock.systemUTC());
    }

    /** {@code clock} must never read epoch millisecond 0, which marks the breaker closed. */
    ValidationCircuitBreaker(String endpointLabel, int failureThreshold, long cooldownMillis, Clock clock) {
        this.endpointLabel = endpointLabel;
        this.failureThreshold = failureThreshold;
        this.cooldownMillis = cooldownMillis;
        this.clock = clock;
    }

    /**
     * Execute {@code action}, with circuit-breaker fast-fail when the validator has been failing
     * recently. Only {@link ValidationServiceUnavailableException} counts toward the threshold.
     */
    public <T> T call(Supplier<T> action) {
        long openSince = openedAt.get();
        if (openSince > 0) {
            long elapsed = clock.millis() - openSince;
            if (elapsed < cooldownMillis) {
                throw new ValidationServiceUnavailableException(endpointLabel,
                        endpointLabel + " circuit breaker open (last failure " + elapsed + "ms ago)");
            }
            // Cooldown expired → half-open. Elect exactly ONE trial caller: the thread that
            // wins this CAS advances openedAt to now (so any concurrent threads see a fresh
            // open window and fast-fail above), then runs the single trial. Losers fall through
            // to the fast-fail on their next read. Without this, every thread that crossed the
            // cooldown boundary would hit a possibly-still-dead validator at once.
            if (!openedAt.compareAndSet(openSince, clock.millis())) {
                throw new ValidationServiceUnavailableException(endpointLabel,
                        endpointLabel + " circuit breaker open (trial in progress)");
            }
        }
        try {
            T result = action.get();
            onSuccess();
            return result;
        } catch (ValidationServiceUnavailableException e) {
            onFailure();
            throw e;
        }
    }

    private void onSuccess() {
        if (openedAt.getAndSet(0) > 0) {
            log.info("{} circuit breaker closed after successful call.", endpointLabel);
        }
        consecutiveFailures.set(0);
    }

    private void onFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures < failureThreshold) {
            return;
        }
        long now = clock.millis();
        long prev = openedAt.getAndSet(now);
        if (prev == 0) {
            log.warn("{} circuit breaker opened after {} consecutive failures (cooldown {}ms).",
                    endpointLabel, failures, cooldownMillis);
        } else {
            log.warn("{} circuit breaker stays open (trial call failed; cooldown extended by {}ms).",
                    endpointLabel, cooldownMillis);
        }
    }

    // Visible for testing
    boolean isOpen() {
        long openSince = openedAt.get();
        if (openSince == 0) return false;
        return clock.millis() - openSince < cooldownMillis;
    }
}
