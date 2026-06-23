package com.dia.ismdtoolbackend.reconciler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the PG↔TDB2 reconciler on a cron schedule. Gated by {@code reconciler.enabled}
 * (default off), so deploying this code does not start scanning until an environment opts in.
 *
 * <p>The {@code @Scheduled} cron is resolved from {@code reconciler.cron} at startup. The
 * in-process re-entrancy guard lives in {@link ConsistencyReconciler}; this component only
 * decides whether a scheduled tick should run at all.
 *
 * <p>Single-instance assumption: with multiple replicas every instance would fire this cron.
 * A distributed lock (ShedLock) is required before enabling scheduled runs in a multi-instance
 * deployment — see the reconciler plan §7. Not added in the detection-only phase.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ReconciliationScheduler {

    private final ReconcilerConfig config;
    private final ReconcilerRunService runService;

    @Scheduled(cron = "${reconciler.cron:0 0 3 * * *}")
    public void runScheduled() {
        if (!config.isEnabled()) {
            log.debug("Scheduled reconcile tick skipped — reconciler.enabled=false");
            return;
        }
        log.info("Scheduled reconcile starting");
        try {
            ReconciliationReport report = runService.run("SCHEDULED");
            if (report == null) {
                log.info("Scheduled reconcile skipped — a run was already in progress");
            }
        } catch (Exception e) {
            // A failed scheduled run must not kill the scheduler thread.
            log.error("Scheduled reconcile failed", e);
        }
    }
}