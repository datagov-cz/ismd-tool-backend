package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
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
 * Resolves a batch of concept IRIs to {conceptName, conceptSlug, ontologyIri, ontologyName, source}.
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
    private final ConceptMetadataRepository conceptMetadataRepository;
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

        Map<String, ResolvedConceptDto> rawIsmdHits = jenaTDB2Repository.fetchConceptResolutions(misses);
        Map<String, ResolvedConceptDto> ismdHits = rawIsmdHits.isEmpty() ? rawIsmdHits : enrichWithSlugs(rawIsmdHits);

        Map<String, ResolvedConceptDto> freshHits = new HashMap<>(ismdHits);

        List<String> remaining = misses.stream()
                .filter(iri -> !ismdHits.containsKey(iri))
                .toList();
        if (!remaining.isEmpty()) {
            freshHits.putAll(nkdSparqlClient.fetchConceptResolutions(remaining));
        }

        // Expand relationship domain/range stubs into fully-resolved DTOs before
        // caching, so the cache and the response never hold a half-resolved stub.
        // Targets are classes (not relationships), so this recursion terminates.
        Map<String, ResolvedConceptDto> finalHits = resolveDomainRangeStubs(freshHits);

        finalHits.forEach((iri, dto) -> {
            if (cache != null) cache.put(iri, dto);
            out.put(iri, dto);
        });

        return out;
    }

    /**
     * Replaces the iri-only domain/range stubs carried by relationship DTOs with
     * fully-resolved {@link ResolvedConceptDto}s. The stub IRIs are resolved in a
     * single batched {@link #resolveAll} call (cache-backed), then grafted back.
     * A stub whose target can't be resolved is dropped to {@code null}.
     */
    private Map<String, ResolvedConceptDto> resolveDomainRangeStubs(Map<String, ResolvedConceptDto> hits) {
        List<String> targetIris = new ArrayList<>();
        for (ResolvedConceptDto dto : hits.values()) {
            collectStubIri(dto.resolvedDomain(), targetIris);
            collectStubIri(dto.resolvedRange(), targetIris);
        }
        if (targetIris.isEmpty()) {
            return hits;
        }

        Map<String, ResolvedConceptDto> resolvedTargets = resolveAll(targetIris);

        Map<String, ResolvedConceptDto> out = new HashMap<>(hits.size());
        hits.forEach((iri, dto) -> {
            if (dto.resolvedDomain() == null && dto.resolvedRange() == null) {
                out.put(iri, dto);
                return;
            }
            out.put(iri, ResolvedConceptDto.builder()
                    .iri(dto.iri())
                    .conceptName(dto.conceptName())
                    .conceptSlug(dto.conceptSlug())
                    .ontologyIri(dto.ontologyIri())
                    .ontologyName(dto.ontologyName())
                    .source(dto.source())
                    .resolvedDomain(expandStub(dto.resolvedDomain(), resolvedTargets))
                    .resolvedRange(expandStub(dto.resolvedRange(), resolvedTargets))
                    .build());
        });
        return out;
    }

    private static void collectStubIri(ResolvedConceptDto stub, List<String> sink) {
        if (stub != null && stub.iri() != null) {
            sink.add(stub.iri());
        }
    }

    private static ResolvedConceptDto expandStub(ResolvedConceptDto stub, Map<String, ResolvedConceptDto> resolved) {
        if (stub == null || stub.iri() == null) {
            return null;
        }
        return resolved.get(stub.iri());
    }

    private Map<String, ResolvedConceptDto> enrichWithSlugs(Map<String, ResolvedConceptDto> ismdHits) {
        List<String> iris = new ArrayList<>(ismdHits.keySet());
        Map<String, String> slugByIri = new HashMap<>();
        for (ConceptMetadataEntity entity : conceptMetadataRepository.findByConceptIriIn(iris)) {
            if (entity.getConceptIri() != null && entity.getSlug() != null) {
                slugByIri.putIfAbsent(entity.getConceptIri(), entity.getSlug());
            }
        }
        if (slugByIri.isEmpty()) {
            return ismdHits;
        }
        Map<String, ResolvedConceptDto> enriched = new HashMap<>(ismdHits.size());
        ismdHits.forEach((iri, dto) -> {
            String slug = slugByIri.get(iri);
            if (slug == null) {
                enriched.put(iri, dto);
            } else {
                enriched.put(iri, ResolvedConceptDto.builder()
                        .iri(dto.iri())
                        .conceptName(dto.conceptName())
                        .conceptSlug(slug)
                        .ontologyIri(dto.ontologyIri())
                        .ontologyName(dto.ontologyName())
                        .source(dto.source())
                        .build());
            }
        });
        return enriched;
    }
}
