package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.config.CacheConfig;
import com.dia.ismdtoolbackend.config.NkdConfig;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.sparql.HttpSparqlExecutor;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.mockito.ArgumentMatchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpClient;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the {@code @Cacheable} behaviour on {@link NkdSparqlClient}'s NKD-published deviation
 * fetches: a repeat call for the same IRI does NOT re-hit the SPARQL executor, a negative result
 * ({@link Optional#empty()}) is cached, and a thrown failure is NOT cached.
 *
 * <p>The private {@link HttpSparqlExecutor} is swapped for a mock via reflection on the AOP
 * target so the test exercises the proxy + cache without any HTTP.
 */
@SpringBootTest(classes = {CacheConfig.class, NkdSparqlClient.class})
@Import(NkdPublishedResourceCacheTest.TestBeans.class)
class NkdPublishedResourceCacheTest {

    private static final String CONCEPT_IRI = "https://slovnik.gov.cz/datovy/osoby/pojem/osoba";

    @Autowired
    private NkdSparqlClient client;

    @Autowired
    private CacheManager cacheManager;

    private HttpSparqlExecutor executorMock;

    @BeforeEach
    void setUp() {
        Objects.requireNonNull(cacheManager.getCache(NkdSparqlClient.PUBLISHED_RESOURCE_CACHE)).clear();
        executorMock = mock(HttpSparqlExecutor.class);
        when(executorMock.isConfigured()).thenReturn(true);
        // Replace the internally-built executor on the AOP target (not the proxy wrapper).
        Object target = AopProxyUtils.getSingletonTarget(client);
        ReflectionTestUtils.setField(Objects.requireNonNull(target), "executor", executorMock);
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
    void cacheRegistered_withExpectedName() {
        assertNotNull(cacheManager.getCache(NkdSparqlClient.PUBLISHED_RESOURCE_CACHE),
                "nkdPublishedResource cache must be registered");
    }

    private static String buildExpectedQuery() {
        return com.dia.ismdtoolbackend.query.NKDSPARQLConstructQuery.buildConstructQuery(CONCEPT_IRI);
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        NkdConfig nkdConfig() {
            NkdConfig cfg = new NkdConfig();
            cfg.getSparql().setEndpoint("http://localhost:9999/sparql");
            cfg.getSparql().setTimeout(2000);
            cfg.getSparql().setMaxConcurrentRequests(2);
            return cfg;
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

        @Bean
        HttpClient externalSparqlHttpClient() {
            return HttpClient.newHttpClient();
        }
    }
}
