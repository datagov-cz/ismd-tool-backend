package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.SearchSource;
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
 * Engine layer of the referenced-concept resolution chain: resolves a batch of
 * concept IRIs to {conceptName, conceptSlug, ontologyIri, ontologyName, source}.
 *
 * <p>This is the reusable resolution engine. Its sole caller is the adapter layer,
 * {@link ReferencedConceptsEnricher}, which pulls the referenced-IRI fields
 * ({@code broaderClasses}, {@code exactMatches}, {@code domain}/{@code range}, …)
 * off a concept-detail model and hands them here as a flat IRI list. Keeping the
 * two layers separate lets this engine stay independent of the detail-model shape
 * and be exercised in isolation.
 *
 * <p>Resolution strategy is cache-first, then batched:
 * <ol>
 *   <li>Validate and dedupe inputs (silently dropping unsafe IRIs — resolution is
 *       best-effort, one malformed IRI shouldn't sink the whole batch).</li>
 *   <li>Split into hits (served from Caffeine) and misses.</li>
 *   <li>Misses are resolved against ISMD in <strong>one</strong> CONSTRUCT.</li>
 *   <li>IRIs ISMD doesn't know about fall through to NKD in <strong>one</strong>
 *       CONSTRUCT.</li>
 * </ol>
 *
 * <p><strong>NKD-only gate:</strong> the same IRI can exist in both ISMD and NKD
 * simultaneously; the default strategy above resolves such an IRI to ISMD (ISMD
 * wins ties, and NKD is never even queried for it). When the FE is viewing an NKD
 * resource detail it passes {@code source = NKD}, which skips ISMD entirely and
 * resolves the whole batch against NKD only — so a doubly-present IRI stays in its
 * NKD context. Because the two modes can attribute the <em>same</em> IRI to
 * different stores, NKD-only results are cached under a separate key namespace to
 * avoid clobbering the default (ISMD-first) cache entries and vice versa.
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
public class ReferencedConceptResolutionEngine {

    public static final String CACHE_NAME = "conceptMetadataResolution";

    private final JenaTDB2Repository jenaTDB2Repository;
    private final NkdSparqlClient nkdSparqlClient;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final CacheManager cacheManager;

    /**
     * Prefix applied to cache keys for NKD-only resolutions so they never collide
     * with the default (ISMD-first) entry for the same IRI. The two modes can
     * legitimately attribute one IRI to different stores, so they must not share a
     * cache slot. The prefix is not a valid IRI character sequence, so it can never
     * clash with a real (default-mode) IRI key.
     */
    private static final String NKD_ONLY_CACHE_PREFIX = "nkd-only::";

    public Map<String, ResolvedConceptDto> resolveAll(List<String> iris) {
        return resolveAll(iris, null);
    }

    /**
     * Resolves a batch of concept IRIs, gated by {@code source}.
     *
     * @param source when {@link SearchSource#NKD}, resolve against NKD only
     *               (see class Javadoc); any other value (including {@code null})
     *               keeps the default ISMD-first-then-NKD-fallback behaviour.
     */
    public Map<String, ResolvedConceptDto> resolveAll(List<String> iris, SearchSource source) {
        if (iris == null || iris.isEmpty()) {
            return Map.of();
        }
        boolean nkdOnly = source == SearchSource.NKD;
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
            ResolvedConceptDto hit = (cache == null) ? null : cache.get(cacheKey(iri, nkdOnly), ResolvedConceptDto.class);
            if (hit != null) {
                out.put(iri, hit);
            } else {
                misses.add(iri);
            }
        }
        if (misses.isEmpty()) {
            return out;
        }

        Map<String, ResolvedConceptDto> freshHits;
        if (nkdOnly) {
            // NKD-only gate: skip ISMD entirely so a doubly-present IRI stays in
            // its NKD context. IRIs NKD can't resolve are simply left unresolved.
            freshHits = new HashMap<>(nkdSparqlClient.fetchConceptResolutions(misses));
        } else {
            long tIsmd = System.currentTimeMillis();
            Map<String, ResolvedConceptDto> rawIsmdHits = jenaTDB2Repository.fetchConceptResolutions(misses);
            log.info("[timing] resolveAll ismd fetchConceptResolutions ({} misses) took {} ms",
                    misses.size(), System.currentTimeMillis() - tIsmd);
            long tSlug = System.currentTimeMillis();
            Map<String, ResolvedConceptDto> ismdHits = rawIsmdHits.isEmpty() ? rawIsmdHits : enrichWithSlugs(rawIsmdHits);
            log.info("[timing] resolveAll enrichWithSlugs ({} hits) took {} ms",
                    rawIsmdHits.size(), System.currentTimeMillis() - tSlug);

            freshHits = new HashMap<>(ismdHits);

            List<String> remaining = misses.stream()
                    .filter(iri -> !ismdHits.containsKey(iri))
                    .toList();
            if (!remaining.isEmpty()) {
                long tNkd = System.currentTimeMillis();
                freshHits.putAll(nkdSparqlClient.fetchConceptResolutions(remaining));
                log.info("[timing] resolveAll nkd fallback fetchConceptResolutions ({} iris) took {} ms",
                        remaining.size(), System.currentTimeMillis() - tNkd);
            }
        }

        // Expand relationship domain/range stubs into fully-resolved DTOs before
        // caching, so the cache and the response never hold a half-resolved stub.
        // Targets are classes (not relationships), so this recursion terminates.
        // The stub expansion inherits the same source gate so an NKD-only detail
        // view resolves its domain/range targets against NKD too.
        long tStubs = System.currentTimeMillis();
        Map<String, ResolvedConceptDto> finalHits = resolveDomainRangeStubs(freshHits, source);
        log.info("[timing] resolveAll resolveDomainRangeStubs took {} ms", System.currentTimeMillis() - tStubs);

        finalHits.forEach((iri, dto) -> {
            if (cache != null) cache.put(cacheKey(iri, nkdOnly), dto);
            out.put(iri, dto);
        });

        return out;
    }

    private static String cacheKey(String iri, boolean nkdOnly) {
        return nkdOnly ? NKD_ONLY_CACHE_PREFIX + iri : iri;
    }

    /**
     * Replaces the iri-only domain/range stubs carried by relationship DTOs with
     * fully-resolved {@link ResolvedConceptDto}s, then grafts them back. A stub whose
     * target can't be resolved is dropped to {@code null}.
     *
     * <p>Domain/range targets are classes of the same vocabulary, so in the bulk case they are
     * almost always already among {@code hits} — measured on a 381-concept vocabulary, 149 of 153
     * target IRIs were, the other 4 being {@code xsd:*}/{@code rdfs:Literal} datatypes that are not
     * concepts at all. Those are served from {@code hits} directly and only the genuine remainder
     * goes to {@link #resolveAll}, which usually removes the round-trip entirely.
     */
    private Map<String, ResolvedConceptDto> resolveDomainRangeStubs(Map<String, ResolvedConceptDto> hits, SearchSource source) {
        List<String> targetIris = new ArrayList<>();
        for (ResolvedConceptDto dto : hits.values()) {
            collectStubIri(dto.resolvedDomain(), targetIris);
            collectStubIri(dto.resolvedRange(), targetIris);
        }
        if (targetIris.isEmpty()) {
            return hits;
        }

        // Serve what this batch already resolved; only the rest needs a lookup. A hit is usable as a
        // target only once fully resolved — a stub carries just an iri.
        Map<String, ResolvedConceptDto> resolvedTargets = new HashMap<>();
        List<String> remaining = new ArrayList<>();
        for (String targetIri : targetIris.stream().distinct().toList()) {
            ResolvedConceptDto known = hits.get(targetIri);
            if (known != null && known.conceptName() != null) {
                resolvedTargets.put(targetIri, known);
            } else {
                remaining.add(targetIri);
            }
        }
        if (!remaining.isEmpty()) {
            resolvedTargets.putAll(resolveAll(remaining, source));
        }

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
                        // Preserve the iri-only domain/range stubs so the later
                        // resolveDomainRangeStubs pass can still expand them; the
                        // slug rebuild must not silently drop relationship targets.
                        .resolvedDomain(dto.resolvedDomain())
                        .resolvedRange(dto.resolvedRange())
                        .build());
            }
        });
        return enriched;
    }
}
