package com.dia.ismdtoolbackend.utility.exporter.json;

import org.apache.jena.ontology.OntModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.dia.ismdtoolbackend.exception.ModelProcessingException;

import static com.dia.ismdtoolbackend.utility.exporter.RdfTestModelFactory.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ConceptJsonMapper - Null/Empty Input Safety")
class ConceptJsonMapperNullSafetyTest {

    private ConceptJsonMapper processor;

    @BeforeEach
    void setUp() {
        processor = new ConceptJsonMapper();
    }

    @Test
    @DisplayName("processAllConcepts throws on null OntModel")
    void processAllConcepts_nullModel_throws() {
        OntModel model = createDefaultModel();
        ModelStructure structure = createModelStructure(model);
        assertThatThrownBy(() -> processor.processAllConcepts(null, structure))
                .isInstanceOf(ModelProcessingException.class);
    }

    @Test
    @DisplayName("processAllConcepts throws on null ModelStructure")
    void processAllConcepts_nullStructure_throws() {
        OntModel model = createDefaultModel();
        assertThatThrownBy(() -> processor.processAllConcepts(model, null))
                .isInstanceOf(ModelProcessingException.class);
    }

    @Test
    @DisplayName("processConceptByIri throws on null OntModel")
    void processConceptByIri_nullModel_throws() {
        OntModel model = createDefaultModel();
        ModelStructure structure = createModelStructure(model);
        assertThatThrownBy(() -> processor.processConceptByIri(null, structure, "http://example.org/x"))
                .isInstanceOf(ModelProcessingException.class);
    }

    @Test
    @DisplayName("processConceptByIri throws on null ModelStructure")
    void processConceptByIri_nullStructure_throws() {
        OntModel model = createDefaultModel();
        assertThatThrownBy(() -> processor.processConceptByIri(model, null, "http://example.org/x"))
                .isInstanceOf(ModelProcessingException.class);
    }

    @Test
    @DisplayName("processConceptByIri throws on null IRI")
    void processConceptByIri_nullIri_throws() {
        OntModel model = createDefaultModel();
        ModelStructure structure = createModelStructure(model);
        assertThatThrownBy(() -> processor.processConceptByIri(model, structure, null))
                .isInstanceOf(ModelProcessingException.class);
    }

    @Test
    @DisplayName("processConceptByIri throws on empty IRI")
    void processConceptByIri_emptyIri_throws() {
        OntModel model = createDefaultModel();
        ModelStructure structure = createModelStructure(model);
        assertThatThrownBy(() -> processor.processConceptByIri(model, structure, ""))
                .isInstanceOf(ModelProcessingException.class);
    }

    @Test
    @DisplayName("processConceptByIri throws for non-concept IRI")
    void processConceptByIri_nonConceptIri_throws() {
        OntModel model = createDefaultModel();
        ModelStructure structure = createModelStructure(model);
        assertThatThrownBy(() -> processor.processConceptByIri(model, structure, "http://example.org/not-a-concept"))
                .isInstanceOf(ModelProcessingException.class);
    }

    @Test
    @DisplayName("processAllConcepts returns empty for model with no concepts")
    void processAllConcepts_emptyModel_returnsEmpty() {
        OntModel model = createDefaultModel();
        ModelStructure structure = createModelStructure(model);
        ConceptData data = processor.processAllConcepts(model, structure);
        assertThat(data).isNotNull();
        assertThat(data.getTotalConceptCount()).isEqualTo(0);
        assertThat(data.getConcepts()).isEmpty();
    }
}
