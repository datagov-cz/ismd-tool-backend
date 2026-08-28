package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the orchestration logic in {@link ReferencedConceptResolutionEngine}:
 * cache-first split, ISMD/NKD batching, lenient NKD vs. strict ISMD error
 * propagation, slug enrichment scope, and input sanitisation.
 */
@ExtendWith(MockitoExtension.class)
class ReferencedConceptResolutionEngineTest {

    private static final String ISMD_IRI_1 = "https://data.gov.cz/zdroj/slovnik/local/pojem/a";
    private static final String ISMD_IRI_2 = "https://data.gov.cz/zdroj/slovnik/local/pojem/b";
    private static final String NKD_IRI = "https://slovník.gov.cz/datový/sportovní/pojem/sport";
    private static final String UNSAFE_IRI = "not-an-iri";

    @Mock private JenaTDB2Repository jenaTDB2Repository;
    @Mock private NkdSparqlClient nkdSparqlClient;
    @Mock private ConceptMetadataRepository conceptMetadataRepository;
    @Mock private CacheManager cacheManager;
    @Mock private Cache cache;

    @InjectMocks
    private ReferencedConceptResolutionEngine resolver;

    @BeforeEach
    void wireCache() {
        // lenient: not every test needs the cache to be queried (e.g. empty-input early returns)
        lenient().when(cacheManager.getCache(ReferencedConceptResolutionEngine.CACHE_NAME)).thenReturn(cache);
    }

    private static ResolvedConceptDto ismdDto(String iri) {
        return ResolvedConceptDto.builder()
                .iri(iri)
                .conceptName(Map.of("cs", "Pojem"))
                .ontologyIri("https://data.gov.cz/zdroj/slovnik/local")
                .ontologyName(Map.of("cs", "Lokální slovník"))
                .source(SearchSource.ISMD)
                .build();
    }

    private static ResolvedConceptDto nkdDto(String iri) {
        return ResolvedConceptDto.builder()
                .iri(iri)
                .conceptName(Map.of("cs", "NKD pojem"))
                .ontologyIri("https://slovník.gov.cz/datový/sportovní")
                .ontologyName(Map.of("cs", "Sportovní slovník"))
                .source(SearchSource.NKD)
                .build();
    }

    private static ConceptMetadataEntity entity(String iri, String slug) {
        ConceptMetadataEntity e = new ConceptMetadataEntity();
        e.setConceptIri(iri);
        e.setSlug(slug);
        return e;
    }

    /** ISMD relationship DTO carrying iri-only domain/range stubs, as projected from the graph. */
    private static ResolvedConceptDto relationshipWithStubs(String iri, String domainIri, String rangeIri) {
        return ResolvedConceptDto.builder()
                .iri(iri)
                .conceptName(Map.of("cs", "Vztah"))
                .ontologyIri("https://data.gov.cz/zdroj/slovnik/local")
                .ontologyName(Map.of("cs", "Lokální slovník"))
                .source(SearchSource.ISMD)
                .resolvedDomain(domainIri == null ? null : ResolvedConceptDto.builder().iri(domainIri).build())
                .resolvedRange(rangeIri == null ? null : ResolvedConceptDto.builder().iri(rangeIri).build())
                .build();
    }

    @Nested
    @DisplayName("Input sanitisation")
    class InputSanitisation {

        @Test
        @DisplayName("null input → empty map, no downstream calls")
        void nullInput() {
            assertThat(resolver.resolveAll(null)).isEmpty();
            verifyNoInteractions(jenaTDB2Repository, nkdSparqlClient, conceptMetadataRepository, cacheManager);
        }

        @Test
        @DisplayName("empty list → empty map, no downstream calls")
        void emptyInput() {
            assertThat(resolver.resolveAll(List.of())).isEmpty();
            verifyNoInteractions(jenaTDB2Repository, nkdSparqlClient, conceptMetadataRepository, cacheManager);
        }

        @Test
        @DisplayName("all-invalid input → empty map, no SPARQL invoked")
        void allUnsafe() {
            assertThat(resolver.resolveAll(List.of(UNSAFE_IRI, "also bad"))).isEmpty();
            verifyNoInteractions(jenaTDB2Repository, nkdSparqlClient, conceptMetadataRepository);
        }

