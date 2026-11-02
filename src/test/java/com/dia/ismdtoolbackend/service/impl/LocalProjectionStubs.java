package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.vocabulary.RDF;

import java.util.Optional;

import static org.mockito.Mockito.when;

/**
 * Stubs the read {@link LocalConceptProjectionReader} makes for one concept: metadata row, its graph,
 * the OFN transform and the extracted detail.
 */
final class LocalProjectionStubs {

    private LocalProjectionStubs() {
    }

    /** Returns the raw graph the stubs serve; it holds one statement, so it is not empty. */
    static Model givenLocalGraph(ConceptMetadataRepository conceptMetadataRepository,
                                 JenaTDB2Repository jenaTDB2Repository,
                                 OntologyDetailExtractor detailExtractor,
                                 String conceptIri, String graph, ConceptDetailModel local) {
        ConceptMetadataEntity metadata = new ConceptMetadataEntity();
        metadata.setConceptIri(conceptIri);
        metadata.setGraphName(graph);
        when(conceptMetadataRepository.findByConceptIri(conceptIri)).thenReturn(Optional.of(metadata));

        Model rawModel = ModelFactory.createDefaultModel();
        rawModel.add(rawModel.createResource(conceptIri), RDF.type,
                rawModel.createResource("http://www.w3.org/2002/07/owl#Class"));
        Model processedModel = ModelFactory.createDefaultModel();
        when(jenaTDB2Repository.fetchGraph(graph)).thenReturn(rawModel);
        when(detailExtractor.applyOFNTransformations(rawModel)).thenReturn(processedModel);
        when(detailExtractor.extractConceptDetail(processedModel, conceptIri)).thenReturn(local);
        return rawModel;
    }
}