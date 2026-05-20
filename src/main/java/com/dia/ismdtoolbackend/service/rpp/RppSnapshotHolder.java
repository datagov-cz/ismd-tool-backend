package com.dia.ismdtoolbackend.service.rpp;

import com.dia.ismdtoolbackend.client.RppSparqlClient;
import com.dia.ismdtoolbackend.config.RppConfig;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.models.rpp.RppAgenda;
import com.dia.ismdtoolbackend.models.rpp.RppCodeComparator;
import com.dia.ismdtoolbackend.models.rpp.RppIsvs;
import com.dia.ismdtoolbackend.models.rpp.RppSnapshot;
import lombok.extern.slf4j.Slf4j;
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