        @Test
        @DisplayName("invalid IRIs are dropped silently, valid ones still resolve")
        void mixedInputDropsUnsafe() {
            when(cache.get(eq(ISMD_IRI_1), eq(ResolvedConceptDto.class))).thenReturn(null);
            when(jenaTDB2Repository.fetchConceptResolutions(List.of(ISMD_IRI_1)))
                    .thenReturn(Map.of(ISMD_IRI_1, ismdDto(ISMD_IRI_1)));
            when(conceptMetadataRepository.findByConceptIriIn(anyList())).thenReturn(List.of());

            Map<String, ResolvedConceptDto> out = resolver.resolveAll(List.of(UNSAFE_IRI, ISMD_IRI_1));

            assertThat(out).containsOnlyKeys(ISMD_IRI_1);
            verify(jenaTDB2Repository).fetchConceptResolutions(List.of(ISMD_IRI_1));
        }

        @Test
        @DisplayName("duplicates are deduplicated before SPARQL")
        void duplicatesDeduplicated() {
            when(cache.get(any(String.class), eq(ResolvedConceptDto.class))).thenReturn(null);
            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            when(jenaTDB2Repository.fetchConceptResolutions(captor.capture())).thenReturn(Map.of());
            when(nkdSparqlClient.fetchConceptResolutions(anyList())).thenReturn(Map.of());

            resolver.resolveAll(List.of(ISMD_IRI_1, ISMD_IRI_1, ISMD_IRI_1));

            assertThat(captor.getValue()).containsExactly(ISMD_IRI_1);
        }
    }

    @Nested
    @DisplayName("Cache split")
    class CacheSplit {

        @Test
        @DisplayName("all cache hits → no SPARQL invoked")
        void allCacheHits() {
            ResolvedConceptDto cachedA = ismdDto(ISMD_IRI_1);
            ResolvedConceptDto cachedB = ismdDto(ISMD_IRI_2);
            when(cache.get(ISMD_IRI_1, ResolvedConceptDto.class)).thenReturn(cachedA);
            when(cache.get(ISMD_IRI_2, ResolvedConceptDto.class)).thenReturn(cachedB);

            Map<String, ResolvedConceptDto> out = resolver.resolveAll(List.of(ISMD_IRI_1, ISMD_IRI_2));

            assertThat(out).containsOnly(
                    Map.entry(ISMD_IRI_1, cachedA),
                    Map.entry(ISMD_IRI_2, cachedB));
            verifyNoInteractions(jenaTDB2Repository, nkdSparqlClient, conceptMetadataRepository);
        }

        @Test
        @DisplayName("partial cache → only misses go to ISMD; hits not re-cached")
        void partialCache() {
            ResolvedConceptDto cachedA = ismdDto(ISMD_IRI_1);
            when(cache.get(ISMD_IRI_1, ResolvedConceptDto.class)).thenReturn(cachedA);
            when(cache.get(ISMD_IRI_2, ResolvedConceptDto.class)).thenReturn(null);
            when(jenaTDB2Repository.fetchConceptResolutions(List.of(ISMD_IRI_2)))
                    .thenReturn(Map.of(ISMD_IRI_2, ismdDto(ISMD_IRI_2)));
            when(conceptMetadataRepository.findByConceptIriIn(anyList())).thenReturn(List.of());

            Map<String, ResolvedConceptDto> out = resolver.resolveAll(List.of(ISMD_IRI_1, ISMD_IRI_2));

            assertThat(out).containsOnlyKeys(ISMD_IRI_1, ISMD_IRI_2);
            verify(jenaTDB2Repository).fetchConceptResolutions(List.of(ISMD_IRI_2));
            verify(cache, never()).put(eq(ISMD_IRI_1), any());
            verify(cache, times(1)).put(eq(ISMD_IRI_2), any());
        }

