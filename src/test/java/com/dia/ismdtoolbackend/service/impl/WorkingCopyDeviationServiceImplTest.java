package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.enums.SnapshotOrigin;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.DeviationStatus;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The single source of truth for a working copy's deviation. Both ontology detail and concept detail
 * route here, so this is where the comparison behaviour (match / not-found / endpoint-down) and the
 * canonical local read live. The comparator is real, so each test checks which side reached which
 * slot of the deviation. The @Cacheable behaviour itself is a Spring concern verified elsewhere.
 */
@ExtendWith(MockitoExtension.class)
class WorkingCopyDeviationServiceImplTest {

    private static final String IRI = "https://slovník.gov.cz/test/pojem/obec";
    private static final String IRI_2 = "https://slovník.gov.cz/test/pojem/kraj";
    private static final String GRAPH = "https://slovník.gov.cz/test";

    private static final String LOCAL_DEF = "Obec podle místní kopie";
    private static final String NKD_DEF = "Obec podle NKD";
    private static final String LOCAL_DEF_2 = "Kraj podle místní kopie";
    private static final String NKD_DEF_2 = "Kraj podle NKD";

    @Mock private ConceptMetadataRepository conceptMetadataRepository;
    @Mock private JenaTDB2Repository jenaTDB2Repository;
    @Mock private OntologyDetailExtractor detailExtractor;
    @Mock private NkdSparqlClient nkdSparqlClient;
    @Mock private DeviationResolutionEnricher deviationEnricher;

    // A real manager: the bulk path's cache hit/miss branching is behaviour under test, and a
    // ConcurrentMapCache exercises it exactly as Caffeine would.
    private final CacheManager cacheManager = new ConcurrentMapCacheManager(
            WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE,
            NkdSparqlClient.PUBLISHED_RESOURCE_CACHE);

    private WorkingCopyDeviationServiceImpl service;

    /** The local projection of {@link #IRI}; its definition differs from {@link #published}. */
    private ConceptDetailModel local;
    /** The NKD twin of {@link #IRI}. */
    private ConceptDetailModel published;

    @BeforeEach
    void setUp() {
        service = new WorkingCopyDeviationServiceImpl(new ConceptDeviationComparator(), nkdSparqlClient,
                deviationEnricher, cacheManager,
                new LocalConceptProjectionReader(conceptMetadataRepository, jenaTDB2Repository,
                        detailExtractor, cacheManager));
        local = detail(IRI, LOCAL_DEF);
        published = detail(IRI, NKD_DEF);
    }

    private static ConceptDetailModel detail(String iri, String csDefinition) {
        return ConceptDetailModel.builder().iri(iri).definition(Map.of("cs", csDefinition)).build();
    }

    private static NkdSparqlClient.PublishedConcept inScheme(ConceptDetailModel detail, String scheme) {
        return new NkdSparqlClient.PublishedConcept(detail, scheme);
    }

    private void givenLocalGraph() {
        LocalProjectionStubs.givenLocalGraph(conceptMetadataRepository, jenaTDB2Repository, detailExtractor,
                IRI, GRAPH, local);
    }

    /** A working-copy deviation of {@code iri} whose only differing field is the definition. */
    private static void assertDefinitionDeviation(PublishedConceptDeviationModel deviation, String iri,
                                                  String localDef, String publishedDef) {
        assertThat(deviation.getStatus()).isEqualTo(DeviationStatus.HAS_DEVIATIONS);
        assertThat(deviation.getOrigin()).isEqualTo(SnapshotOrigin.WORKING_COPY);
        assertThat(deviation.getSource().getIri()).isEqualTo(iri);
        assertThat(deviation.getDefinition().getLocalValue()).isEqualTo(Map.of("cs", localDef));
        assertThat(deviation.getDefinition().getPublishedValue()).isEqualTo(Map.of("cs", publishedDef));
        assertThat(deviation.getDescription()).isNull();
        assertThat(deviation.getIdentifier()).isNull();
    }

    @Test
    void canonicalLocal_readsOfnTransformedModel() {
        givenLocalGraph();
        ConceptDetailModel result = service.canonicalLocalConcept(IRI);

        assertThat(result).isSameAs(local);
        // The canonical read is the OFN-transformed one — the divergence source between the two surfaces.
        verify(detailExtractor).applyOFNTransformations(any());
        verify(detailExtractor).extractConceptDetail(any(), any());
    }

