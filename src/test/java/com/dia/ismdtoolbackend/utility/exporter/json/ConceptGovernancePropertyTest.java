package com.dia.ismdtoolbackend.utility.exporter.json;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.utility.exporter.RdfTestModelFactory.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConceptProcessor - Governance Properties")
class ConceptGovernancePropertyTest {

    private ConceptProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ConceptProcessor();
    }

    @Test
    @DisplayName("způsoby-sdílení-údaje produces array")
    void zpusobySdileniUdaje_producesArray() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        addGovernanceProperties(concept, model, "publikace jako otevřená data", null, null);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertTrue(result.containsKey(ZPUSOBY_SDILENI_ALT));
        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) result.get(ZPUSOBY_SDILENI_ALT);
        assertFalse(values.isEmpty());
    }

    @Test
    @DisplayName("Semicolon-separated sharing methods are split into array")
    void semicolonSplitting() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        Property prop = model.createProperty(OFN_NAMESPACE + ZPUSOBY_SDILENI_UDAJE);
        concept.addProperty(prop, "publikace jako otevřená data; sdílení v PPDF");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) result.get(ZPUSOBY_SDILENI_ALT);
        assertEquals(2, values.size());
        assertTrue(values.contains("publikace jako otevřená data"));
        assertTrue(values.contains("sdílení v PPDF"));
    }

    @Test
    @DisplayName("Old fallback property name má-způsob-sdílení-údaje is ignored")
    void oldFallbackPropertyName_ignored() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        // Use the old fallback property name — should NOT be picked up
        Property prop = model.createProperty(OFN_NAMESPACE + ZPUSOB_SDILENI);
        concept.addProperty(prop, "otevřená data");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertFalse(result.containsKey(ZPUSOBY_SDILENI_ALT),
                "Old fallback property names should be ignored");
    }

    @Test
    @DisplayName("způsob-získání-údaje produces single value")
    void zpusobZiskani() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        addGovernanceProperties(concept, model, null, "automaticky", null);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertTrue(result.containsKey(ZPUSOB_ZISKANI_ALT));
        assertEquals("automaticky", result.get(ZPUSOB_ZISKANI_ALT));
    }

    @Test
    @DisplayName("typ-obsahu-údaje produces single value")
    void typObsahu() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        addGovernanceProperties(concept, model, null, null, "identifikační");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertTrue(result.containsKey(TYP_OBSAHU_ALT));
        assertEquals("identifikační", result.get(TYP_OBSAHU_ALT));
    }

    @Test
    @DisplayName("No governance properties → fields absent")
    void noGovernanceProperties_fieldsAbsent() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "osoba", "Osoba");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertFalse(result.containsKey(ZPUSOBY_SDILENI_ALT));
        assertFalse(result.containsKey(ZPUSOB_ZISKANI_ALT));
        assertFalse(result.containsKey(TYP_OBSAHU_ALT));
    }

    @Test
    @DisplayName("Sharing method value is trimmed")
    void sharingMethodTrimmed() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        Property prop = model.createProperty(OFN_NAMESPACE + ZPUSOBY_SDILENI_UDAJE);
        concept.addProperty(prop, "  otevřená data  ");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) result.get(ZPUSOBY_SDILENI_ALT);
        assertEquals("otevřená data", values.get(0));
    }

    @Test
    @DisplayName("Governance property with resource value uses URI")
    void governancePropertyResourceValue() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        Property prop = model.createProperty(OFN_NAMESPACE + ZPUSOBY_SDILENI_UDAJE);
        concept.addProperty(prop, model.createResource("https://example.org/sdileni/otevrena-data"));
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) result.get(ZPUSOBY_SDILENI_ALT);
        assertEquals("https://example.org/sdileni/otevrena-data", values.get(0));
    }
}