        @Test
        @DisplayName("null CacheManager.getCache → still resolves (no NPE)")
        void cacheManagerReturnsNull() {
            when(cacheManager.getCache(ReferencedConceptResolutionEngine.CACHE_NAME)).thenReturn(null);
            when(jenaTDB2Repository.fetchConceptResolutions(List.of(ISMD_IRI_1)))
                    .thenReturn(Map.of(ISMD_IRI_1, ismdDto(ISMD_IRI_1)));
            when(conceptMetadataRepository.findByConceptIriIn(anyList())).thenReturn(List.of());

            Map<String, ResolvedConceptDto> out = resolver.resolveAll(List.of(ISMD_IRI_1));

            assertThat(out).containsKey(ISMD_IRI_1);
        }
    }

    @Nested
    @DisplayName("ISMD/NKD fallback")
    class IsmdNkdFallback {

        @Test
        @DisplayName("ISMD hits keep NKD untouched")
        void ismdHitsSkipNkd() {
            when(cache.get(any(String.class), eq(ResolvedConceptDto.class))).thenReturn(null);
            when(jenaTDB2Repository.fetchConceptResolutions(List.of(ISMD_IRI_1)))
                    .thenReturn(Map.of(ISMD_IRI_1, ismdDto(ISMD_IRI_1)));
            when(conceptMetadataRepository.findByConceptIriIn(anyList())).thenReturn(List.of());

            resolver.resolveAll(List.of(ISMD_IRI_1));

            verify(nkdSparqlClient, never()).fetchConceptResolutions(anyList());
        }

        @Test
        @DisplayName("ISMD miss falls through to NKD with only the unresolved IRIs")
        void ismdMissFallsToNkd() {
            when(cache.get(any(String.class), eq(ResolvedConceptDto.class))).thenReturn(null);
            when(jenaTDB2Repository.fetchConceptResolutions(List.of(ISMD_IRI_1, NKD_IRI)))
                    .thenReturn(Map.of(ISMD_IRI_1, ismdDto(ISMD_IRI_1)));
            when(conceptMetadataRepository.findByConceptIriIn(anyList())).thenReturn(List.of());
            when(nkdSparqlClient.fetchConceptResolutions(List.of(NKD_IRI)))
                    .thenReturn(Map.of(NKD_IRI, nkdDto(NKD_IRI)));

            Map<String, ResolvedConceptDto> out = resolver.resolveAll(List.of(ISMD_IRI_1, NKD_IRI));

            assertThat(out).containsOnlyKeys(ISMD_IRI_1, NKD_IRI);
            assertThat(out.get(ISMD_IRI_1).source()).isEqualTo(SearchSource.ISMD);
            assertThat(out.get(NKD_IRI).source()).isEqualTo(SearchSource.NKD);
            verify(nkdSparqlClient).fetchConceptResolutions(List.of(NKD_IRI));
        }

        @Test
        @DisplayName("NKD result is cached so a follow-up call can serve from cache")
        void nkdHitIsCached() {
            when(cache.get(NKD_IRI, ResolvedConceptDto.class)).thenReturn(null);
            when(jenaTDB2Repository.fetchConceptResolutions(List.of(NKD_IRI))).thenReturn(Map.of());
            when(nkdSparqlClient.fetchConceptResolutions(List.of(NKD_IRI)))
                    .thenReturn(Map.of(NKD_IRI, nkdDto(NKD_IRI)));

            resolver.resolveAll(List.of(NKD_IRI));

            verify(cache).put(eq(NKD_IRI), any(ResolvedConceptDto.class));
        }
    }

    @Nested
    @DisplayName("Error propagation")
    class ErrorPropagation {

        @Test
        @DisplayName("ISMD throws SparqlEndpointUnavailableException → propagates (controller maps to 503)")
        void ismdThrowsPropagates() {
            when(cache.get(any(String.class), eq(ResolvedConceptDto.class))).thenReturn(null);
            when(jenaTDB2Repository.fetchConceptResolutions(anyList()))
                    .thenThrow(new SparqlEndpointUnavailableException("ISMD", "boom"));

            assertThatThrownBy(() -> resolver.resolveAll(List.of(ISMD_IRI_1)))
                    .isInstanceOf(SparqlEndpointUnavailableException.class)
                    .hasMessage("boom");
        }

