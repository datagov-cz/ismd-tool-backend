package com.dia.ismdtoolbackend.service.rpp;

import com.dia.ismdtoolbackend.client.RppSparqlClient;
import com.dia.ismdtoolbackend.config.RppConfig;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.models.rpp.RppAgenda;
import com.dia.ismdtoolbackend.models.rpp.RppCodeComparator;
import com.dia.ismdtoolbackend.models.rpp.RppIsvs;
import com.dia.ismdtoolbackend.models.rpp.RppSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Component
@Slf4j
public class RppSnapshotHolder {

    private final RppSparqlClient client;
    private final Clock clock;
    private final Duration ttl;

    private final AtomicReference<RppSnapshot> current = new AtomicReference<>(RppSnapshot.empty());
    private final Object refreshLock = new Object();

    public RppSnapshotHolder(RppSparqlClient client, Clock clock, RppConfig config) {
        this.client = client;
        this.clock = clock;
        this.ttl = Duration.ofHours(config.getCache().getTtlHours());
    }

    public RppSnapshot get() {
        RppSnapshot snap = current.get();
        if (isFresh(snap)) {
            return snap;
        }
        return refreshUnderLock();
    }

    /**
     * Warms the snapshot off the request path once the app is ready. Runs on the {@code snapshotExecutor}
     * pool — {@link ApplicationReadyEvent} listeners run synchronously, and {@code @Async} keeps this
     * ~15s build off the boot thread so readiness is not delayed. A failing/absent RPP endpoint is
     * swallowed here: the lazy {@link #get()} path (and the scheduled refresh) will retry on demand.
     */
    @Async("snapshotExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void warmOnStartup() {
        try {
            log.info("RPP snapshot warm-up starting");
            RppSnapshot snap = forceRefresh();
            log.info("RPP snapshot warm-up complete: agendas={} isvs={}",
                    snap.getAgendas().size(), snap.getIsvs().size());
        } catch (Exception e) {
            log.warn("RPP snapshot warm-up failed; will build lazily on first request. cause={}", e.getMessage());
        }
    }

    /**
     * Rebuilds the snapshot ahead of TTL expiry so no user hits the cold rebuild. Fires on
     * {@code rpp.cache.refresh-cron} (default every 12h, inside the 24h TTL). Unlike {@link #get()} this
     * rebuilds unconditionally regardless of freshness — the point is to refresh <em>before</em> the
     * snapshot goes stale. Serves-stale-on-failure via {@link #attemptRefresh} means a slow or down RPP
     * endpoint never evicts the good in-memory snapshot.
     */
    @Scheduled(cron = "${rpp.cache.refresh-cron:0 0 */12 * * *}")
    public void scheduledRefresh() {
        try {
            forceRefresh();
        } catch (Exception e) {
            log.warn("Scheduled RPP refresh failed; keeping existing snapshot. cause={}", e.getMessage());
        }
    }

    private RppSnapshot forceRefresh() {
        synchronized (refreshLock) {
            return attemptRefresh(current.get());
        }
    }

    public RppSnapshot peek() {
        return current.get();
    }

    public Optional<RppAgenda> findAgendaByIri(String iri) {
        if (iri == null || iri.isBlank()) {
            return Optional.empty();
        }
        return snapshotForLookup().getAgendas().stream()
                .filter(a -> iri.equals(a.getIri()))
                .findFirst();
    }

    public Optional<RppIsvs> findIsvsByIri(String iri) {
        if (iri == null || iri.isBlank()) {
            return Optional.empty();
        }
        return snapshotForLookup().getIsvs().stream()
                .filter(i -> iri.equals(i.getIri()))
                .findFirst();
    }

    private RppSnapshot snapshotForLookup() {
        try {
            return get();
        } catch (SparqlEndpointUnavailableException e) {
            log.debug("RPP snapshot unavailable for lookup; treating as miss. cause={}", e.getMessage());
            return RppSnapshot.empty();
        }
    }

    private RppSnapshot refreshUnderLock() {
        synchronized (refreshLock) {
            RppSnapshot snap = current.get();
            if (isFresh(snap)) {
                return snap;
            }
            return attemptRefresh(snap);
        }
    }

    private RppSnapshot attemptRefresh(RppSnapshot existing) {
        try {
            RppSnapshot fresh = buildFresh();
            current.set(fresh);
            return fresh;
        } catch (SparqlEndpointUnavailableException e) {
            if (!existing.isEmpty()) {
                log.warn("RPP refresh failed; serving stale snapshot loadedAt={}. cause={}",
                        existing.getLoadedAt(), e.getMessage());
                return existing;
            }
            throw e;
        }
    }

    private boolean isFresh(RppSnapshot snap) {
        return !snap.isEmpty() && !snap.isStale(ttl, clock);
    }

    private RppSnapshot buildFresh() {
        List<RppAgenda> agendas = new ArrayList<>(client.fetchAllAgendas());
        List<RppIsvs> isvs = new ArrayList<>(client.fetchAllIsvs());
        agendas.sort(Comparator.comparing(RppAgenda::getCode, RppCodeComparator.INSTANCE));
        isvs.sort(Comparator.comparing(RppIsvs::getCode, RppCodeComparator.INSTANCE));

        Map<String, List<String>> byIsvs = isvs.stream()
                .filter(i -> i.getIri() != null)
                .collect(Collectors.toUnmodifiableMap(
                        RppIsvs::getIri,
                        i -> List.copyOf(i.getAgendaIris()),
                        (a, b) -> a));

        return new RppSnapshot(clock.instant(), List.copyOf(agendas), List.copyOf(isvs), byIsvs);
    }
}
