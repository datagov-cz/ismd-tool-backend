package com.dia.ismdtoolbackend.utility.sparql;

import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Lightweight per-endpoint circuit breaker for SPARQL calls.
 *
 * <p>Three states implicit in two fields ({@code consecutiveFailures}, {@code openedAt}):
 * <ul>
 *   <li><strong>Closed</strong> — calls flow through; failures increment a counter.</li>
 *   <li><strong>Open</strong> — after {@link #failureThreshold} consecutive failures,
 *       calls fail fast for {@link #cooldownMillis} so a thundering herd doesn't slam
 *       an already-dead endpoint.</li>
 *   <li><strong>Half-open</strong> (implicit) — once cooldown expires, one trial call
 *       passes through. Success closes the breaker; failure re-opens it for another
 *       cooldown window.</li>
 * </ul>
 *
 * <p>Only {@link SparqlEndpointUnavailableException} counts as a failure — domain
 * exceptions (e.g. invalid IRI) propagate untouched and don't trip the breaker.
 *
 * <p>Stateful, thread-safe, plain Java. Construct one per endpoint.
 */
@Slf4j
public final class SparqlCircuitBreaker {

    private final String endpointLabel;
    private final int failureThreshold;
    private final long cooldownMillis;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong openedAt = new AtomicLong(0);

    public SparqlCircuitBreaker(String endpointLabel, int failureThreshold, long cooldownMillis) {
        this.endpointLabel = endpointLabel;
        this.failureThreshold = failureThreshold;
        this.cooldownMillis = cooldownMillis;
    }

    /**
     * Execute {@code action}, with circuit-breaker fast-fail when the endpoint
     * has been failing recently. Only {@link SparqlEndpointUnavailableException}
     * counts toward the failure threshold.
     */
    public <T> T call(Supplier<T> action) {
        long openSince = openedAt.get();
        if (openSince > 0) {
            long elapsed = System.currentTimeMillis() - openSince;
            if (elapsed < cooldownMillis) {
                throw new SparqlEndpointUnavailableException(endpointLabel,
                        endpointLabel + " circuit breaker open (last failure " + elapsed + "ms ago)");
            }
            // cooldown expired — let one call through as a trial
        }
        try {
            T result = action.get();
            onSuccess();
            return result;
        } catch (SparqlEndpointUnavailableException e) {
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
        long now = System.currentTimeMillis();
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
        return System.currentTimeMillis() - openSince < cooldownMillis;
    }
}
