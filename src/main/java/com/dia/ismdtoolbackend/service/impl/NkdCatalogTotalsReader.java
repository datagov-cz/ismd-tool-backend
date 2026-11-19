package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.query.NKDSPARQLBrowseQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Reads the catalogue-wide NKD totals, cached in-heap. */
@Component
@RequiredArgsConstructor
@Slf4j
class NkdCatalogTotalsReader {

    /**
     * TTL for the cached global counts (total ontologies, total concepts).
     * Catalog-level totals change rarely; 1h is a fine accuracy/latency trade.
     * In-heap, single-instance — to be revisited once a project-wide cache
     * abstraction lands (see feat/issue-101).
     */
    private static final Duration COUNT_TTL = Duration.ofHours(1);

    private final NkdSparqlClient nkdSparqlClient;

    private final AtomicReference<CachedValue<Integer>> cachedTotalOntologies = new AtomicReference<>();
    private final AtomicReference<CachedValue<Integer>> cachedTotalConcepts = new AtomicReference<>();

    int getCachedTotalOntologies() {
        return getCached(cachedTotalOntologies,
                () -> fetchSingleCount(NKDSPARQLBrowseQuery.buildCountOntologiesQuery()));
    }

    int getCachedTotalConcepts() {
        return getCached(cachedTotalConcepts,
                () -> fetchSingleCount(NKDSPARQLBrowseQuery.buildCountConceptsQuery()));
    }

    private int getCached(AtomicReference<CachedValue<Integer>> cache, Supplier<Integer> loader) {
        CachedValue<Integer> current = cache.get();
        Instant now = Instant.now();
        if (current != null && current.expiresAt.isAfter(now)) {
            return current.value;
        }
        try {
            int fresh = loader.get();
            cache.set(new CachedValue<>(fresh, now.plus(COUNT_TTL)));
            return fresh;
        } catch (RuntimeException e) {
            // Serve stale on remote failure — better a slightly old number than
            // a 500 on the catalog page.
            if (current != null) {
                log.warn("NKD count refresh failed, serving stale value: {}", e.getMessage());
                return current.value;
            }
            log.warn("NKD count fetch failed and no stale value available: {}", e.getMessage());
            return 0;
        }
    }

    private int fetchSingleCount(String query) {
        List<Map<String, String>> rows = nkdSparqlClient.executeSelect(query);
        if (rows.isEmpty()) return 0;
        String value = rows.get(0).get("total");
        if (value == null) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("Unparseable COUNT result from NKD: {}", value);
            return 0;
        }
    }

    private record CachedValue<T>(T value, Instant expiresAt) {}
}
