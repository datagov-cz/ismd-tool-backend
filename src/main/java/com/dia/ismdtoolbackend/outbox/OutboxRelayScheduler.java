package com.dia.ismdtoolbackend.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Backstop drain for the outbox: fires on {@code outbox.relay-cron}, gated by
 * {@code outbox.enabled} (default off), so deploying this code does not start draining until an
 * environment opts in. The hot path is the {@link OutboxRelayTrigger} after-commit nudge; this
 * scheduled tick exists to catch rows whose nudge never completed (process died, relay busy) and to
 * retry transiently-failed rows.
 *
 * <p>One tick drains the BACKLOG, not a single batch: it loops {@link OutboxRelay#drainOnce()} until
 * a pass applies nothing, so a built-up queue clears in one tick. A safety cap bounds the loop so a
 * pass that keeps claiming rows it cannot advance (all gated/failing) can never spin forever.
 *
 * <p>Single-instance note: with multiple replicas every instance fires this cron, but the relay's
 * {@code FOR UPDATE SKIP LOCKED} claim makes concurrent drains safe (unlike the reconciler, which
 * needs ShedLock) — so no distributed lock is required here.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OutboxRelayScheduler {

    /** Hard cap on drain passes per tick — far above any real backlog; a spin-guard, not a tuning knob. */
    private static final int MAX_PASSES_PER_TICK = 1000;

    private final OutboxConfig config;
    private final OutboxRelay relay;

    @Scheduled(cron = "${outbox.relay-cron:*/10 * * * * *}")
    public void drainScheduled() {
        if (!config.isEnabled()) {
            log.debug("Scheduled outbox drain skipped — outbox.enabled=false");
            return;
        }
        try {
            int totalApplied = 0;
            int passes = 0;
            while (passes++ < MAX_PASSES_PER_TICK) {
                int applied = relay.drainOnce();
                totalApplied += applied;
                if (applied == 0) {
                    break; // nothing more to do this tick (backlog drained or only gated/failing rows remain)
                }
            }
            if (totalApplied > 0) {
                log.info("Scheduled outbox drain applied {} row(s) over {} pass(es)", totalApplied, passes);
            }
        } catch (Exception e) {
            // A failed scheduled drain must not kill the scheduler thread.
            log.error("Scheduled outbox drain failed", e);
        }
    }
}
