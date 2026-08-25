package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.config.CacheConfig;
import com.dia.ismdtoolbackend.controller.dto.MinimalConceptDto;
import com.dia.ismdtoolbackend.service.NkdDetailService;
import com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder;
import com.dia.ismdtoolbackend.utility.exporter.json.JsonExporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the NKD concept-list cache.
 *
 * <p>{@code listOntologyConcepts} replaced a call chain that ran through the {@code @Cacheable}
 * {@code fetchPublishedOntology} with a direct {@code executeSelect}, which is NOT cached. The query
 * got cheaper but every warm request went to NKD live — measured at ~2ms → ~1s on a real endpoint.
 * These tests fail if that caching is lost again.
 */
@SpringBootTest(classes = {CacheConfig.class, NkdDetailServiceImpl.class})
@Import(NkdConceptListCacheTest.TestBeans.class)
class NkdConceptListCacheTest {

    private static final String ONTOLOGY_IRI = "https://slovnik.gov.cz/datovy/osoby";
    private static final String OTHER_IRI = "https://slovnik.gov.cz/datovy/adresy";

    @Autowired
    private NkdDetailService service;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private NkdSparqlClient clientMock;

    @BeforeEach
    void setUp() {
        Objects.requireNonNull(cacheManager.getCache(NkdSparqlClient.PUBLISHED_RESOURCE_CACHE)).clear();
        reset(clientMock);
        when(clientMock.isEndpointConfigured()).thenReturn(true);
        when(clientMock.executeSelect(anyString())).thenReturn(List.of(
                Map.of("concept", ONTOLOGY_IRI + "/pojem/osoba",
                        "label", "Osoba",
                        "roleTrida", "http://www.w3.org/2002/07/owl#Class")));
    }

    @Test
    void listOntologyConcepts_isCached_secondCallSkipsSparql() {
        List<MinimalConceptDto> first = service.listOntologyConcepts(ONTOLOGY_IRI);
        List<MinimalConceptDto> second = service.listOntologyConcepts(ONTOLOGY_IRI);

        assertEquals(1, first.size());
        assertEquals(1, second.size());
        assertEquals(first.get(0).getIri(), second.get(0).getIri());
        // One SPARQL round-trip despite two calls — this is the whole point of the fix.
        verify(clientMock, times(1)).executeSelect(anyString());
    }

    @Test
    void listOntologyConcepts_keyedPerOntology_differentIriMisses() {
        service.listOntologyConcepts(ONTOLOGY_IRI);
        service.listOntologyConcepts(OTHER_IRI);

        // A different ontology must not be served another's cached list.
        verify(clientMock, times(2)).executeSelect(anyString());
    }

    @Test
    void listOntologyConcepts_cachedUnderItsOwnKeyPrefix() {
        service.listOntologyConcepts(ONTOLOGY_IRI);

        // Shares nkdPublishedResource with the client's concept:/ontology: keys, so the prefix is
        // what keeps them from colliding.
        assertNotNull(Objects.requireNonNull(cacheManager.getCache(NkdSparqlClient.PUBLISHED_RESOURCE_CACHE))
                        .get("conceptList:" + ONTOLOGY_IRI),
                "concept list must be cached under the conceptList: prefix");
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        NkdSparqlClient nkdSparqlClient() {
            return mock(NkdSparqlClient.class);
        }

        @Bean
        JsonExporter jsonExporter() {
            return mock(JsonExporter.class);
        }

        @Bean
        RppSnapshotHolder rppSnapshotHolder() {
            return mock(RppSnapshotHolder.class);
        }

        @Bean
        ReferencedConceptsEnricher referencedConceptsEnricher() {
            return mock(ReferencedConceptsEnricher.class);
        }
    }
}
