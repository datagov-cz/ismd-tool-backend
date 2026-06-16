package com.dia.ismdtoolbackend.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Registers an after-commit nudge that drains the outbox as soon as the enqueuing business
 * transaction commits, keeping the common edit-then-refetch path read-your-write fast (the relay's
 * scheduled tick is then only the crash backstop).
 *
 * <p><b>Why after-commit, not in the method body:</b> the nudge must run AFTER the Postgres COMMIT
 * so the outbox row is durably visible to the relay's claim query. Registering via
 * {@link TransactionSynchronizationManager} with {@link TransactionSynchronization#afterCommit()}
 * guarantees that ordering; a plain end-of-method call would race the commit.
 *
 * <p><b>Concurrency is bounded by running the drain SYNCHRONOUSLY on the committing thread.</b> We
 * deliberately do NOT hand off to an async executor: the number of concurrent drains can never
 * exceed the number of in-flight business requests, whereas an async pool would let nudges pile up.
 * Synchronous == naturally single-flight-per-request and keeps read-your-write (the drain finishes
 * before the HTTP response).
 *
 * <p><b>Connection cost (operational note):</b> {@code afterCommit} fires BEFORE Spring returns the
 * business transaction's JDBC connection to the pool, and {@link OutboxRelay#drainOnce()} is
 * {@code REQUIRES_NEW} — so a nudging request transiently holds TWO pool connections (the
 * not-yet-released business one + the drain's new one) for the drain's duration, which spans the
 * relay's Fuseki I/O. The Hikari pool MUST therefore be sized with headroom for ~2× the peak
 * concurrent-writer count, or a write burst can exhaust the pool. (Bounded by in-flight request
 * count — not unbounded like an async queue would be.)
 *
 * <p>A drain failure here is swallowed (logged): the row stays PENDING and the scheduled backstop
 * retries, so a nudge failure must never fail the user's request whose data is already committed.
 * Consequence: read-your-write is best-effort — if the nudge drain fails or the row is gated behind
 * an earlier unapplied same-aggregate row, the write still returns 200 but TDB2 catches up later.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OutboxRelayTrigger {

    private final OutboxRelay relay;

    /**
     * Schedules a drain to run once the current transaction commits. No-op if there is no active
     * transaction synchronization (e.g. called outside a tx) — the scheduled backstop covers that.
     */
    public void nudgeAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.debug("Outbox nudge requested with no active tx synchronization; relying on the scheduled drain");
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    relay.drainOnce();
                } catch (Exception e) {
                    // The user's data is already committed; a nudge failure must not surface to them.
                    // The row stays PENDING and the scheduled backstop retries.
                    log.warn("Outbox after-commit nudge drain failed; scheduled backstop will retry", e);
                }
            }
        });
    }
}
