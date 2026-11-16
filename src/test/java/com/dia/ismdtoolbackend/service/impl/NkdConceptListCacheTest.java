package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.MinimalConceptDto;
import com.dia.ismdtoolbackend.service.NkdDetailService;
import com.dia.ismdtoolbackend.service.nkod.NkodCodelistService;
import com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder;
import com.dia.ismdtoolbackend.testsupport.cache.CacheSliceTest;
import com.dia.ismdtoolbackend.testsupport.cache.CacheTestSupport;
import com.dia.ismdtoolbackend.utility.exporter.json.JsonExporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
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
@CacheSliceTest({NkdDetailServiceImpl.class, NkdRequestGuard.class,
        NkdConceptListReader.class, NkdOntologyListReader.class, NkdCatalogTotalsReader.class})
class NkdConceptListCacheTest {

    private static final String ONTOLOGY_IRI = "https://slovnik.gov.cz/datovy/osoby";
    private static final String OTHER_IRI = "https://slovnik.gov.cz/datovy/adresy";

    @Autowired
    private NkdDetailService service;

    @Autowired
    private CacheManager cacheManager;

    @MockitoBean
    private NkdSparqlClient clientMock;

    @MockitoBean
    private JsonExporter jsonExporter;

    @MockitoBean
    private RppSnapshotHolder rppSnapshotHolder;

    @MockitoBean
    private NkodCodelistService nkodCodelistService;

    @MockitoBean
    private ReferencedConceptsEnricher referencedConceptsEnricher;

    @BeforeEach
    void setUp() {
        CacheTestSupport.clearAll(cacheManager);
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

        assertThat(first).hasSize(1);
        assertThat(second).extracting(MinimalConceptDto::getIri).containsExactly(first.get(0).getIri());
        // One SPARQL round-trip despite two calls — this is the whole point of the fix.
        verify(clientMock, times(1)).executeSelect(anyString());
    }

    @Test
    void listOntologyConcepts_keyedPerOntology_differentIriMisses() {
        // Each ontology's query answers with that ontology's own concept.
        when(clientMock.executeSelect(anyString())).thenAnswer(inv -> {
            String query = inv.getArgument(0);
            String ontology = query.contains(OTHER_IRI) ? OTHER_IRI : ONTOLOGY_IRI;
            return List.of(Map.of("concept", ontology + "/pojem/clen", "label", ontology));
        });

        service.listOntologyConcepts(ONTOLOGY_IRI);
        service.listOntologyConcepts(OTHER_IRI);
        List<MinimalConceptDto> first = service.listOntologyConcepts(ONTOLOGY_IRI);
        List<MinimalConceptDto> other = service.listOntologyConcepts(OTHER_IRI);

        // A different ontology must not be served another's cached list.
        assertThat(first).extracting(MinimalConceptDto::getIri).containsExactly(ONTOLOGY_IRI + "/pojem/clen");
        assertThat(other).extracting(MinimalConceptDto::getIri).containsExactly(OTHER_IRI + "/pojem/clen");
        verify(clientMock, times(2)).executeSelect(anyString());
    }

    @Test
    void listOntologyConcepts_cachedUnderItsOwnKeyPrefix() {
        service.listOntologyConcepts(ONTOLOGY_IRI);

        // Shares nkdPublishedResource with the client's concept:/ontology: keys, so the prefix is
        // what keeps them from colliding.
        assertThat(cacheManager.getCache(NkdSparqlClient.PUBLISHED_RESOURCE_CACHE)).isNotNull()
                .extracting(cache -> cache.get("conceptList:" + ONTOLOGY_IRI))
                .as("concept list must be cached under the conceptList: prefix")
                .isNotNull();
    }
}