        @Test
        @DisplayName("NKD client returns empty (lenient outage) → ISMD hits still returned")
        void nkdLenientOutage() {
            when(cache.get(any(String.class), eq(ResolvedConceptDto.class))).thenReturn(null);
            when(jenaTDB2Repository.fetchConceptResolutions(List.of(ISMD_IRI_1, NKD_IRI)))
                    .thenReturn(Map.of(ISMD_IRI_1, ismdDto(ISMD_IRI_1)));
            when(conceptMetadataRepository.findByConceptIriIn(anyList())).thenReturn(List.of());
            when(nkdSparqlClient.fetchConceptResolutions(List.of(NKD_IRI))).thenReturn(Map.of());

            Map<String, ResolvedConceptDto> out = resolver.resolveAll(List.of(ISMD_IRI_1, NKD_IRI));

            assertThat(out).containsOnlyKeys(ISMD_IRI_1);
        }
    }

    @Nested
    @DisplayName("Relationship domain/range expansion")
    class RelationshipDomainRange {

        private static final String REL_IRI = "https://data.gov.cz/zdroj/slovnik/local/pojem/rel";
        private static final String DOMAIN_IRI = "https://data.gov.cz/zdroj/slovnik/local/pojem/trida-a";
        private static final String RANGE_IRI = "https://data.gov.cz/zdroj/slovnik/local/pojem/trida-b";

        @Test
        @DisplayName("relationship stubs are expanded into fully-resolved domain/range DTOs")
        void stubsExpanded() {
            // First hop: the relationship itself (carries stubs). Second hop: its domain+range targets.
            when(cache.get(any(String.class), eq(ResolvedConceptDto.class))).thenReturn(null);
            when(jenaTDB2Repository.fetchConceptResolutions(List.of(REL_IRI)))
                    .thenReturn(new HashMap<>(Map.of(REL_IRI, relationshipWithStubs(REL_IRI, DOMAIN_IRI, RANGE_IRI))));
            when(jenaTDB2Repository.fetchConceptResolutions(List.of(DOMAIN_IRI, RANGE_IRI)))
                    .thenReturn(new HashMap<>(Map.of(
                            DOMAIN_IRI, ismdDto(DOMAIN_IRI),
                            RANGE_IRI, ismdDto(RANGE_IRI))));
            when(conceptMetadataRepository.findByConceptIriIn(anyList())).thenReturn(List.of());

            Map<String, ResolvedConceptDto> out = resolver.resolveAll(List.of(REL_IRI));

            ResolvedConceptDto rel = out.get(REL_IRI);
            assertThat(rel.resolvedDomain()).isNotNull();
            assertThat(rel.resolvedDomain().iri()).isEqualTo(DOMAIN_IRI);
            assertThat(rel.resolvedDomain().conceptName()).isNotNull();
            assertThat(rel.resolvedDomain().source()).isEqualTo(SearchSource.ISMD);
            assertThat(rel.resolvedRange()).isNotNull();
            assertThat(rel.resolvedRange().iri()).isEqualTo(RANGE_IRI);
        }