    @Test
    void canonicalLocal_fromCallerModel_skipsTheGraphRead() {
        // Concept detail has already fetched this graph; re-reading it is a wasted round-trip plus a
        // second full-graph OFN transform.
        Model callerModel = ModelFactory.createDefaultModel();
        callerModel.add(callerModel.createResource(IRI), RDF.type,
                callerModel.createResource("http://www.w3.org/2002/07/owl#Class"));
        Model processed = ModelFactory.createDefaultModel();
        when(detailExtractor.applyOFNTransformations(callerModel)).thenReturn(processed);
        when(detailExtractor.extractConceptDetail(processed, IRI)).thenReturn(local);

        ConceptDetailModel result = service.canonicalLocalConcept(IRI, callerModel);

        assertThat(result).isSameAs(local);
        verify(jenaTDB2Repository, never()).fetchGraph(any());
    }

    @Test
    void canonicalLocal_fromCallerModel_emptyOrNull_returnsNull() {
        assertThat(service.canonicalLocalConcept(IRI, null)).isNull();
        assertThat(service.canonicalLocalConcept(IRI, ModelFactory.createDefaultModel())).isNull();
        verify(jenaTDB2Repository, never()).fetchGraph(any());
    }

    @Test
    void canonicalLocal_noMetadataRow_returnsNull() {
        when(conceptMetadataRepository.findByConceptIri(IRI)).thenReturn(Optional.empty());
        assertThat(service.canonicalLocalConcept(IRI)).isNull();
    }

    @Test
    void deviationForWithLocal_comparesSuppliedProjection_withoutReReadingTheGraph() {
        when(nkdSparqlClient.fetchPublishedConcept(IRI)).thenReturn(Optional.of(published));

        PublishedConceptDeviationModel result = service.deviationForWithLocal(IRI, local);

        assertDefinitionDeviation(result, IRI, LOCAL_DEF, NKD_DEF);
        verify(jenaTDB2Repository, never()).fetchGraph(any());
        verify(conceptMetadataRepository, never()).findByConceptIri(any());
    }

    @Test
    void deviationForWithLocal_nullLocal_comparesTheProjectionItReads() {
        givenLocalGraph();
        when(nkdSparqlClient.fetchPublishedConcept(IRI)).thenReturn(Optional.of(published));

        PublishedConceptDeviationModel result = service.deviationForWithLocal(IRI, null);

        assertDefinitionDeviation(result, IRI, LOCAL_DEF, NKD_DEF);
        verify(jenaTDB2Repository).fetchGraph(GRAPH);
    }

    @Test
    void deviation_differingTwin_reportsLocalAndPublishedValues() {
        givenLocalGraph();
        when(nkdSparqlClient.fetchPublishedConcept(IRI)).thenReturn(Optional.of(published));

        PublishedConceptDeviationModel result = service.deviationFor(IRI);

        assertDefinitionDeviation(result, IRI, LOCAL_DEF, NKD_DEF);
        verify(deviationEnricher).enrich(result);
    }

    @Test
    void deviation_identicalTwin_hasNoDeviation() {
        givenLocalGraph();
        when(nkdSparqlClient.fetchPublishedConcept(IRI)).thenReturn(Optional.of(detail(IRI, LOCAL_DEF)));

        PublishedConceptDeviationModel result = service.deviationFor(IRI);

        assertThat(result.getStatus()).isEqualTo(DeviationStatus.NO_DEVIATION);
        assertThat(result.getDefinition()).isNull();
    }

    @Test
    void deviation_notInNkd_returnsConceptNotFound() {
        givenLocalGraph();
        when(nkdSparqlClient.fetchPublishedConcept(IRI)).thenReturn(Optional.empty());

        PublishedConceptDeviationModel result = service.deviationFor(IRI);

        assertThat(result.getStatus()).isEqualTo(DeviationStatus.CONCEPT_NOT_FOUND_IN_NKD);
        assertThat(result.getOrigin()).isEqualTo(SnapshotOrigin.WORKING_COPY);
        assertThat(result.getSource().getIri()).isEqualTo(IRI);
        assertThat(result.getDefinition()).isNull();
        verify(deviationEnricher, never()).enrich(any());
    }

