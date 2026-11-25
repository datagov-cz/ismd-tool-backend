package com.dia.ismdtoolbackend.client.esbirka;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Process-wide limiter for e-Sbírka REST calls: at most {@code maxInFlight} calls at once, and each
 * departure at least {@code minSpacingNanos} after the previous one (a token bucket of capacity 1 —
 * the upstream edge allows no burst). The backend runs as a single instance, so in-process state
 * covers all of its traffic.
 */
final class EsbirkaRateGate {

    /** Thrown when the deadline expires before a slot is free. */
    static final class DeadlineExceeded extends Exception {
        DeadlineExceeded(String message) {
            super(message, null, false, false);
        }
    }

    private final long minSpacingNanos;
    private final Duration maxWait;
    private final Semaphore inFlight;
    /** Fair, so a waiting call is not overtaken indefinitely by later ones. */
    private final ReentrantLock departureLock = new ReentrantLock(true);
    private long lastDepartureNanos;
    private boolean departedBefore;

    EsbirkaRateGate(long minSpacingMillis, int maxInFlight) {
        this(minSpacingMillis, maxInFlight, Duration.ofSeconds(30));
    }

    /** {@code maxWait} caps the wait for a slot, however much of the request deadline is left. */
    EsbirkaRateGate(long minSpacingMillis, int maxInFlight, Duration maxWait) {
        this.minSpacingNanos = TimeUnit.MILLISECONDS.toNanos(minSpacingMillis);
        this.maxWait = maxWait;
        this.inFlight = new Semaphore(maxInFlight, true);
    }

    /**
     * Blocks until this call may depart; the caller must {@link #release()} once the call has
     * completed.
     *
     * @return the departure time ({@link System#nanoTime()})
     */
    long acquire(EsbirkaDeadline requestDeadline) throws DeadlineExceeded, InterruptedException {
        EsbirkaDeadline deadline = requestDeadline.atMost(maxWait);
        if (!inFlight.tryAcquire(deadline.remainingNanos(), TimeUnit.NANOSECONDS)) {
            throw new DeadlineExceeded("deadline expired waiting for an in-flight slot");
        }
        try {
            return awaitDepartureSlot(deadline);
        } catch (DeadlineExceeded | InterruptedException | RuntimeException e) {
            inFlight.release();
            throw e;
        }
    }

    void release() {
        inFlight.release();
    }

    private long awaitDepartureSlot(EsbirkaDeadline deadline) throws DeadlineExceeded, InterruptedException {
        if (!departureLock.tryLock(deadline.remainingNanos(), TimeUnit.NANOSECONDS)) {
            throw new DeadlineExceeded("deadline expired waiting for a departure slot");
        }
        try {
            long now = System.nanoTime();
            if (departedBefore) {
                long slot = lastDepartureNanos + minSpacingNanos;
                if (deadline.isAfter(slot)) {
                    throw new DeadlineExceeded("deadline expires before the next departure slot");
                }
                while (now - slot < 0) {
                    TimeUnit.NANOSECONDS.sleep(slot - now);
                    now = System.nanoTime();
                }
            }
            lastDepartureNanos = now;
            departedBefore = true;
            return now;
        } finally {
            departureLock.unlock();
        }
    }
}