        @Test
        @DisplayName("slugged relationship keeps domain/range — slug enrichment must not strip stubs before expansion")
        void sluggedRelationshipKeepsDomainRange() {
            // Production case: the relationship has a Postgres slug row, so enrichWithSlugs
            // rebuilds its DTO. That rebuild must carry the domain/range stubs forward, or
            // resolveDomainRangeStubs finds nothing to expand and the FE sees no targets.
            when(cache.get(any(String.class), eq(ResolvedConceptDto.class))).thenReturn(null);
            when(jenaTDB2Repository.fetchConceptResolutions(List.of(REL_IRI)))
                    .thenReturn(new HashMap<>(Map.of(REL_IRI, relationshipWithStubs(REL_IRI, DOMAIN_IRI, RANGE_IRI))));
            when(jenaTDB2Repository.fetchConceptResolutions(List.of(DOMAIN_IRI, RANGE_IRI)))
                    .thenReturn(new HashMap<>(Map.of(
                            DOMAIN_IRI, ismdDto(DOMAIN_IRI),
                            RANGE_IRI, ismdDto(RANGE_IRI))));
            // The relationship itself has a slug; its domain/range targets resolve without one.
            when(conceptMetadataRepository.findByConceptIriIn(anyList()))
                    .thenReturn(List.of(entity(REL_IRI, "rel-slug")));

            Map<String, ResolvedConceptDto> out = resolver.resolveAll(List.of(REL_IRI));

            ResolvedConceptDto rel = out.get(REL_IRI);
            assertThat(rel.conceptSlug()).isEqualTo("rel-slug");
            assertThat(rel.resolvedDomain()).as("domain must survive slug enrichment").isNotNull();
            assertThat(rel.resolvedDomain().iri()).isEqualTo(DOMAIN_IRI);
            assertThat(rel.resolvedDomain().conceptName()).isNotNull();
            assertThat(rel.resolvedRange()).as("range must survive slug enrichment").isNotNull();
            assertThat(rel.resolvedRange().iri()).isEqualTo(RANGE_IRI);
        }

        @Test
        @DisplayName("relationship with an unresolvable range → range dropped to null, domain kept")
        void unresolvableRangeDropped() {
            when(cache.get(any(String.class), eq(ResolvedConceptDto.class))).thenReturn(null);
            when(jenaTDB2Repository.fetchConceptResolutions(List.of(REL_IRI)))
                    .thenReturn(new HashMap<>(Map.of(REL_IRI, relationshipWithStubs(REL_IRI, DOMAIN_IRI, RANGE_IRI))));
            // Second hop: only the domain resolves; range is unknown to both ISMD and NKD.
            when(jenaTDB2Repository.fetchConceptResolutions(List.of(DOMAIN_IRI, RANGE_IRI)))
                    .thenReturn(new HashMap<>(Map.of(DOMAIN_IRI, ismdDto(DOMAIN_IRI))));
            when(nkdSparqlClient.fetchConceptResolutions(List.of(RANGE_IRI))).thenReturn(Map.of());
            when(conceptMetadataRepository.findByConceptIriIn(anyList())).thenReturn(List.of());

            Map<String, ResolvedConceptDto> out = resolver.resolveAll(List.of(REL_IRI));

            ResolvedConceptDto rel = out.get(REL_IRI);
            assertThat(rel.resolvedDomain()).isNotNull();
            assertThat(rel.resolvedDomain().iri()).isEqualTo(DOMAIN_IRI);
            assertThat(rel.resolvedRange()).isNull();
        }

        @Test
        @DisplayName("the cached relationship DTO is fully resolved, not a stub")
        void cachesFinishedDto() {
            when(cache.get(any(String.class), eq(ResolvedConceptDto.class))).thenReturn(null);
            when(jenaTDB2Repository.fetchConceptResolutions(List.of(REL_IRI)))
                    .thenReturn(new HashMap<>(Map.of(REL_IRI, relationshipWithStubs(REL_IRI, DOMAIN_IRI, null))));
            when(jenaTDB2Repository.fetchConceptResolutions(List.of(DOMAIN_IRI)))
                    .thenReturn(new HashMap<>(Map.of(DOMAIN_IRI, ismdDto(DOMAIN_IRI))));
            when(conceptMetadataRepository.findByConceptIriIn(anyList())).thenReturn(List.of());

            resolver.resolveAll(List.of(REL_IRI));

            ArgumentCaptor<ResolvedConceptDto> relCache = ArgumentCaptor.forClass(ResolvedConceptDto.class);
            verify(cache).put(eq(REL_IRI), relCache.capture());
            assertThat(relCache.getValue().resolvedDomain().conceptName())
                    .as("cached relationship must hold a resolved domain, not an iri-only stub")
                    .isNotNull();
        }