    @Test
    void deviation_nkdThrows_returnsEndpointUnavailable() {
        givenLocalGraph();
        // The NKD client funnels every upstream failure through SparqlEndpointUnavailableException.
        when(nkdSparqlClient.fetchPublishedConcept(IRI))
                .thenThrow(new SparqlEndpointUnavailableException(NkdSparqlClient.NKD_LABEL, "NKD timeout"));

        PublishedConceptDeviationModel result = service.deviationFor(IRI);

        assertThat(result.getStatus()).isEqualTo(DeviationStatus.ENDPOINT_UNAVAILABLE);
    }

    @Test
    void deviation_unexpectedFailure_returnsQueryErrorNotEndpointUnavailable() {
        givenLocalGraph();
        // A fault on our side must not be reported as an NKD outage.
        when(nkdSparqlClient.fetchPublishedConcept(IRI)).thenReturn(Optional.of(published));
        doThrow(new IllegalStateException("enricher bug")).when(deviationEnricher).enrich(any());

        PublishedConceptDeviationModel result = service.deviationFor(IRI);

        assertThat(result.getStatus()).isEqualTo(DeviationStatus.QUERY_ERROR);
        assertThat(result.getErrorMessage()).contains("enricher bug");
    }

    @Test
    void deviation_localMissing_returnsQueryError() {
        when(conceptMetadataRepository.findByConceptIri(IRI)).thenReturn(Optional.empty());

        PublishedConceptDeviationModel result = service.deviationFor(IRI);

        assertThat(result.getStatus()).isEqualTo(DeviationStatus.QUERY_ERROR);
        verify(nkdSparqlClient, never()).fetchPublishedConcept(any());
    }

    /**
     * The ontology-detail entry point. It must not re-read or re-transform the graph per concept, while
     * still producing what deviationFor would.
     */
    @Nested
    class Bulk {

        private final Model callerModel = ModelFactory.createDefaultModel();
        private final ConceptDetailModel local2 = detail(IRI_2, LOCAL_DEF_2);

        @Test
        void extractsFromCallersModel_neverRefetchesOrRetransformsPerConcept() {
            when(detailExtractor.extractConceptDetails(callerModel, List.of(IRI, IRI_2)))
                    .thenReturn(Map.of(IRI, local, IRI_2, local2));

            Map<String, ConceptDetailModel> result =
                    service.canonicalLocalConcepts(callerModel, List.of(IRI, IRI_2));

            assertThat(result).containsEntry(IRI, local).containsEntry(IRI_2, local2);
            verify(jenaTDB2Repository, never()).fetchGraph(any());
            verify(detailExtractor, never()).applyOFNTransformations(any());
            verify(detailExtractor, never()).extractConceptDetail(any(), any());
        }

        @Test
        void populatesTheSamePerIriCache_soLaterSingleReadsHit() {
            when(detailExtractor.extractConceptDetails(callerModel, List.of(IRI))).thenReturn(Map.of(IRI, local));

            service.canonicalLocalConcepts(callerModel, List.of(IRI));

            // Written under the plain conceptIri key, like a @Cacheable write, so the allEntries
            // evictions cover it.
            assertThat(cachedValue(WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE, IRI)).isSameAs(local);
        }

        @Test
        void servesCachedEntries_andOnlyExtractsMisses() {
            cache(WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE).put(IRI, local);
            when(detailExtractor.extractConceptDetails(callerModel, List.of(IRI_2)))
                    .thenReturn(Map.of(IRI_2, local2));

            Map<String, ConceptDetailModel> result =
                    service.canonicalLocalConcepts(callerModel, List.of(IRI, IRI_2));

            assertThat(result).containsEntry(IRI, local).containsEntry(IRI_2, local2);
        }

