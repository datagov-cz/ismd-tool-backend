package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The single source of truth for a working copy's deviation. Both ontology detail and concept detail
 * route here, so this is where the comparison behaviour (match / not-found / endpoint-down) and the
 * canonical local read live. The @Cacheable behaviour itself is a Spring concern verified elsewhere;
 * these are the plain-logic tests.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkingCopyDeviationServiceImplTest {

    private static final String IRI = "https://slovník.gov.cz/test/pojem/obec";
    private static final String GRAPH = "https://slovník.gov.cz/test";

    @Mock private ConceptMetadataRepository conceptMetadataRepository;
    @Mock private JenaTDB2Repository jenaTDB2Repository;
    @Mock private OntologyDetailExtractor detailExtractor;
    @Mock private ConceptDeviationComparator conceptDeviationComparator;
    @Mock private NkdSparqlClient nkdSparqlClient;
    @Mock private DeviationResolutionEnricher deviationEnricher;

    // A real no-op manager rather than a mock: the bulk path's cache hit/miss branching is behaviour
    // under test, and a ConcurrentMapCache exercises it exactly as Caffeine would.
    private final CacheManager cacheManager = new ConcurrentMapCacheManager(
            WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE,
            NkdSparqlClient.PUBLISHED_RESOURCE_CACHE);

    private WorkingCopyDeviationServiceImpl service;

    private ConceptDetailModel local;

    private WorkingCopyDeviationServiceImpl newService(WorkingCopyDeviationServiceImpl self) {
        return new WorkingCopyDeviationServiceImpl(conceptMetadataRepository, jenaTDB2Repository,
                detailExtractor, conceptDeviationComparator, nkdSparqlClient,
                deviationEnricher, cacheManager, self);
    }

    @BeforeEach
    void setUp() {
        // Pass the instance as its own `self` — in production Spring injects the @Lazy proxy so
        // canonicalLocalConcept is cached; the unit test just needs the internal call to resolve. Build a
        // throwaway first so the real instance can reference itself.
        service = newService(null);
        service = newService(service);

        ConceptMetadataEntity metadata = new ConceptMetadataEntity();
        metadata.setConceptIri(IRI);
        metadata.setGraphName(GRAPH);
        when(conceptMetadataRepository.findByConceptIri(IRI)).thenReturn(Optional.of(metadata));

        Model rawModel = ModelFactory.createDefaultModel();
        // A bare resource adds no statement; add one so rawModel.isEmpty() is false.
        rawModel.add(rawModel.createResource(IRI),
                org.apache.jena.vocabulary.RDF.type,
                rawModel.createResource("http://www.w3.org/2002/07/owl#Class"));
        Model processedModel = ModelFactory.createDefaultModel();
        when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(rawModel);
        when(detailExtractor.applyOFNTransformations(rawModel)).thenReturn(processedModel);

        local = ConceptDetailModel.builder().iri(IRI).build();
        when(detailExtractor.extractConceptDetail(processedModel, IRI)).thenReturn(local);
    }

    @Test
    void canonicalLocal_readsOfnTransformedModel() {
        ConceptDetailModel result = service.canonicalLocalConcept(IRI);

        assertThat(result).isSameAs(local);
        // The canonical read is the OFN-transformed one — the divergence source between the two surfaces.
        verify(detailExtractor).applyOFNTransformations(any());
        verify(detailExtractor).extractConceptDetail(any(), any());
    }

    @Test
    void canonicalLocal_fromCallerModel_skipsTheGraphRead() {
        // Concept detail has already fetched this graph. Re-reading it from Fuseki is a wasted
        // round-trip plus a second full-graph OFN transform.
        Model callerModel = ModelFactory.createDefaultModel();
        callerModel.add(callerModel.createResource(IRI),
                org.apache.jena.vocabulary.RDF.type,
                callerModel.createResource("http://www.w3.org/2002/07/owl#Class"));
        Model processed = ModelFactory.createDefaultModel();
        when(detailExtractor.applyOFNTransformations(callerModel)).thenReturn(processed);
        ConceptDetailModel expected = ConceptDetailModel.builder().iri(IRI).build();
        when(detailExtractor.extractConceptDetail(processed, IRI)).thenReturn(expected);

        ConceptDetailModel result = service.canonicalLocalConcept(IRI, callerModel);

        assertThat(result).isSameAs(expected);
        verify(jenaTDB2Repository, never()).fetchGraph(any());
        // Same projection as the fetching overload: OFN transform then extract.
        verify(detailExtractor).applyOFNTransformations(callerModel);
    }

    @Test
    void canonicalLocal_fromCallerModel_emptyOrNull_returnsNull() {
        assertThat(service.canonicalLocalConcept(IRI, null)).isNull();
        assertThat(service.canonicalLocalConcept(IRI, ModelFactory.createDefaultModel())).isNull();
        verify(jenaTDB2Repository, never()).fetchGraph(any());
    }

    @Test
    void deviationForWithLocal_usesSuppliedProjection_withoutReReadingTheGraph() {
        ConceptDetailModel supplied = ConceptDetailModel.builder().iri(IRI).build();
        ConceptDetailModel published = ConceptDetailModel.builder().iri(IRI).build();
        when(nkdSparqlClient.fetchPublishedConcept(IRI)).thenReturn(Optional.of(published));
        PublishedConceptDeviationModel expected = PublishedConceptDeviationModel.builder()
                .status(DeviationStatus.NO_DEVIATION).build();
        when(conceptDeviationComparator.compareConceptDetails(supplied, published, SnapshotOrigin.WORKING_COPY, IRI))
                .thenReturn(expected);

        PublishedConceptDeviationModel result = service.deviationForWithLocal(IRI, supplied);

        assertThat(result).isSameAs(expected);
        verify(jenaTDB2Repository, never()).fetchGraph(any());
        verify(conceptMetadataRepository, never()).findByConceptIri(any());
    }

    @Test
    void deviationForWithLocal_nullLocal_fallsBackToReadingIt() {
        ConceptDetailModel published = ConceptDetailModel.builder().iri(IRI).build();
        when(nkdSparqlClient.fetchPublishedConcept(IRI)).thenReturn(Optional.of(published));
        when(conceptDeviationComparator.compareConceptDetails(local, published, SnapshotOrigin.WORKING_COPY, IRI))
                .thenReturn(PublishedConceptDeviationModel.builder().status(DeviationStatus.NO_DEVIATION).build());

        service.deviationForWithLocal(IRI, null);

        verify(jenaTDB2Repository).fetchGraph(GRAPH);
    }

    @Test
    void canonicalLocal_noMetadataRow_returnsNull() {
        when(conceptMetadataRepository.findByConceptIri(IRI)).thenReturn(Optional.empty());
        assertThat(service.canonicalLocalConcept(IRI)).isNull();
    }

    @Test
    void deviation_nkdMatches_runsComparatorWithWorkingCopyOrigin() {
        ConceptDetailModel published = ConceptDetailModel.builder().iri(IRI).build();
        when(nkdSparqlClient.fetchPublishedConcept(IRI)).thenReturn(Optional.of(published));
        PublishedConceptDeviationModel expected = PublishedConceptDeviationModel.builder()
                .status(DeviationStatus.NO_DEVIATION).build();
        when(conceptDeviationComparator.compareConceptDetails(local, published, SnapshotOrigin.WORKING_COPY, IRI))
                .thenReturn(expected);

        PublishedConceptDeviationModel result = service.deviationFor(IRI);

        assertThat(result).isSameAs(expected);
        verify(conceptDeviationComparator).compareConceptDetails(local, published, SnapshotOrigin.WORKING_COPY, IRI);
    }

    @Test
    void deviation_enrichesTheResult() {
        // Resolution of concept/RPP refs is delegated to DeviationResolutionEnricher (own test); here we
        // only pin that deviationFor runs it on the comparator's output before returning.
        ConceptDetailModel published = ConceptDetailModel.builder().iri(IRI).build();
        when(nkdSparqlClient.fetchPublishedConcept(IRI)).thenReturn(Optional.of(published));
        PublishedConceptDeviationModel deviation = PublishedConceptDeviationModel.builder()
                .status(DeviationStatus.HAS_DEVIATIONS).build();
        when(conceptDeviationComparator.compareConceptDetails(local, published, SnapshotOrigin.WORKING_COPY, IRI))
                .thenReturn(deviation);

        PublishedConceptDeviationModel result = service.deviationFor(IRI);

        assertThat(result).isSameAs(deviation);
        verify(deviationEnricher).enrich(deviation);
    }

    @Test
    void deviation_notInNkd_returnsConceptNotFound_noComparison() {
        when(nkdSparqlClient.fetchPublishedConcept(IRI)).thenReturn(Optional.empty());

        PublishedConceptDeviationModel result = service.deviationFor(IRI);

        assertThat(result.getStatus()).isEqualTo(DeviationStatus.CONCEPT_NOT_FOUND_IN_NKD);
        assertThat(result.getOrigin()).isEqualTo(SnapshotOrigin.WORKING_COPY);
        assertThat(result.getSource().getIri()).isEqualTo(IRI);
        verify(conceptDeviationComparator, never()).compareConceptDetails(any(), any(), any(), any());
    }

    @Test
    void deviation_nkdThrows_returnsEndpointUnavailable() {
        // The NKD client funnels every upstream failure through SparqlEndpointUnavailableException.
        when(nkdSparqlClient.fetchPublishedConcept(IRI))
                .thenThrow(new SparqlEndpointUnavailableException(NkdSparqlClient.NKD_LABEL, "NKD timeout"));

        PublishedConceptDeviationModel result = service.deviationFor(IRI);

        assertThat(result.getStatus()).isEqualTo(DeviationStatus.ENDPOINT_UNAVAILABLE);
    }

    @Test
    void deviation_unexpectedFailure_returnsQueryErrorNotEndpointUnavailable() {
        // A fault on our side must not be reported as an NKD outage — that disguise hid an NPE here before.
        when(nkdSparqlClient.fetchPublishedConcept(IRI))
                .thenReturn(Optional.of(ConceptDetailModel.builder().iri(IRI).build()));
        when(conceptDeviationComparator.compareConceptDetails(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("comparator bug"));

        PublishedConceptDeviationModel result = service.deviationFor(IRI);

        assertThat(result.getStatus()).isEqualTo(DeviationStatus.QUERY_ERROR);
        assertThat(result.getErrorMessage()).contains("comparator bug");
    }

    @Test
    void deviation_localMissing_returnsQueryError() {
        when(conceptMetadataRepository.findByConceptIri(IRI)).thenReturn(Optional.empty());

        PublishedConceptDeviationModel result = service.deviationFor(IRI);

        assertThat(result.getStatus()).isEqualTo(DeviationStatus.QUERY_ERROR);
        verify(nkdSparqlClient, never()).fetchPublishedConcept(any());
    }

    // --- bulk path -------------------------------------------------------------------------------
    // The ontology-detail entry point. Its whole reason to exist is that it must NOT re-read or
    // re-transform the graph per concept, while still producing what deviationFor would.

    private static final String IRI_2 = "https://slovník.gov.cz/test/pojem/kraj";

    @Test
    void bulk_extractsFromCallersModel_neverRefetchesOrRetransformsPerConcept() {
        Model callerModel = ModelFactory.createDefaultModel();
        ConceptDetailModel local2 = ConceptDetailModel.builder().iri(IRI_2).build();
        when(detailExtractor.extractConceptDetails(callerModel, List.of(IRI, IRI_2)))
                .thenReturn(Map.of(IRI, local, IRI_2, local2));

        Map<String, ConceptDetailModel> result =
                service.canonicalLocalConcepts(callerModel, List.of(IRI, IRI_2));

        assertThat(result).containsEntry(IRI, local).containsEntry(IRI_2, local2);
        // The regression this fixes: one shared extraction, zero per-concept graph reads/transforms.
        verify(detailExtractor).extractConceptDetails(callerModel, List.of(IRI, IRI_2));
        verify(jenaTDB2Repository, never()).fetchGraph(any());
        verify(detailExtractor, never()).applyOFNTransformations(any());
        verify(detailExtractor, never()).extractConceptDetail(any(), any());
    }

    @Test
    void bulk_populatesTheSamePerIriCache_soLaterSingleReadsHit() {
        Model callerModel = ModelFactory.createDefaultModel();
        when(detailExtractor.extractConceptDetails(callerModel, List.of(IRI)))
                .thenReturn(Map.of(IRI, local));

        service.canonicalLocalConcepts(callerModel, List.of(IRI));

        // Written under the plain conceptIri key — indistinguishable from a @Cacheable write, so the
        // existing allEntries evictions still cover it.
        assertThat(cacheManager.getCache(WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE)
                .get(IRI).get()).isSameAs(local);
    }

    @Test
    void bulk_servesCachedEntries_andOnlyExtractsMisses() {
        Model callerModel = ModelFactory.createDefaultModel();
        cacheManager.getCache(WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE).put(IRI, local);
        ConceptDetailModel local2 = ConceptDetailModel.builder().iri(IRI_2).build();
        when(detailExtractor.extractConceptDetails(callerModel, List.of(IRI_2)))
                .thenReturn(Map.of(IRI_2, local2));

        Map<String, ConceptDetailModel> result =
                service.canonicalLocalConcepts(callerModel, List.of(IRI, IRI_2));

        assertThat(result).containsEntry(IRI, local).containsEntry(IRI_2, local2);
        verify(detailExtractor).extractConceptDetails(callerModel, List.of(IRI_2));
    }

    @Test
    void bulk_cachedNullIsAnAnswer_notAMiss() {
        // A concept with no metadata row / empty graph caches as null. Re-extracting it every request
        // would reintroduce per-concept work for exactly the concepts that can't benefit from it.
        Model callerModel = ModelFactory.createDefaultModel();
        cacheManager.getCache(WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE).put(IRI, null);

        Map<String, ConceptDetailModel> result = service.canonicalLocalConcepts(callerModel, List.of(IRI));

        assertThat(result).containsEntry(IRI, null);
        verify(detailExtractor, never()).extractConceptDetails(any(), any());
    }

    @Test
    void bulk_deviationForAll_readsLocalsInOnePass_notPerConcept() {
        // Guards the entry point the ontology-detail path actually calls. The tests above pin
        // canonicalLocalConcepts directly, so they stay green even if deviationForAll stops using it —
        // which is precisely the regression (per-IRI reads re-fetch + re-transform the whole graph).
        Model callerModel = ModelFactory.createDefaultModel();
        ConceptDetailModel local2 = ConceptDetailModel.builder().iri(IRI_2).build();
        when(detailExtractor.extractConceptDetails(callerModel, List.of(IRI, IRI_2)))
                .thenReturn(Map.of(IRI, local, IRI_2, local2));
        when(nkdSparqlClient.fetchPublishedConcept(any())).thenReturn(Optional.empty());

        service.deviationForAll(callerModel, List.of(IRI, IRI_2));

        verify(detailExtractor).extractConceptDetails(callerModel, List.of(IRI, IRI_2));
        verify(jenaTDB2Repository, never()).fetchGraph(any());
        verify(detailExtractor, never()).applyOFNTransformations(any());
        verify(detailExtractor, never()).extractConceptDetail(any(), any());
    }

    @Test
    void bulk_prefetchesNkdInOneBatch_thenPerConceptFetchesHitCache() {
        // The dominant cold cost: 129 serial ~180ms NKD round-trips. One batched call must replace them.
        Model callerModel = ModelFactory.createDefaultModel();
        ConceptDetailModel local2 = ConceptDetailModel.builder().iri(IRI_2).build();
        when(detailExtractor.extractConceptDetails(callerModel, List.of(IRI, IRI_2)))
                .thenReturn(Map.of(IRI, local, IRI_2, local2));
        ConceptDetailModel pub = ConceptDetailModel.builder().iri(IRI).build();
        when(nkdSparqlClient.fetchPublishedConceptsBatched(List.of(IRI, IRI_2))).thenReturn(Map.of(
                IRI, Optional.of(new NkdSparqlClient.PublishedConcept(pub, "https://slovník.gov.cz/test")),
                IRI_2, Optional.empty()));
        when(conceptDeviationComparator.compareConceptDetails(any(), any(), any(), any()))
                .thenReturn(PublishedConceptDeviationModel.builder().status(DeviationStatus.NO_DEVIATION).build());

        Map<String, PublishedConceptDeviationModel> result =
                service.deviationForAll(callerModel, List.of(IRI, IRI_2));

        verify(nkdSparqlClient).fetchPublishedConceptsBatched(List.of(IRI, IRI_2));
        // Seeded the shared cache, so the per-concept path never went to the network.
        verify(nkdSparqlClient, never()).fetchPublishedConcept(any());
        assertThat(result.get(IRI).getStatus()).isEqualTo(DeviationStatus.NO_DEVIATION);
        // A concept absent from NKD keeps its not-found status through the batched path.
        assertThat(result.get(IRI_2).getStatus()).isEqualTo(DeviationStatus.CONCEPT_NOT_FOUND_IN_NKD);
    }

    @Test
    void bulk_prefetchSkipsAlreadyCachedConcepts() {
        Model callerModel = ModelFactory.createDefaultModel();
        when(detailExtractor.extractConceptDetails(any(), any())).thenReturn(Map.of(IRI, local));
        cacheManager.getCache(NkdSparqlClient.PUBLISHED_RESOURCE_CACHE)
                .put("concept:" + IRI, Optional.of(ConceptDetailModel.builder().iri(IRI).build()));
        when(conceptDeviationComparator.compareConceptDetails(any(), any(), any(), any()))
                .thenReturn(PublishedConceptDeviationModel.builder().status(DeviationStatus.NO_DEVIATION).build());

        service.deviationForAll(callerModel, List.of(IRI));

        verify(nkdSparqlClient, never()).fetchPublishedConceptsBatched(any());
    }

    @Test
    void bulk_batchFailure_fallsBackToPerConceptFetch() {
        // A batch failure must degrade, not fail the whole detail response.
        Model callerModel = ModelFactory.createDefaultModel();
        when(detailExtractor.extractConceptDetails(any(), any())).thenReturn(Map.of(IRI, local));
        when(nkdSparqlClient.fetchPublishedConceptsBatched(any())).thenThrow(new RuntimeException("NKD down"));
        ConceptDetailModel published = ConceptDetailModel.builder().iri(IRI).build();
        when(nkdSparqlClient.fetchPublishedConcept(IRI)).thenReturn(Optional.of(published));
        PublishedConceptDeviationModel expected = PublishedConceptDeviationModel.builder()
                .status(DeviationStatus.NO_DEVIATION).build();
        when(conceptDeviationComparator.compareConceptDetails(local, published, SnapshotOrigin.WORKING_COPY, IRI))
                .thenReturn(expected);

        Map<String, PublishedConceptDeviationModel> result = service.deviationForAll(callerModel, List.of(IRI));

        assertThat(result.get(IRI)).isSameAs(expected);
        verify(nkdSparqlClient).fetchPublishedConcept(IRI);
    }

    @Test
    void bulk_matchesPerIriResult_forTheSameConcept() {
        // The single-source-of-truth guarantee: bulk and per-IRI must not disagree.
        Model callerModel = ModelFactory.createDefaultModel();
        ConceptDetailModel published = ConceptDetailModel.builder().iri(IRI).build();
        when(detailExtractor.extractConceptDetails(callerModel, List.of(IRI))).thenReturn(Map.of(IRI, local));
        when(nkdSparqlClient.fetchPublishedConcept(IRI)).thenReturn(Optional.of(published));
        PublishedConceptDeviationModel expected = PublishedConceptDeviationModel.builder()
                .status(DeviationStatus.NO_DEVIATION).build();
        when(conceptDeviationComparator.compareConceptDetails(local, published, SnapshotOrigin.WORKING_COPY, IRI))
                .thenReturn(expected);

        Map<String, PublishedConceptDeviationModel> bulk = service.deviationForAll(callerModel, List.of(IRI));

        assertThat(bulk.get(IRI)).isSameAs(expected).isSameAs(service.deviationFor(IRI));
        // Both surfaces enrich the same deviation, but via different entry points: the bulk path
        // defers to one enrichAll for the whole set (so N deviations cost ONE resolve round-trip
        // rather than N), while the per-IRI path enriches inline.
        verify(deviationEnricher).enrichAll(org.mockito.ArgumentMatchers.anyList());
        verify(deviationEnricher).enrich(expected);
    }

    @Test
    void bulk_localMissing_yieldsQueryErrorForThatConceptOnly() {
        Model callerModel = ModelFactory.createDefaultModel();
        ConceptDetailModel published = ConceptDetailModel.builder().iri(IRI_2).build();
        // IRI has no local projection; IRI_2 does.
        java.util.Map<String, ConceptDetailModel> extracted = new java.util.HashMap<>();
        extracted.put(IRI, null);
        extracted.put(IRI_2, ConceptDetailModel.builder().iri(IRI_2).build());
        when(detailExtractor.extractConceptDetails(callerModel, List.of(IRI, IRI_2))).thenReturn(extracted);
        when(nkdSparqlClient.fetchPublishedConcept(IRI_2)).thenReturn(Optional.of(published));
        when(conceptDeviationComparator.compareConceptDetails(any(), any(), any(), any()))
                .thenReturn(PublishedConceptDeviationModel.builder().status(DeviationStatus.NO_DEVIATION).build());

        Map<String, PublishedConceptDeviationModel> result =
                service.deviationForAll(callerModel, List.of(IRI, IRI_2));

        assertThat(result.get(IRI).getStatus()).isEqualTo(DeviationStatus.QUERY_ERROR);
        assertThat(result.get(IRI_2).getStatus()).isEqualTo(DeviationStatus.NO_DEVIATION);
        verify(nkdSparqlClient, never()).fetchPublishedConcept(IRI);
    }

    // --- Merged NKD round-trip: the ontology CONSTRUCT already carries every in-scheme concept, so
    // the batched concept query is redundant for concepts of that scheme. ---

    @Test
    void inOntology_derivesConceptsFromOntologyModel_withoutBatchedConceptQuery() {
        // The whole point of the merge: ONE NKD round-trip, not two.
        Model callerModel = ModelFactory.createDefaultModel();
        Model ontologyModel = ModelFactory.createDefaultModel();
        ontologyModel.add(ontologyModel.createResource(IRI),
                org.apache.jena.vocabulary.RDF.type,
                ontologyModel.createResource("http://www.w3.org/2002/07/owl#Class"));
        when(detailExtractor.extractConceptDetails(callerModel, List.of(IRI))).thenReturn(Map.of(IRI, local));
        when(nkdSparqlClient.fetchPublishedOntologyRaw(GRAPH)).thenReturn(Optional.of(ontologyModel));
        ConceptDetailModel pub = ConceptDetailModel.builder().iri(IRI).build();
        when(nkdSparqlClient.derivePublishedConceptsFromOntology(ontologyModel, List.of(IRI)))
                .thenReturn(Map.of(IRI, Optional.of(new NkdSparqlClient.PublishedConcept(pub, GRAPH))));
        when(conceptDeviationComparator.compareConceptDetails(any(), any(), any(), any()))
                .thenReturn(PublishedConceptDeviationModel.builder().status(DeviationStatus.NO_DEVIATION).build());

        Map<String, PublishedConceptDeviationModel> result =
                service.deviationForAllInOntology(callerModel, List.of(IRI), GRAPH);

        assertThat(result.get(IRI).getStatus()).isEqualTo(DeviationStatus.NO_DEVIATION);
        verify(nkdSparqlClient).derivePublishedConceptsFromOntology(ontologyModel, List.of(IRI));
        // The second round-trip is gone.
        verify(nkdSparqlClient, never()).fetchPublishedConceptsBatched(any());
    }

    @Test
    void inOntology_seedsBothCacheKeys_soPerIriFetchesStayOffTheNetwork() {
        Model callerModel = ModelFactory.createDefaultModel();
        Model ontologyModel = ModelFactory.createDefaultModel();
        when(detailExtractor.extractConceptDetails(callerModel, List.of(IRI))).thenReturn(Map.of(IRI, local));
        when(nkdSparqlClient.fetchPublishedOntologyRaw(GRAPH)).thenReturn(Optional.of(ontologyModel));
        ConceptDetailModel pub = ConceptDetailModel.builder().iri(IRI).build();
        NkdSparqlClient.PublishedConcept published = new NkdSparqlClient.PublishedConcept(pub, GRAPH);
        when(nkdSparqlClient.derivePublishedConceptsFromOntology(any(), any()))
                .thenReturn(Map.of(IRI, Optional.of(published)));
        when(conceptDeviationComparator.compareConceptDetails(any(), any(), any(), any()))
                .thenReturn(PublishedConceptDeviationModel.builder().status(DeviationStatus.NO_DEVIATION).build());

        service.deviationForAllInOntology(callerModel, List.of(IRI), GRAPH);

        var cache = cacheManager.getCache(NkdSparqlClient.PUBLISHED_RESOURCE_CACHE);
        assertThat(cache.get("concept:" + IRI, Optional.class)).contains(pub);
        assertThat(cache.get("conceptWithScheme:" + IRI, Optional.class)).contains(published);
    }

    @Test
    void inOntology_conceptAbsentFromScheme_stillQueriedSeparately() {
        // Absence from the scheme model is not absence from NKD — the concept may be published under
        // a different scheme. Coverage must not shrink relative to the batched path.
        Model callerModel = ModelFactory.createDefaultModel();
        Model ontologyModel = ModelFactory.createDefaultModel();
        ConceptDetailModel local2 = ConceptDetailModel.builder().iri(IRI_2).build();
        when(detailExtractor.extractConceptDetails(callerModel, List.of(IRI, IRI_2)))
                .thenReturn(Map.of(IRI, local, IRI_2, local2));
        when(nkdSparqlClient.fetchPublishedOntologyRaw(GRAPH)).thenReturn(Optional.of(ontologyModel));
        ConceptDetailModel pub = ConceptDetailModel.builder().iri(IRI).build();
        java.util.Map<String, Optional<NkdSparqlClient.PublishedConcept>> derived = new java.util.HashMap<>();
        derived.put(IRI, Optional.of(new NkdSparqlClient.PublishedConcept(pub, GRAPH)));
        derived.put(IRI_2, Optional.empty());
        when(nkdSparqlClient.derivePublishedConceptsFromOntology(any(), any())).thenReturn(derived);
        ConceptDetailModel pub2 = ConceptDetailModel.builder().iri(IRI_2).build();
        when(nkdSparqlClient.fetchPublishedConceptsBatched(List.of(IRI_2))).thenReturn(
                Map.of(IRI_2, Optional.of(new NkdSparqlClient.PublishedConcept(pub2, "https://slovník.gov.cz/other"))));
        when(conceptDeviationComparator.compareConceptDetails(any(), any(), any(), any()))
                .thenReturn(PublishedConceptDeviationModel.builder().status(DeviationStatus.NO_DEVIATION).build());

        Map<String, PublishedConceptDeviationModel> result =
                service.deviationForAllInOntology(callerModel, List.of(IRI, IRI_2), GRAPH);

        // Only the unresolved one is re-queried, not the whole batch.
        verify(nkdSparqlClient).fetchPublishedConceptsBatched(List.of(IRI_2));
        assertThat(result.get(IRI).getStatus()).isEqualTo(DeviationStatus.NO_DEVIATION);
        assertThat(result.get(IRI_2).getStatus()).isEqualTo(DeviationStatus.NO_DEVIATION);
    }

    @Test
    void inOntology_ontologyModelUnavailable_fallsBackToBatchedQuery() {
        Model callerModel = ModelFactory.createDefaultModel();
        when(detailExtractor.extractConceptDetails(callerModel, List.of(IRI))).thenReturn(Map.of(IRI, local));
        when(nkdSparqlClient.fetchPublishedOntologyRaw(GRAPH)).thenReturn(Optional.empty());
        ConceptDetailModel pub = ConceptDetailModel.builder().iri(IRI).build();
        when(nkdSparqlClient.fetchPublishedConceptsBatched(List.of(IRI))).thenReturn(
                Map.of(IRI, Optional.of(new NkdSparqlClient.PublishedConcept(pub, GRAPH))));
        when(conceptDeviationComparator.compareConceptDetails(any(), any(), any(), any()))
                .thenReturn(PublishedConceptDeviationModel.builder().status(DeviationStatus.NO_DEVIATION).build());

        Map<String, PublishedConceptDeviationModel> result =
                service.deviationForAllInOntology(callerModel, List.of(IRI), GRAPH);

        verify(nkdSparqlClient).fetchPublishedConceptsBatched(List.of(IRI));
        assertThat(result.get(IRI).getStatus()).isEqualTo(DeviationStatus.NO_DEVIATION);
    }

    @Test
    void nullOntologyIri_keepsBatchedBehaviour() {
        // deviationForAll delegates with a null ontology, so the legacy path must be untouched.
        Model callerModel = ModelFactory.createDefaultModel();
        when(detailExtractor.extractConceptDetails(callerModel, List.of(IRI))).thenReturn(Map.of(IRI, local));
        ConceptDetailModel pub = ConceptDetailModel.builder().iri(IRI).build();
        when(nkdSparqlClient.fetchPublishedConceptsBatched(List.of(IRI))).thenReturn(
                Map.of(IRI, Optional.of(new NkdSparqlClient.PublishedConcept(pub, GRAPH))));
        when(conceptDeviationComparator.compareConceptDetails(any(), any(), any(), any()))
                .thenReturn(PublishedConceptDeviationModel.builder().status(DeviationStatus.NO_DEVIATION).build());

        service.deviationForAllInOntology(callerModel, List.of(IRI), null);

        verify(nkdSparqlClient).fetchPublishedConceptsBatched(List.of(IRI));
        verify(nkdSparqlClient, never()).fetchPublishedOntologyRaw(any());
    }
}
