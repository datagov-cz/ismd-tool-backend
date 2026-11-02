package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import com.dia.ismdtoolbackend.testsupport.fixtures.ConceptModels;
import com.dia.ismdtoolbackend.testsupport.fixtures.EditModels;
import com.dia.ismdtoolbackend.testsupport.fixtures.Texts;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.vocabulary.RDFS;

/** The concept, vocabulary and models shared by the concept delegate tests. */
final class ConceptServiceTestData {

    static final Long CONCEPT_ID = 1L;
    static final String CONCEPT_IRI = "http://example.org/pojem/test-concept";
    static final String GRAPH_NAME = "http://example.org/test-ontology";
    static final String USER_ID = "user123";
    static final String CONCEPT_NAME = "Test Concept";
    static final String SLUG = "test-slug";

    private ConceptServiceTestData() {
    }

    /** An unpublished TRIDA concept owned by {@link #USER_ID} in {@link #GRAPH_NAME}. */
    static ConceptMetadataEntity concept() {
        ConceptMetadataEntity entity = new ConceptMetadataEntity();
        entity.setId(CONCEPT_ID);
        entity.setConceptIri(CONCEPT_IRI);
        entity.setConceptName(CONCEPT_NAME);
        entity.setConceptType(ConceptType.TRIDA);
        entity.setGraphName(GRAPH_NAME);
        entity.setUserId(USER_ID);
        entity.setIsPublished(false);
        return entity;
    }

    static OntologyMetadataEntity ontology() {
        OntologyMetadataEntity ontology = new OntologyMetadataEntity();
        ontology.setId(1L);
        ontology.setGraphName(GRAPH_NAME);
        return ontology;
    }

    /** A valid TRIDA create model in {@link #GRAPH_NAME}. */
    static ClassConceptModel createModel() {
        return ConceptModels.trida(CONCEPT_NAME, m -> {
            m.setOntologyGraphName(GRAPH_NAME);
            m.setNamespace(GRAPH_NAME);
        });
    }

    /** A TRIDA edit model that sets only the Czech name. */
    static ClassConceptEditModel editModel() {
        return EditModels.trida(m -> m.setNameModel(Texts.name("cs", CONCEPT_NAME)));
    }

    /** A graph holding the concept with one label and no links. */
    static Model graphWithConcept() {
        Model model = ModelFactory.createDefaultModel();
        model.add(model.createResource(CONCEPT_IRI), RDFS.label, "Test");
        return model;
    }
}
