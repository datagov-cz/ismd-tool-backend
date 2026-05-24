package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves a batch of concept IRIs to {ontologyIri, ontologyDescription, source}.
 * The FE calls this once per concept-detail view, after the detail response
 * arrives, to enrich plain IRIs in {@code broaderClasses}, {@code exactMatches},
 * etc. into rich navigation metadata.
 *
 * <p>Resolution strategy is cache-first, then batched:
 * <ol>
 *   <li>Validate and dedupe inputs (silently dropping unsafe IRIs — the request
 *       is best-effort, one malformed IRI shouldn't 400 the whole batch).</li>
 *   <li>Split into hits (served from Caffeine) and misses.</li>
 *   <li>Misses are resolved against ISMD in <strong>one</strong> CONSTRUCT.</li>
 *   <li>IRIs ISMD doesn't know about fall through to NKD in <strong>one</strong>
 *       CONSTRUCT.</li>
 * </ol>
 *
 * <p>This shape replaces a naive per-IRI parallel design that would serialize on
 * the 10-permit Fuseki semaphore and the 4-permit NKD pool — cutting cold worst
 * case from multi-second to a single ISMD batch + a single NKD HTTP round-trip.
 *
 * <p>{@code @Cacheable} isn't used here because per-IRI cache semantics would
 * fight the batched-query design; instead we drive the {@code Cache} directly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConceptMetadataResolver {

    public static final String CACHE_NAME = "conceptMetadataResolution";

    private final JenaTDB2Repository jenaTDB2Repository;
    private final NkdSparqlClient nkdSparqlClient;
    private final CacheManager cacheManager;

    public Map<String, ResolvedConceptDto> resolveAll(List<String> iris) {
        if (iris == null || iris.isEmpty()) {
            return Map.of();
        }
        List<String> clean = iris.stream()
                .filter(Objects::nonNull)
                .distinct()
                .filter(SparqlIriValidator::isSafeHttpIri)
                .toList();
        if (clean.isEmpty()) {
            return Map.of();
        }

        Cache cache = cacheManager.getCache(CACHE_NAME);
        Map<String, ResolvedConceptDto> out = new HashMap<>();
        List<String> misses = new ArrayList<>();
        for (String iri : clean) {
            ResolvedConceptDto hit = (cache == null) ? null : cache.get(iri, ResolvedConceptDto.class);
            if (hit != null) {
                out.put(iri, hit);
            } else {
                misses.add(iri);
            }
        }
        if (misses.isEmpty()) {
            return out;
        }

        Map<String, ResolvedConceptDto> ismdHits = jenaTDB2Repository.fetchConceptResolutions(misses);
        ismdHits.forEach((iri, dto) -> {
            if (cache != null) cache.put(iri, dto);
            out.put(iri, dto);
        });

        List<String> remaining = misses.stream()
                .filter(iri -> !ismdHits.containsKey(iri))
                .toList();
        if (!remaining.isEmpty()) {
            Map<String, ResolvedConceptDto> nkdHits = nkdSparqlClient.fetchConceptResolutions(remaining);
            nkdHits.forEach((iri, dto) -> {
                if (cache != null) cache.put(iri, dto);
                out.put(iri, dto);
            });
        }

        return out;
    }
}