        @Test
        void cachedNullIsAnAnswer_notAMiss() {
            // A concept with no metadata row / empty graph caches as null; re-extracting it every request
            // would add per-concept work for exactly the concepts that cannot benefit from it.
            cache(WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE).put(IRI, null);

            Map<String, ConceptDetailModel> result = service.canonicalLocalConcepts(callerModel, List.of(IRI));

            assertThat(result).containsEntry(IRI, null);
            verify(detailExtractor, never()).extractConceptDetails(any(), any());
        }

        @Test
        void deviationForAll_readsLocalsInOnePass_notPerConcept() {
            when(detailExtractor.extractConceptDetails(callerModel, List.of(IRI, IRI_2)))
                    .thenReturn(Map.of(IRI, local, IRI_2, local2));
            when(nkdSparqlClient.fetchPublishedConceptsBatched(List.of(IRI, IRI_2))).thenReturn(Map.of(
                    IRI, Optional.of(inScheme(published, GRAPH)),
                    IRI_2, Optional.of(inScheme(detail(IRI_2, NKD_DEF_2), GRAPH))));

            Map<String, PublishedConceptDeviationModel> result =
                    service.deviationForAll(callerModel, List.of(IRI, IRI_2));

            // Each concept is compared against its own twin, local on the local side.
            assertDefinitionDeviation(result.get(IRI), IRI, LOCAL_DEF, NKD_DEF);
            assertDefinitionDeviation(result.get(IRI_2), IRI_2, LOCAL_DEF_2, NKD_DEF_2);
            verify(jenaTDB2Repository, never()).fetchGraph(any());
            verify(detailExtractor, never()).applyOFNTransformations(any());
            verify(detailExtractor, never()).extractConceptDetail(any(), any());
            // One enrichment for the whole set, none per concept.
            verify(deviationEnricher).enrichAll(List.of(result.get(IRI), result.get(IRI_2)));
            verify(deviationEnricher, never()).enrich(any());
        }

        @Test
        void prefetchesNkdInOneBatch_thenPerConceptFetchesHitCache() {
            when(detailExtractor.extractConceptDetails(callerModel, List.of(IRI, IRI_2)))
                    .thenReturn(Map.of(IRI, local, IRI_2, local2));
            when(nkdSparqlClient.fetchPublishedConceptsBatched(List.of(IRI, IRI_2))).thenReturn(Map.of(
                    IRI, Optional.of(inScheme(published, GRAPH)),
                    IRI_2, Optional.empty()));

            Map<String, PublishedConceptDeviationModel> result =
                    service.deviationForAll(callerModel, List.of(IRI, IRI_2));

            verify(nkdSparqlClient, never()).fetchPublishedConcept(any());
            assertDefinitionDeviation(result.get(IRI), IRI, LOCAL_DEF, NKD_DEF);
            // A concept absent from NKD keeps its not-found status through the batched path.
            assertThat(result.get(IRI_2).getStatus()).isEqualTo(DeviationStatus.CONCEPT_NOT_FOUND_IN_NKD);
        }

        @Test
        void prefetchSkipsAlreadyCachedConcepts() {
            when(detailExtractor.extractConceptDetails(any(), any())).thenReturn(Map.of(IRI, local));
            cache(NkdSparqlClient.PUBLISHED_RESOURCE_CACHE).put("concept:" + IRI, Optional.of(published));
            // The per-concept read that the cache entry serves in production (the client's @Cacheable).
            when(nkdSparqlClient.fetchPublishedConcept(IRI)).thenReturn(Optional.of(published));

            Map<String, PublishedConceptDeviationModel> result = service.deviationForAll(callerModel, List.of(IRI));

            verify(nkdSparqlClient, never()).fetchPublishedConceptsBatched(any());
            assertDefinitionDeviation(result.get(IRI), IRI, LOCAL_DEF, NKD_DEF);
        }

        @Test
        void batchFailure_fallsBackToPerConceptFetch() {
            when(detailExtractor.extractConceptDetails(any(), any())).thenReturn(Map.of(IRI, local));
            when(nkdSparqlClient.fetchPublishedConceptsBatched(any())).thenThrow(new RuntimeException("NKD down"));
            when(nkdSparqlClient.fetchPublishedConcept(IRI)).thenReturn(Optional.of(published));

            Map<String, PublishedConceptDeviationModel> result = service.deviationForAll(callerModel, List.of(IRI));

            assertDefinitionDeviation(result.get(IRI), IRI, LOCAL_DEF, NKD_DEF);
        }

