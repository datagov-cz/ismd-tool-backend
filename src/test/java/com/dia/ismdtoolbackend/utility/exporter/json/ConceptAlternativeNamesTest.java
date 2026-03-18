package com.dia.ismdtoolbackend.utility.exporter.json;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.utility.exporter.RdfTestModelFactory.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConceptProcessor - Alternative Names (altLabel)")
class ConceptAlternativeNamesTest {

    private ConceptProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ConceptProcessor();
    }

    @Test
    @DisplayName("Single skos:altLabel produces alternativní-název array")
    void singleAltLabel() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "osoba", "Osoba");
        concept.addProperty(SKOS.altLabel, model.createLiteral("Fyzická osoba", "cs"));
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertTrue(result.containsKey(ALTERNATIVNI_NAZEV));
        @SuppressWarnings("unchecked")
        Map<String, Object> altNames = (Map<String, Object>) result.get(ALTERNATIVNI_NAZEV);
        @SuppressWarnings("unchecked")
        List<Object> csValues = (List<Object>) altNames.get("cs");
        assertNotNull(csValues);
        assertEquals(1, csValues.size());
        assertEquals("Fyzická osoba", csValues.get(0));
    }

    @Test
    @DisplayName("Multiple skos:altLabels accumulate in array")
    void multipleAltLabels() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "osoba", "Osoba");
        concept.addProperty(SKOS.altLabel, model.createLiteral("Fyzická osoba", "cs"));
        concept.addProperty(SKOS.altLabel, model.createLiteral("Člověk", "cs"));
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        Map<String, Object> altNames = (Map<String, Object>) result.get(ALTERNATIVNI_NAZEV);
        @SuppressWarnings("unchecked")
        List<Object> csValues = (List<Object>) altNames.get("cs");
        assertEquals(2, csValues.size());
    }

    @Test
    @DisplayName("Non-SKOS altLabel property is ignored (only skos:altLabel supported)")
    void nonSkosAltLabel_ignored() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "osoba", "Osoba");
        // Add alternativní-název using DEFAULT_NS — should NOT be picked up
        concept.addProperty(model.createProperty(DEFAULT_NS + ALTERNATIVNI_NAZEV),
                model.createLiteral("Alternativa", "cs"));
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertFalse(result.containsKey(ALTERNATIVNI_NAZEV),
                "Non-SKOS altLabel properties should be ignored");
    }

    @Test
    @DisplayName("No altLabel → field absent from result")
    void noAltLabel_fieldAbsent() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "osoba", "Osoba");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertFalse(result.containsKey(ALTERNATIVNI_NAZEV));
    }

    @Test
    @DisplayName("altLabel with multiple languages groups by language")
    void multiLanguageAltLabels() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "osoba", "Osoba");
        concept.addProperty(SKOS.altLabel, model.createLiteral("Fyzická osoba", "cs"));
        concept.addProperty(SKOS.altLabel, model.createLiteral("Natural person", "en"));
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        Map<String, Object> altNames = (Map<String, Object>) result.get(ALTERNATIVNI_NAZEV);
        assertNotNull(altNames.get("cs"));
        assertNotNull(altNames.get("en"));
    }
}
