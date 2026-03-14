package com.dia.ismdtoolbackend.utility.exporter.json;

import org.apache.jena.ontology.OntModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.dia.ismdtoolbackend.exception.ModelProcessingException;

import static com.dia.ismdtoolbackend.utility.exporter.RdfTestModelFactory.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConceptProcessor - Null/Empty Input Safety")
class ConceptProcessorNullSafetyTest {

    private ConceptProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ConceptProcessor();
    }

    @Test
    @DisplayName("processAllConcepts throws on null OntModel")
    void processAllConcepts_nullModel_throws() {
        OntModel model = createDefaultModel();
        ModelStructure structure = createModelStructure(model);
        assertThrows(ModelProcessingException.class,
                () -> processor.processAllConcepts(null, structure));
    }

    @Test
    @DisplayName("processAllConcepts throws on null ModelStructure")
    void processAllConcepts_nullStructure_throws() {
        OntModel model = createDefaultModel();
        assertThrows(ModelProcessingException.class,
                () -> processor.processAllConcepts(model, null));
    }

    @Test
    @DisplayName("processConceptByIri throws on null OntModel")
    void processConceptByIri_nullModel_throws() {
        OntModel model = createDefaultModel();
        ModelStructure structure = createModelStructure(model);
        assertThrows(ModelProcessingException.class,
                () -> processor.processConceptByIri(null, structure, "http://example.org/x"));
    }

    @Test
    @DisplayName("processConceptByIri throws on null ModelStructure")
    void processConceptByIri_nullStructure_throws() {
        OntModel model = createDefaultModel();
        assertThrows(ModelProcessingException.class,
                () -> processor.processConceptByIri(model, null, "http://example.org/x"));
    }

    @Test
    @DisplayName("processConceptByIri throws on null IRI")
    void processConceptByIri_nullIri_throws() {
        OntModel model = createDefaultModel();
        ModelStructure structure = createModelStructure(model);
        assertThrows(ModelProcessingException.class,
                () -> processor.processConceptByIri(model, structure, null));
    }

    @Test
    @DisplayName("processConceptByIri throws on empty IRI")
    void processConceptByIri_emptyIri_throws() {
        OntModel model = createDefaultModel();
        ModelStructure structure = createModelStructure(model);
        assertThrows(ModelProcessingException.class,
                () -> processor.processConceptByIri(model, structure, ""));
    }

    @Test
    @DisplayName("processConceptByIri throws for non-concept IRI")
    void processConceptByIri_nonConceptIri_throws() {
        OntModel model = createDefaultModel();
        ModelStructure structure = createModelStructure(model);
        assertThrows(ModelProcessingException.class,
                () -> processor.processConceptByIri(model, structure, "http://example.org/not-a-concept"));
    }

    @Test
    @DisplayName("processAllConcepts returns empty for model with no concepts")
    void processAllConcepts_emptyModel_returnsEmpty() {
        OntModel model = createDefaultModel();
        ModelStructure structure = createModelStructure(model);
        ConceptData data = processor.processAllConcepts(model, structure);
        assertNotNull(data);
        assertEquals(0, data.getTotalConceptCount());
        assertTrue(data.getConcepts().isEmpty());
    }
}