        @Test
        void matchesPerIriResult_forTheSameConcept() {
            givenLocalGraph();
            when(detailExtractor.extractConceptDetails(callerModel, List.of(IRI))).thenReturn(Map.of(IRI, local));
            when(nkdSparqlClient.fetchPublishedConceptsBatched(List.of(IRI)))
                    .thenReturn(Map.of(IRI, Optional.of(inScheme(published, GRAPH))));
            // The per-IRI read after the bulk prefetch; the client's @Cacheable serves it in production.
            when(nkdSparqlClient.fetchPublishedConcept(IRI)).thenReturn(Optional.of(published));

            PublishedConceptDeviationModel bulk = service.deviationForAll(callerModel, List.of(IRI)).get(IRI);
            PublishedConceptDeviationModel single = service.deviationFor(IRI);

            assertDefinitionDeviation(bulk, IRI, LOCAL_DEF, NKD_DEF);
            assertThat(bulk).usingRecursiveComparison().isEqualTo(single);
            // The bulk path enriches the set once; the per-IRI path enriches inline.
            verify(deviationEnricher).enrichAll(anyList());
            verify(deviationEnricher).enrich(single);
        }

        @Test
        void localMissing_yieldsQueryErrorForThatConceptOnly() {
            Map<String, ConceptDetailModel> extracted = new HashMap<>();
            extracted.put(IRI, null);
            extracted.put(IRI_2, local2);
            when(detailExtractor.extractConceptDetails(callerModel, List.of(IRI, IRI_2))).thenReturn(extracted);
            when(nkdSparqlClient.fetchPublishedConceptsBatched(List.of(IRI, IRI_2))).thenReturn(Map.of(
                    IRI, Optional.of(inScheme(published, GRAPH)),
                    IRI_2, Optional.of(inScheme(detail(IRI_2, NKD_DEF_2), GRAPH))));

            Map<String, PublishedConceptDeviationModel> result =
                    service.deviationForAll(callerModel, List.of(IRI, IRI_2));

            assertThat(result.get(IRI).getStatus()).isEqualTo(DeviationStatus.QUERY_ERROR);
            assertDefinitionDeviation(result.get(IRI_2), IRI_2, LOCAL_DEF_2, NKD_DEF_2);
        }
    }

    /**
     * The ontology CONSTRUCT already carries every in-scheme concept, so the batched concept query is
     * redundant for concepts of that scheme.
     */
    @Nested
    class InOntology {

        private final Model callerModel = ModelFactory.createDefaultModel();
        private final Model ontologyModel = ModelFactory.createDefaultModel();

        @Test
        void derivesConceptsFromOntologyModel_withoutBatchedConceptQuery() {
            when(detailExtractor.extractConceptDetails(callerModel, List.of(IRI))).thenReturn(Map.of(IRI, local));
            when(nkdSparqlClient.fetchPublishedOntologyRaw(GRAPH)).thenReturn(Optional.of(ontologyModel));
            when(nkdSparqlClient.derivePublishedConceptsFromOntology(ontologyModel, List.of(IRI)))
                    .thenReturn(Map.of(IRI, Optional.of(inScheme(published, GRAPH))));

            Map<String, PublishedConceptDeviationModel> result =
                    service.deviationForAllInOntology(callerModel, List.of(IRI), GRAPH);

            assertDefinitionDeviation(result.get(IRI), IRI, LOCAL_DEF, NKD_DEF);
            verify(nkdSparqlClient, never()).fetchPublishedConceptsBatched(any());
        }

        @Test
        void seedsBothCacheKeys_soPerIriFetchesStayOffTheNetwork() {
            when(detailExtractor.extractConceptDetails(callerModel, List.of(IRI))).thenReturn(Map.of(IRI, local));
            when(nkdSparqlClient.fetchPublishedOntologyRaw(GRAPH)).thenReturn(Optional.of(ontologyModel));
            NkdSparqlClient.PublishedConcept twin = inScheme(published, GRAPH);
            when(nkdSparqlClient.derivePublishedConceptsFromOntology(ontologyModel, List.of(IRI)))
                    .thenReturn(Map.of(IRI, Optional.of(twin)));

            service.deviationForAllInOntology(callerModel, List.of(IRI), GRAPH);

            assertThat(cachedValue(NkdSparqlClient.PUBLISHED_RESOURCE_CACHE, "concept:" + IRI)).isEqualTo(Optional.of(published));
            assertThat(cachedValue(NkdSparqlClient.PUBLISHED_RESOURCE_CACHE, "conceptWithScheme:" + IRI)).isEqualTo(Optional.of(twin));
        }

