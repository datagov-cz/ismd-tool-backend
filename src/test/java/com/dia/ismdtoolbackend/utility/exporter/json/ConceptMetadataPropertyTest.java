package com.dia.ismdtoolbackend.utility.exporter.json;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.utility.exporter.RdfTestModelFactory.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConceptProcessor - Metadata Properties (A104/L111)")
class ConceptMetadataPropertyTest {

    private ConceptProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ConceptProcessor();
    }

    @Test
    @DisplayName("je-ppdf boolean true is preserved")
    void jePpdf_true() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        addMetadataProperties(concept, model, true, null, null);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertTrue(result.containsKey(JE_PPDF));
        assertEquals(true, result.get(JE_PPDF));
    }

    @Test
    @DisplayName("je-ppdf boolean false is preserved")
    void jePpdf_false() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        addMetadataProperties(concept, model, false, null, null);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertTrue(result.containsKey(JE_PPDF));
        assertEquals(false, result.get(JE_PPDF));
    }

    @Test
    @DisplayName("Agenda property from A104 namespace")
    void agendaProperty() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        addMetadataProperties(concept, model, null, "A123 - Evidence obyvatel", null);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertTrue(result.containsKey(AGENDA));
        assertEquals("A123 - Evidence obyvatel", result.get(AGENDA));
    }

    @Test
    @DisplayName("AIS property from A104 namespace")
    void aisProperty() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        addMetadataProperties(concept, model, null, null, "ISEO");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertTrue(result.containsKey(AIS));
        assertEquals("ISEO", result.get(AIS));
    }

    @Test
    @DisplayName("AIS as resource URI")
    void aisAsResourceUri() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        concept.addProperty(model.createProperty(A104_NS + UDAJE_AIS),
                model.createResource("https://rpp-opendata.egon.gov.cz/ais/123"));
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertTrue(result.containsKey(AIS));
        assertEquals("https://rpp-opendata.egon.gov.cz/ais/123", result.get(AIS));
    }

    @Test
    @DisplayName("ustanovení-dokládající-neveřejnost-údaje from L111 namespace")
    void ustanoveniNeverejnost() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        addUstanoveniNeverejnost(concept, model, "https://zakon.example.org/sb/111-2009/par/5");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertTrue(result.containsKey(USTANOVENI_NEVEREJNOST));
        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) result.get(USTANOVENI_NEVEREJNOST);
        assertTrue(values.contains("https://zakon.example.org/sb/111-2009/par/5"));
    }

    @Test
    @DisplayName("No metadata properties → fields absent")
    void noMetadataProperties_fieldsAbsent() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "osoba", "Osoba");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertFalse(result.containsKey(JE_PPDF));
        assertFalse(result.containsKey(AGENDA));
        assertFalse(result.containsKey(AIS));
        assertFalse(result.containsKey(USTANOVENI_NEVEREJNOST));
    }

    @Test
    @DisplayName("All metadata properties set together")
    void allMetadataProperties() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        addMetadataProperties(concept, model, true, "A123", "ISEO");
        addUstanoveniNeverejnost(concept, model, "https://zakon.example.org/sb/111-2009/par/5");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertEquals(true, result.get(JE_PPDF));
        assertEquals("A123", result.get(AGENDA));
        assertEquals("ISEO", result.get(AIS));
        assertTrue(result.containsKey(USTANOVENI_NEVEREJNOST));
    }
}
