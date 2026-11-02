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
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConceptJsonMapper - Governance Properties")
class ConceptGovernancePropertyTest {

    private ConceptJsonMapper processor;

    @BeforeEach
    void setUp() {
        processor = new ConceptJsonMapper();
    }

    @Test
    @DisplayName("způsoby-sdílení-údaje produces array")
    void zpusobySdileniUdaje_producesArray() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        addGovernanceProperties(concept, model, "publikace jako otevřená data", null, null);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertThat(result).containsKey(ZPUSOBY_SDILENI_ALT);
        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) result.get(ZPUSOBY_SDILENI_ALT);
        assertThat(values).isNotEmpty();
    }

    @Test
    @DisplayName("Semicolon-separated sharing methods are split into array")
    void semicolonSplitting() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        Property prop = model.createProperty(OFN_NAMESPACE + ZPUSOB_SDILENI);
        concept.addProperty(prop, "publikace jako otevřená data; sdílení v PPDF");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) result.get(ZPUSOBY_SDILENI_ALT);
        assertThat(values).hasSize(2);
        assertThat(values).contains("publikace jako otevřená data");
        assertThat(values).contains("sdílení v PPDF");
    }

    @Test
    @DisplayName("způsob-získání-údaje produces single value")
    void zpusobZiskani() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        addGovernanceProperties(concept, model, null, "automaticky", null);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertThat(result).containsKey(ZPUSOB_ZISKANI_ALT);
        assertThat(result.get(ZPUSOB_ZISKANI_ALT)).isEqualTo("automaticky");
    }

    @Test
    @DisplayName("typ-obsahu-údaje produces single value")
    void typObsahu() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        addGovernanceProperties(concept, model, null, null, "identifikační");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertThat(result).containsKey(TYP_OBSAHU_ALT);
        assertThat(result.get(TYP_OBSAHU_ALT)).isEqualTo("identifikační");
    }

    @Test
    @DisplayName("No governance properties → fields absent")
    void noGovernanceProperties_fieldsAbsent() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "osoba", "Osoba");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertThat(result).doesNotContainKey(ZPUSOBY_SDILENI_ALT);
        assertThat(result).doesNotContainKey(ZPUSOB_ZISKANI_ALT);
        assertThat(result).doesNotContainKey(TYP_OBSAHU_ALT);
    }

    @Test
    @DisplayName("Sharing method value is trimmed")
    void sharingMethodTrimmed() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        Property prop = model.createProperty(OFN_NAMESPACE + ZPUSOB_SDILENI);
        concept.addProperty(prop, "  otevřená data  ");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) result.get(ZPUSOBY_SDILENI_ALT);
        assertThat(values.get(0)).isEqualTo("otevřená data");
    }

    @Test
    @DisplayName("Governance property with resource value uses URI")
    void governancePropertyResourceValue() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        Property prop = model.createProperty(OFN_NAMESPACE + ZPUSOB_SDILENI);
        concept.addProperty(prop, model.createResource("https://example.org/sdileni/otevrena-data"));
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) result.get(ZPUSOBY_SDILENI_ALT);
        assertThat(values.get(0)).isEqualTo("https://example.org/sdileni/otevrena-data");
    }
}
