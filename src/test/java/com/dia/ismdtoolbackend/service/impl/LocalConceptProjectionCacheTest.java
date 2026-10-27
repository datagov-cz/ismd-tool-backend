package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.testsupport.cache.CacheSliceTest;
import com.dia.ismdtoolbackend.testsupport.cache.CacheTestSupport;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Verifies through the real cache proxy that the canonical local projection is read once per concept. */
@CacheSliceTest(LocalConceptProjectionReader.class)
class LocalConceptProjectionCacheTest {

    private static final String IRI = "https://slovník.gov.cz/test/pojem/obec";
    private static final String GRAPH = "https://slovník.gov.cz/test";

    @Autowired private LocalConceptProjectionReader reader;
    @Autowired private CacheManager cacheManager;

    @MockitoBean private ConceptMetadataRepository conceptMetadataRepository;
    @MockitoBean private JenaTDB2Repository jenaTDB2Repository;
    @MockitoBean private OntologyDetailExtractor detailExtractor;

    private Model rawModel;
    private ConceptDetailModel local;

    @BeforeEach
    void setUp() {
        CacheTestSupport.clearAll(cacheManager);

        local = ConceptDetailModel.builder().iri(IRI).build();
        rawModel = LocalProjectionStubs.givenLocalGraph(conceptMetadataRepository, jenaTDB2Repository,
                detailExtractor, IRI, GRAPH, local);
    }

    @Test
    void canonicalLocalConcept_isCached_secondCallSkipsTheGraphRead() {
        assertThat(reader.canonicalLocalConcept(IRI)).isSameAs(local);
        assertThat(reader.canonicalLocalConcept(IRI)).isSameAs(local);

        verify(jenaTDB2Repository, times(1)).fetchGraph(GRAPH);
    }

    @Test
    void bothOverloads_shareOneEntry() {
        reader.canonicalLocalConcept(IRI, rawModel);
        reader.canonicalLocalConcept(IRI);

        verify(jenaTDB2Repository, times(0)).fetchGraph(any());
        verify(detailExtractor, times(1)).applyOFNTransformations(any());
    }
}
