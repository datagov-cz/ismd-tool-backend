package com.dia.ismdtoolbackend.client.esbirka;

import java.time.Duration;

/**
 * Time budget of one {@code /api/eli} request. Rate-gate waits, HTTP calls and retries all draw
 * from it; it is passed explicitly because page fetches of one request run on several threads.
 */
final class EsbirkaDeadline {

    private final long expiresAtNanos;

    private EsbirkaDeadline(long expiresAtNanos) {
        this.expiresAtNanos = expiresAtNanos;
    }

    static EsbirkaDeadline after(Duration budget) {
        return new EsbirkaDeadline(System.nanoTime() + budget.toNanos());
    }

    long remainingNanos() {
        return expiresAtNanos - System.nanoTime();
    }

    Duration remaining() {
        return Duration.ofNanos(Math.max(0, remainingNanos()));
    }

    boolean isExpired() {
        return remainingNanos() <= 0;
    }

    /** Whether {@code nanoTime} lies past the deadline. */
    boolean isAfter(long nanoTime) {
        return nanoTime - expiresAtNanos > 0;
    }
}