        @Test
        @DisplayName("stub targets already in the batch are served from it — no second round-trip")
        void stubsAlreadyInBatchNeedNoSecondQuery() {
            // The bulk case: domain/range targets are classes of the same vocabulary, so they are
            // normally already among the resolved rows. Re-querying them is a wasted round-trip —
            // measured at ~55ms on a 381-concept vocabulary.
            when(cache.get(any(String.class), eq(ResolvedConceptDto.class))).thenReturn(null);
            when(jenaTDB2Repository.fetchConceptResolutions(List.of(REL_IRI, DOMAIN_IRI, RANGE_IRI)))
                    .thenReturn(new HashMap<>(Map.of(
                            REL_IRI, relationshipWithStubs(REL_IRI, DOMAIN_IRI, RANGE_IRI),
                            DOMAIN_IRI, ismdDto(DOMAIN_IRI),
                            RANGE_IRI, ismdDto(RANGE_IRI))));
            when(conceptMetadataRepository.findByConceptIriIn(anyList())).thenReturn(List.of());

            Map<String, ResolvedConceptDto> out =
                    resolver.resolveAll(List.of(REL_IRI, DOMAIN_IRI, RANGE_IRI));

            ResolvedConceptDto rel = out.get(REL_IRI);
            assertThat(rel.resolvedDomain()).isNotNull();
            assertThat(rel.resolvedDomain().conceptName()).isNotNull();
            assertThat(rel.resolvedRange()).isNotNull();
            assertThat(rel.resolvedRange().conceptName()).isNotNull();
            // Exactly one Fuseki call: the stub expansion was satisfied from rows already fetched.
            verify(jenaTDB2Repository, times(1)).fetchConceptResolutions(anyList());
            verify(nkdSparqlClient, never()).fetchConceptResolutions(anyList());
        }

        @Test
        @DisplayName("a stub target NOT in the batch still gets its own lookup")
        void stubNotInBatchStillQueried() {
            // Only the genuine remainder may go to the network — the short-circuit must not swallow
            // targets the batch never resolved.
            when(cache.get(any(String.class), eq(ResolvedConceptDto.class))).thenReturn(null);
            when(jenaTDB2Repository.fetchConceptResolutions(List.of(REL_IRI, DOMAIN_IRI)))
                    .thenReturn(new HashMap<>(Map.of(
                            REL_IRI, relationshipWithStubs(REL_IRI, DOMAIN_IRI, RANGE_IRI),
                            DOMAIN_IRI, ismdDto(DOMAIN_IRI))));
            when(jenaTDB2Repository.fetchConceptResolutions(List.of(RANGE_IRI)))
                    .thenReturn(new HashMap<>(Map.of(RANGE_IRI, ismdDto(RANGE_IRI))));
            when(conceptMetadataRepository.findByConceptIriIn(anyList())).thenReturn(List.of());

            Map<String, ResolvedConceptDto> out = resolver.resolveAll(List.of(REL_IRI, DOMAIN_IRI));

            ResolvedConceptDto rel = out.get(REL_IRI);
            assertThat(rel.resolvedDomain().conceptName()).as("served from the batch").isNotNull();
            assertThat(rel.resolvedRange().conceptName()).as("fetched by the follow-up").isNotNull();
            // The follow-up asked for the missing target ONLY, not for the one already in hand.
            verify(jenaTDB2Repository).fetchConceptResolutions(List.of(RANGE_IRI));
        }
    }

    @Nested
    @DisplayName("NKD-only gate (source=NKD)")
    class NkdOnlyGate {

        // Same IRI as ISMD_IRI_1 but requested in NKD context — this is the
        // doubly-present case: an IRI that lives in both stores. Under the gate it
        // must resolve NKD-only and never touch ISMD.
        private static final String NKD_ONLY_CACHE_KEY = "nkd-only::" + ISMD_IRI_1;

