package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.models.OntologyCreateModel;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.vocabulary.RDFS;

import java.util.Arrays;
import java.util.List;

import static com.dia.ismdtoolbackend.testsupport.fixtures.Texts.description;
import static com.dia.ismdtoolbackend.testsupport.fixtures.Texts.name;

/** Entities, models and RDF shared by the ontology operation tests. */
final class OntologyTestData {

    static final Long ONTOLOGY_ID = 1L;
    static final String SLUG = "test-ontology";
    static final String NAMESPACE = "http://example.org/";
    static final String GRAPH = NAMESPACE + SLUG;
    static final String USER_ID = "user123";

    private OntologyTestData() {
    }

    /** The unpublished draft vocabulary at {@link #GRAPH}, owned by {@link #USER_ID}. */
    static OntologyMetadataEntity ontologyEntity() {
        OntologyMetadataEntity entity = ontologyEntity(ONTOLOGY_ID, GRAPH);
        entity.setSlug(SLUG);
        entity.setUserId(USER_ID);
        entity.setIsPublished(false);
        return entity;
    }

    static OntologyMetadataEntity ontologyEntity(Long id, String graphName) {
        OntologyMetadataEntity entity = new OntologyMetadataEntity();
        entity.setId(id);
        entity.setGraphName(graphName);
        return entity;
    }

    static ConceptMetadataEntity concept(String conceptIri, String conceptName) {
        ConceptMetadataEntity entity = new ConceptMetadataEntity();
        entity.setConceptIri(conceptIri);
        entity.setConceptName(conceptName);
        entity.setGraphName(GRAPH);
        return entity;
    }

    static ConceptMetadataEntity conceptWithSlug(String conceptIri, String slug) {
        ConceptMetadataEntity entity = new ConceptMetadataEntity();
        entity.setConceptIri(conceptIri);
        entity.setSlug(slug);
        return entity;
    }

    static CommentEntity comment(OntologyMetadataEntity ontology, String text) {
        CommentEntity comment = new CommentEntity();
        comment.setOntologyMetadata(ontology);
        comment.setComment(text);
        return comment;
    }

    /** A valid create request: Czech name {@link #SLUG} and a Czech description in {@link #NAMESPACE}. */
    static OntologyCreateModel createModel() {
        OntologyCreateModel model = new OntologyCreateModel();
        model.setNameModel(name("cs", SLUG));
        model.setNamespace(NAMESPACE);
        model.setDescriptionModel(description("cs", "Test description"));
        return model;
    }

    /** A graph with one triple, so emptiness checks pass. */
    static Model nonEmptyModel() {
        Model model = ModelFactory.createDefaultModel();
        model.add(model.createResource(GRAPH), RDFS.label, "x");
        return model;
    }

    static OntologyDetailModel detailWithConcepts(String... conceptIris) {
        List<OntologyDetailModel.ConceptDetailModel> concepts = Arrays.stream(conceptIris)
                .map(iri -> OntologyDetailModel.ConceptDetailModel.builder().iri(iri).build())
                .toList();
        return OntologyDetailModel.builder().iri(GRAPH).concepts(concepts).build();
    }
}