        @Test
        void conceptAbsentFromScheme_stillQueriedSeparately() {
            // Absence from the scheme model is not absence from NKD: the concept may be published under
            // a different scheme.
            ConceptDetailModel local2 = detail(IRI_2, LOCAL_DEF_2);
            when(detailExtractor.extractConceptDetails(callerModel, List.of(IRI, IRI_2)))
                    .thenReturn(Map.of(IRI, local, IRI_2, local2));
            when(nkdSparqlClient.fetchPublishedOntologyRaw(GRAPH)).thenReturn(Optional.of(ontologyModel));
            Map<String, Optional<NkdSparqlClient.PublishedConcept>> derived = new HashMap<>();
            derived.put(IRI, Optional.of(inScheme(published, GRAPH)));
            derived.put(IRI_2, Optional.empty());
            when(nkdSparqlClient.derivePublishedConceptsFromOntology(ontologyModel, List.of(IRI, IRI_2)))
                    .thenReturn(derived);
            when(nkdSparqlClient.fetchPublishedConceptsBatched(List.of(IRI_2))).thenReturn(Map.of(IRI_2,
                    Optional.of(inScheme(detail(IRI_2, NKD_DEF_2), "https://slovník.gov.cz/other"))));

            Map<String, PublishedConceptDeviationModel> result =
                    service.deviationForAllInOntology(callerModel, List.of(IRI, IRI_2), GRAPH);

            assertDefinitionDeviation(result.get(IRI), IRI, LOCAL_DEF, NKD_DEF);
            assertDefinitionDeviation(result.get(IRI_2), IRI_2, LOCAL_DEF_2, NKD_DEF_2);
        }

        @Test
        void ontologyModelUnavailable_fallsBackToBatchedQuery() {
            when(detailExtractor.extractConceptDetails(callerModel, List.of(IRI))).thenReturn(Map.of(IRI, local));
            when(nkdSparqlClient.fetchPublishedOntologyRaw(GRAPH)).thenReturn(Optional.empty());
            when(nkdSparqlClient.fetchPublishedConceptsBatched(List.of(IRI)))
                    .thenReturn(Map.of(IRI, Optional.of(inScheme(published, GRAPH))));

            Map<String, PublishedConceptDeviationModel> result =
                    service.deviationForAllInOntology(callerModel, List.of(IRI), GRAPH);

            assertDefinitionDeviation(result.get(IRI), IRI, LOCAL_DEF, NKD_DEF);
        }

        @Test
        void nullOntologyIri_keepsBatchedBehaviour() {
            when(detailExtractor.extractConceptDetails(callerModel, List.of(IRI))).thenReturn(Map.of(IRI, local));
            when(nkdSparqlClient.fetchPublishedConceptsBatched(List.of(IRI)))
                    .thenReturn(Map.of(IRI, Optional.of(inScheme(published, GRAPH))));

            Map<String, PublishedConceptDeviationModel> result =
                    service.deviationForAllInOntology(callerModel, List.of(IRI), null);

            assertDefinitionDeviation(result.get(IRI), IRI, LOCAL_DEF, NKD_DEF);
            verify(nkdSparqlClient, never()).fetchPublishedOntologyRaw(any());
        }
    }

    private Cache cache(String name) {
        Cache cache = cacheManager.getCache(name);
        if (cache == null) throw new AssertionError("no cache " + name);
        return cache;
    }

    /** The stored value; fails when the key has no entry (a cached null is an entry). */
    private Object cachedValue(String name, Object key) {
        Cache.ValueWrapper entry = cache(name).get(key);
        if (entry == null) throw new AssertionError("no entry " + key + " in cache " + name);
        return entry.get();
    }
}