        @Test
        @DisplayName("source=NKD skips ISMD entirely and resolves against NKD only")
        void nkdOnlySkipsIsmd() {
            when(cache.get(NKD_ONLY_CACHE_KEY, ResolvedConceptDto.class)).thenReturn(null);
            when(nkdSparqlClient.fetchConceptResolutions(List.of(ISMD_IRI_1)))
                    .thenReturn(Map.of(ISMD_IRI_1, nkdDto(ISMD_IRI_1)));

            Map<String, ResolvedConceptDto> out = resolver.resolveAll(List.of(ISMD_IRI_1), SearchSource.NKD);

            assertThat(out).containsOnlyKeys(ISMD_IRI_1);
            assertThat(out.get(ISMD_IRI_1).source()).isEqualTo(SearchSource.NKD);
            verify(nkdSparqlClient).fetchConceptResolutions(List.of(ISMD_IRI_1));
            // The whole point of the gate: ISMD (and its slug repo) are never consulted.
            verifyNoInteractions(jenaTDB2Repository, conceptMetadataRepository);
        }

        @Test
        @DisplayName("source=NKD caches under a namespaced key so it can't clobber the ISMD-first entry")
        void nkdOnlyUsesSeparateCacheKey() {
            when(cache.get(NKD_ONLY_CACHE_KEY, ResolvedConceptDto.class)).thenReturn(null);
            when(nkdSparqlClient.fetchConceptResolutions(List.of(ISMD_IRI_1)))
                    .thenReturn(Map.of(ISMD_IRI_1, nkdDto(ISMD_IRI_1)));

            resolver.resolveAll(List.of(ISMD_IRI_1), SearchSource.NKD);

            verify(cache).put(eq(NKD_ONLY_CACHE_KEY), any(ResolvedConceptDto.class));
            // Must NOT write the bare-IRI key that the default (ISMD-first) mode owns.
            verify(cache, never()).put(eq(ISMD_IRI_1), any());
        }

        @Test
        @DisplayName("source=NKD reads from the namespaced cache key, not the bare IRI")
        void nkdOnlyReadsSeparateCacheKey() {
            ResolvedConceptDto cachedNkd = nkdDto(ISMD_IRI_1);
            when(cache.get(NKD_ONLY_CACHE_KEY, ResolvedConceptDto.class)).thenReturn(cachedNkd);

            Map<String, ResolvedConceptDto> out = resolver.resolveAll(List.of(ISMD_IRI_1), SearchSource.NKD);

            assertThat(out).containsEntry(ISMD_IRI_1, cachedNkd);
            verifyNoInteractions(jenaTDB2Repository, nkdSparqlClient, conceptMetadataRepository);
        }

        @Test
        @DisplayName("source=NKD expands relationship domain/range against NKD too")
        void nkdOnlyExpandsStubsViaNkd() {
            String relIri = "https://slovník.gov.cz/datový/sportovní/pojem/rel";
            String domainIri = "https://slovník.gov.cz/datový/sportovní/pojem/trida-a";
            when(cache.get(any(String.class), eq(ResolvedConceptDto.class))).thenReturn(null);
            when(nkdSparqlClient.fetchConceptResolutions(List.of(relIri)))
                    .thenReturn(new HashMap<>(Map.of(relIri, relationshipWithStubs(relIri, domainIri, null))));
            when(nkdSparqlClient.fetchConceptResolutions(List.of(domainIri)))
                    .thenReturn(new HashMap<>(Map.of(domainIri, nkdDto(domainIri))));

            Map<String, ResolvedConceptDto> out = resolver.resolveAll(List.of(relIri), SearchSource.NKD);

            ResolvedConceptDto rel = out.get(relIri);
            assertThat(rel.resolvedDomain()).isNotNull();
            assertThat(rel.resolvedDomain().iri()).isEqualTo(domainIri);
            assertThat(rel.resolvedDomain().conceptName()).isNotNull();
            // The stub second hop stayed on NKD — ISMD was never consulted.
            verifyNoInteractions(jenaTDB2Repository, conceptMetadataRepository);
        }

