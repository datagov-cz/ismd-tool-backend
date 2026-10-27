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
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConceptJsonMapper - Integration Tests")
class ConceptJsonMapperIntegrationTest {

    private ConceptJsonMapper processor;

    @BeforeEach
    void setUp() {
        processor = new ConceptJsonMapper();
    }

    @Test
    @DisplayName("Full vocabulary with multiple concept types processes correctly")
    void fullVocabulary_multipleTypes() {
        OntModel model = createDefaultModel();
        Resource osoba = addOwlClass(model, "osoba", "Osoba");
        addDefinition(osoba, model, "Fyzická osoba", "cs");
        Resource adresa = addOwlClass(model, "adresa", "Adresa");
        Resource maAdresu = addObjectProperty(model, "má-adresu", "má adresu", osoba, adresa);
        Resource jmeno = addDatatypeProperty(model, "jméno", "jméno", osoba, "string");
        ModelStructure structure = createModelStructure(model);

        ConceptData data = processor.processAllConcepts(model, structure);

        assertThat(data).isNotNull();
        assertThat(data.getTotalConceptCount())
                .as("Should have at least 4 concepts: 2 classes + 1 relation + 1 property")
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("processConceptByIri returns full property set")
    void processConceptByIri_fullPropertySet() {
        OntModel model = createDefaultModel();
        Resource parent = addOwlClass(model, "subjekt", "Subjekt");
        Resource osoba = addOwlClass(model, "osoba", "Osoba");
        addDefinition(osoba, model, "Fyzická nebo právnická osoba", "cs");
        addDescription(osoba, model, "Detailní popis osoby", "cs");
        addExactMatch(osoba, model, "http://eurovoc.europa.eu/100157");
        addSubClassRelationship(osoba, parent);
        addSkosBroader(osoba, parent);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, osoba.getURI());

        assertThat(result.get("iri")).isEqualTo(osoba.getURI());
        assertThat(result.get("typ")).isNotNull();
        assertThat(result.get(NAZEV)).isNotNull();
        assertThat(result.get(DEFINICE)).isNotNull();
        assertThat(result.get(POPIS)).isNotNull();
        assertThat(result.get(EKVIVALENTNI_POJEM)).isNotNull();
        assertThat(result.get(NADRAZENA_TRIDA)).isNotNull();
        assertThat(result.get("nadřazený-pojem")).isNotNull();
    }

    @Test
    @DisplayName("processAllConcepts maps each concept as processConceptByIri does")
    void allConcepts_matchesIndividual() {
        OntModel model = createDefaultModel();
        addOwlClass(model, "osoba", "Osoba");
        addOwlClass(model, "adresa", "Adresa");
        addObjectProperty(model, "ma-adresu", "má adresu", null, null);
        ModelStructure structure = createModelStructure(model);

        ConceptData data = processor.processAllConcepts(model, structure);
        assertThat(data.getTotalConceptCount()).isEqualTo(3);
        assertThat(data.getConcepts()).hasSize(3);
        for (Map<String, Object> concept : data.getConcepts()) {
            String iri = (String) concept.get("iri");
            assertThat(processor.processConceptByIri(model, structure, iri)).isEqualTo(concept);
        }
    }

    @Test
    @DisplayName("Concept with all governance and metadata properties")
    void fullGovernanceAndMetadata() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Testovací údaj");
        addGovernanceProperties(concept, model, "otevřená data", "automaticky", "identifikační");
        addMetadataProperties(concept, model, true, "A123", "ISEO");
        addUstanoveniNeverejnost(concept, model, "https://zakon.example.org/sb/111-2009/par/5");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertThat(result.get(ZPUSOBY_SDILENI_ALT)).isNotNull();
        assertThat(result.get(ZPUSOB_ZISKANI_ALT)).isNotNull();
        assertThat(result.get(TYP_OBSAHU_ALT)).isNotNull();
        assertThat(result.get(JE_PPDF)).isEqualTo(true);
        assertThat(result.get(AGENDA)).isNotNull();
        assertThat(result.get(AIS)).isNotNull();
        assertThat(result.get(USTANOVENI_NEVEREJNOST)).isNotNull();
    }

    @Test
    @DisplayName("IRI is always present in result")
    void iriAlwaysPresent() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "minimal", "Minimální");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertThat(result.get("iri")).isEqualTo(concept.getURI());
    }

    @Test
    @DisplayName("Type list always contains Pojem and Koncept")
    void typeAlwaysContainsPojemAndKoncept() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "test", "Test");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) result.get("typ");
        assertThat(types).contains(POJEM_JSON_LD);
        assertThat(types).contains("Koncept");
    }

    @Test
    @DisplayName("processAllConcepts handles mixed concept quality gracefully")
    void mixedConceptQuality() {
        OntModel model = createDefaultModel();
        // Well-formed concept
        addOwlClass(model, "osoba", "Osoba");
        // Minimal concept (just skos:Concept + pojem)
        addSkosConcept(model, "minimal", "Minimal");
        ModelStructure structure = createModelStructure(model);

        ConceptData data = processor.processAllConcepts(model, structure);

        assertThat(data).isNotNull();
        assertThat(data.getTotalConceptCount()).isGreaterThanOrEqualTo(2);
    }
}
