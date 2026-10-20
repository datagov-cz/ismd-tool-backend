package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.config.CacheConfig;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies through the real cache proxy that the canonical local projection is read once per concept. */
@SpringBootTest(classes = {CacheConfig.class, LocalConceptProjectionReader.class})
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
        Objects.requireNonNull(cacheManager.getCache(
                WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE)).clear();

        ConceptMetadataEntity metadata = new ConceptMetadataEntity();
        metadata.setConceptIri(IRI);
        metadata.setGraphName(GRAPH);
        when(conceptMetadataRepository.findByConceptIri(IRI)).thenReturn(Optional.of(metadata));

        rawModel = ModelFactory.createDefaultModel();
        rawModel.add(rawModel.createResource(IRI), RDF.type,
                rawModel.createResource("http://www.w3.org/2002/07/owl#Class"));
        Model processedModel = ModelFactory.createDefaultModel();
        when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(rawModel);
        when(detailExtractor.applyOFNTransformations(rawModel)).thenReturn(processedModel);
        local = ConceptDetailModel.builder().iri(IRI).build();
        when(detailExtractor.extractConceptDetail(processedModel, IRI)).thenReturn(local);
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
