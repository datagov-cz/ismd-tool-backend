package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.config.ExternalSparqlConfig;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.testsupport.cache.CacheSliceTest;
import com.dia.ismdtoolbackend.testsupport.cache.CacheTestSupport;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.sparql.HttpSparqlExecutor;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.mockito.ArgumentMatchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the {@code @Cacheable} behaviour on {@link NkdSparqlClient}'s NKD-published deviation
 * fetches: a repeat call for the same IRI does NOT re-hit the SPARQL executor, a negative result
 * ({@link Optional#empty()}) is cached, and a thrown failure is NOT cached.
 *
 * <p>The shared {@link HttpSparqlExecutor} bean is a mock, so the test exercises both proxies and
 * the cache without any HTTP.
 */
@CacheSliceTest({NkdSparqlClient.class, NkdPublishedResourceFetcher.class})
@Import(NkdPublishedResourceCacheTest.TestBeans.class)
class NkdPublishedResourceCacheTest {

    private static final String CONCEPT_IRI = "https://slovnik.gov.cz/datovy/osoby/pojem/osoba";
    private static final String ONTOLOGY_IRI = "https://slovnik.gov.cz/datovy/osoby";

    @Autowired
    private NkdSparqlClient client;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private HttpSparqlExecutor executorMock;

    @BeforeEach
    void setUp() {
        CacheTestSupport.clearAll(cacheManager);
        reset(executorMock);
        when(executorMock.isConfigured()).thenReturn(true);
    }

    @Test
    void fetchPublishedConceptWithScheme_isCached_secondCallSkipsExecutor() {
        when(executorMock.construct(anyString(), anyString())).thenReturn(Optional.of(conceptModel()));

        client.fetchPublishedConceptWithScheme(CONCEPT_IRI);
        client.fetchPublishedConceptWithScheme(CONCEPT_IRI);

        verify(executorMock, times(1)).construct(anyString(), anyString());
    }

    @Test
    void fetchPublishedConcept_sharesTheWithSchemeEntry() {
        when(executorMock.construct(anyString(), anyString())).thenReturn(Optional.of(conceptModel()));

        client.fetchPublishedConcept(CONCEPT_IRI);
        client.fetchPublishedConceptWithScheme(CONCEPT_IRI);

        // The client's projection is built on the fetcher's cached entry, not on a second CONSTRUCT.
        verify(executorMock, times(1)).construct(anyString(), anyString());
    }

    @Test
    void fetchPublishedOntologyRaw_isCached_secondCallSkipsExecutor() {
        when(executorMock.construct(anyString(), anyString())).thenReturn(Optional.of(conceptModel()));

        client.fetchPublishedOntologyRaw(ONTOLOGY_IRI);
        client.fetchPublishedOntologyRaw(ONTOLOGY_IRI);

        verify(executorMock, times(1)).construct(anyString(), anyString());
    }

    @Test
    void fetchPublishedOntology_sharesTheRawEntry() {
        when(executorMock.construct(anyString(), anyString())).thenReturn(Optional.of(conceptModel()));

        client.fetchPublishedOntology(ONTOLOGY_IRI);
        client.fetchPublishedOntologyRaw(ONTOLOGY_IRI);

        verify(executorMock, times(1)).construct(anyString(), anyString());
    }

    private static Model conceptModel() {
        Model model = ModelFactory.createDefaultModel();
        model.add(model.createResource(CONCEPT_IRI),
                model.createProperty("http://www.w3.org/2004/02/skos/core#prefLabel"),
                "Osoba");
        return model;
    }

    @Test
    void fetchPublishedConcept_isCached_secondCallSkipsExecutor() {
        Model model = ModelFactory.createDefaultModel();
        model.add(model.createResource(CONCEPT_IRI),
                model.createProperty("http://www.w3.org/2004/02/skos/core#prefLabel"),
                "Osoba");
        when(executorMock.construct(anyString(), anyString())).thenReturn(Optional.of(model));

        Optional<OntologyDetailModel.ConceptDetailModel> first = client.fetchPublishedConcept(CONCEPT_IRI);
        Optional<OntologyDetailModel.ConceptDetailModel> second = client.fetchPublishedConcept(CONCEPT_IRI);

        assertTrue(first.isPresent());
        assertTrue(second.isPresent());
        // One network construct despite two calls — the second served from cache.
        verify(executorMock, times(1)).construct(anyString(), eq(buildExpectedQuery()));
    }

    @Test
    void fetchPublishedConcept_negativeResultIsCached() {
        when(executorMock.construct(anyString(), anyString())).thenReturn(Optional.empty());

        Optional<OntologyDetailModel.ConceptDetailModel> first = client.fetchPublishedConcept(CONCEPT_IRI);
        Optional<OntologyDetailModel.ConceptDetailModel> second = client.fetchPublishedConcept(CONCEPT_IRI);

        assertTrue(first.isEmpty());
        assertTrue(second.isEmpty());
        // Optional.empty() ("not in NKD") is cached too — no second probe for a known-absent IRI.
        verify(executorMock, times(1)).construct(anyString(), anyString());
    }

    @Test
    void fetchPublishedConcept_failureIsNotCached() {
        when(executorMock.construct(anyString(), anyString()))
                .thenThrow(new RuntimeException("NKD down"))
                .thenReturn(Optional.empty());

        try {
            client.fetchPublishedConcept(CONCEPT_IRI);
        } catch (RuntimeException ignored) {
            // expected on the first call
        }
        // Second call must re-invoke the executor — a thrown failure is never cached.
        Optional<OntologyDetailModel.ConceptDetailModel> second = client.fetchPublishedConcept(CONCEPT_IRI);

        assertTrue(second.isEmpty());
        verify(executorMock, times(2)).construct(anyString(), anyString());
    }

    @Test
    void cacheRegistered_withTtlAndBound() {
        CacheTestSupport.assertBoundedWithTtl(cacheManager, NkdSparqlClient.PUBLISHED_RESOURCE_CACHE);
    }

    private static String buildExpectedQuery() {
        return com.dia.ismdtoolbackend.query.NKDSPARQLConstructQuery.buildConstructQuery(CONCEPT_IRI);
    }

    @TestConfiguration
    static class TestBeans {
        @Bean(ExternalSparqlConfig.NKD_SPARQL_EXECUTOR)
        HttpSparqlExecutor nkdSparqlExecutor() {
            return mock(HttpSparqlExecutor.class);
        }

        @Bean
        OntologyDetailExtractor ontologyDetailExtractor() {
            // Mocked: the cache test only cares that the SPARQL executor is hit once, not what
            // the extractor produces. A real extractor would drag in ConceptMetadataRepository
            // and run the OFN transform — irrelevant here.
            OntologyDetailExtractor extractor = mock(OntologyDetailExtractor.class);
            when(extractor.applyOFNTransformationsForNkd(ArgumentMatchers.any()))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(extractor.extractConceptDetail(ArgumentMatchers.any(), anyString(),
                    ArgumentMatchers.any()))
                    .thenReturn(mock(OntologyDetailModel.ConceptDetailModel.class));
            return extractor;
        }
    }
}
