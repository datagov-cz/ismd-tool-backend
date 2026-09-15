package com.dia.ismdtoolbackend.service.nkod;

import com.dia.ismdtoolbackend.client.NkodSparqlClient;
import com.dia.ismdtoolbackend.config.NkodConfig;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.models.nkod.NkodDatasetRow;
import com.dia.ismdtoolbackend.models.nkod.NkodDatasetSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the harvested NKOD catalogue in memory, so listing and search are served locally.
 *
 * <p>Follows {@code RppSnapshotHolder}: warm off the request path once ready, serve stale on
 * refresh failure, and refresh ahead of TTL expiry.
 *
 * <p>Each instance harvests independently, so instances can briefly disagree after an
 * upstream change. That is acceptable for a browse list.
 */
@Component
@Slf4j
public class NkodDatasetSnapshotHolder {

    /** Language the snapshot is sorted by; list responses may request any language for display. */
    private static final String SORT_LANG = "cs";

    private final NkodSparqlClient client;
    private final Clock clock;
    private final Duration ttl;

    private final AtomicReference<NkodDatasetSnapshot> current =
            new AtomicReference<>(NkodDatasetSnapshot.empty());
    private final Object refreshLock = new Object();

    public NkodDatasetSnapshotHolder(NkodSparqlClient client, Clock clock, NkodConfig config) {
        this.client = client;
        this.clock = clock;
        this.ttl = Duration.ofHours(config.getSnapshot().getTtlHours());
    }

    /** Fresh snapshot, rebuilding synchronously if the current one is stale or empty. */
    public NkodDatasetSnapshot get() {
        NkodDatasetSnapshot snap = current.get();
        if (isFresh(snap)) {
            return snap;
        }
        return refreshUnderLock();
    }

    /** Current snapshot without triggering a rebuild — for diagnostics. */
    public NkodDatasetSnapshot peek() {
        return current.get();
    }

    /**
     * Harvests off the request path once the app is ready, so the first user does not pay for
     * it. {@link ApplicationReadyEvent} listeners run synchronously, so {@code @Async} keeps
     * the harvest off the boot thread and readiness is not delayed. A failure is swallowed:
     * {@link #get()} and the scheduled refresh retry later.
     */
    @Async("snapshotExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void warmOnStartup() {
        if (!client.isEndpointConfigured()) {
            log.info("NKOD endpoint not configured; skipping catalogue warm-up.");
            return;
        }
        try {
            log.info("NKOD catalogue warm-up starting");
            NkodDatasetSnapshot snap = forceRefresh();
            log.info("NKOD catalogue warm-up complete: datasets={}", snap.size());
        } catch (Exception e) {
            log.warn("NKOD catalogue warm-up failed; will build lazily on first request. cause={}",
                    e.getMessage());
        }
    }

    /**
     * Rebuilds ahead of TTL expiry so no user hits a cold harvest. Unlike {@link #get()} this
     * rebuilds regardless of freshness — refreshing <em>before</em> staleness is the point.
     */
    @Scheduled(cron = "${nkod.snapshot.refresh-cron:0 0 */6 * * *}")
    public void scheduledRefresh() {
        if (!client.isEndpointConfigured()) {
            return;
        }
        try {
            forceRefresh();
        } catch (Exception e) {
            log.warn("Scheduled NKOD refresh failed; keeping existing snapshot. cause={}", e.getMessage());
        }
    }

    private NkodDatasetSnapshot forceRefresh() {
        synchronized (refreshLock) {
            return attemptRefresh(current.get());
        }
    }

    private NkodDatasetSnapshot refreshUnderLock() {
        synchronized (refreshLock) {
            NkodDatasetSnapshot snap = current.get();
            if (isFresh(snap)) {
                return snap;
            }
            return attemptRefresh(snap);
        }
    }

    /**
     * Serves the existing snapshot when a refresh fails, so a flaky endpoint never evicts good
     * data. Only an empty snapshot lets the failure propagate — there is nothing to serve.
     */
    private NkodDatasetSnapshot attemptRefresh(NkodDatasetSnapshot existing) {
        try {
            NkodDatasetSnapshot fresh = buildFresh();
            current.set(fresh);
            return fresh;
        } catch (SparqlEndpointUnavailableException e) {
            if (!existing.isEmpty()) {
                log.warn("NKOD refresh failed; serving stale snapshot loadedAt={}. cause={}",
                        existing.getLoadedAt(), e.getMessage());
                return existing;
            }
            throw e;
        }
    }

    private boolean isFresh(NkodDatasetSnapshot snap) {
        return !snap.isEmpty() && !snap.isStale(ttl, clock);
    }

    private NkodDatasetSnapshot buildFresh() {
        long t0 = System.currentTimeMillis();
        List<NkodDatasetRow> harvested = client.harvestDatasets();
        NkodDatasetSnapshot snapshot = NkodDatasetSnapshot.build(clock.instant(), harvested, SORT_LANG);
        log.info("[timing] NKOD catalogue harvest: {} datasets in {} ms",
                snapshot.size(), System.currentTimeMillis() - t0);
        return snapshot;
    }
}