        @Test
        @DisplayName("source=ISMD behaves like the default (ISMD-first) path")
        void explicitIsmdSourceIsDefaultPath() {
            when(cache.get(ISMD_IRI_1, ResolvedConceptDto.class)).thenReturn(null);
            when(jenaTDB2Repository.fetchConceptResolutions(List.of(ISMD_IRI_1)))
                    .thenReturn(new HashMap<>(Map.of(ISMD_IRI_1, ismdDto(ISMD_IRI_1))));
            when(conceptMetadataRepository.findByConceptIriIn(anyList())).thenReturn(List.of());

            Map<String, ResolvedConceptDto> out = resolver.resolveAll(List.of(ISMD_IRI_1), SearchSource.ISMD);

            assertThat(out.get(ISMD_IRI_1).source()).isEqualTo(SearchSource.ISMD);
            verify(jenaTDB2Repository).fetchConceptResolutions(List.of(ISMD_IRI_1));
            verify(cache).put(eq(ISMD_IRI_1), any());
        }
    }

    @Nested
    @DisplayName("Slug enrichment")
    class SlugEnrichment {

        @Test
        @DisplayName("ISMD hit gets slug from Postgres lookup")
        void ismdHitGetsSlug() {
            when(cache.get(ISMD_IRI_1, ResolvedConceptDto.class)).thenReturn(null);
            when(jenaTDB2Repository.fetchConceptResolutions(List.of(ISMD_IRI_1)))
                    .thenReturn(new HashMap<>(Map.of(ISMD_IRI_1, ismdDto(ISMD_IRI_1))));
            when(conceptMetadataRepository.findByConceptIriIn(List.of(ISMD_IRI_1)))
                    .thenReturn(List.of(entity(ISMD_IRI_1, "pojem-a")));

            Map<String, ResolvedConceptDto> out = resolver.resolveAll(List.of(ISMD_IRI_1));

            assertThat(out.get(ISMD_IRI_1).conceptSlug()).isEqualTo("pojem-a");
        }

        @Test
        @DisplayName("ISMD hit with no Postgres row keeps null slug (concept exists in graph but not metadata)")
        void ismdHitMissingFromPostgres() {
            when(cache.get(ISMD_IRI_1, ResolvedConceptDto.class)).thenReturn(null);
            when(jenaTDB2Repository.fetchConceptResolutions(List.of(ISMD_IRI_1)))
                    .thenReturn(new HashMap<>(Map.of(ISMD_IRI_1, ismdDto(ISMD_IRI_1))));
            when(conceptMetadataRepository.findByConceptIriIn(List.of(ISMD_IRI_1))).thenReturn(List.of());

            Map<String, ResolvedConceptDto> out = resolver.resolveAll(List.of(ISMD_IRI_1));

            assertThat(out.get(ISMD_IRI_1).conceptSlug()).isNull();
        }

        @Test
        @DisplayName("NKD-resolved IRIs never trigger the slug repo lookup")
        void nkdResolvedSkipsRepo() {
            when(cache.get(NKD_IRI, ResolvedConceptDto.class)).thenReturn(null);
            when(jenaTDB2Repository.fetchConceptResolutions(List.of(NKD_IRI))).thenReturn(Map.of());
            when(nkdSparqlClient.fetchConceptResolutions(List.of(NKD_IRI)))
                    .thenReturn(Map.of(NKD_IRI, nkdDto(NKD_IRI)));

            Map<String, ResolvedConceptDto> out = resolver.resolveAll(List.of(NKD_IRI));

            assertThat(out.get(NKD_IRI).conceptSlug()).isNull();
            verify(conceptMetadataRepository, never()).findByConceptIriIn(anyList());
        }

        @Test
        @DisplayName("Postgres row with null slug is ignored (no overwrite to null)")
        void nullSlugIgnored() {
            when(cache.get(ISMD_IRI_1, ResolvedConceptDto.class)).thenReturn(null);
            when(jenaTDB2Repository.fetchConceptResolutions(List.of(ISMD_IRI_1)))
                    .thenReturn(new HashMap<>(Map.of(ISMD_IRI_1, ismdDto(ISMD_IRI_1))));
            when(conceptMetadataRepository.findByConceptIriIn(List.of(ISMD_IRI_1)))
                    .thenReturn(List.of(entity(ISMD_IRI_1, null)));

            Map<String, ResolvedConceptDto> out = resolver.resolveAll(List.of(ISMD_IRI_1));

            assertThat(out.get(ISMD_IRI_1).conceptSlug()).isNull();
        }
    }
}
