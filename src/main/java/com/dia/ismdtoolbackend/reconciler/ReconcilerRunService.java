package com.dia.ismdtoolbackend.reconciler;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Thin facade that runs the {@link ConsistencyReconciler} and caches the most recent report
 * so both the scheduled job and the admin endpoint share one entry point and one "last
 * result" view.
 *
 * <p>Detection-only: the cached report is in-memory and lost on restart. Plan §9 calls for
 * persisting a {@code reconciler_run} row per run once the repair phase adds its PG tables;
 * at that point this cache becomes a convenience over the durable record.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReconcilerRunService {

    private final ConsistencyReconciler reconciler;

    /**
     * -- GETTER --
     * The most recent completed report, or null if none has run since startup.
     */
    @Getter
    private volatile ReconciliationReport lastReport;

    /**
     * Runs a detection pass. Returns null if a run is already in progress (the caller decides
     * how to surface that — the scheduler logs and skips, the controller returns 409).
     */
    public ReconciliationReport run(String triggeredBy) {
        ReconciliationReport report = reconciler.reconcile(triggeredBy);
        if (report != null) {
            lastReport = report;
        }
        return report;
    }
}