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

@DisplayName("ConceptJsonMapper - Metadata Properties (A104/L111)")
class ConceptMetadataPropertyTest {

    private ConceptJsonMapper processor;

    @BeforeEach
    void setUp() {
        processor = new ConceptJsonMapper();
    }

    @Test
    @DisplayName("je-ppdf boolean true is preserved")
    void jePpdf_true() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        addMetadataProperties(concept, model, true, null, null);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertThat(result).containsKey(JE_PPDF);
        assertThat(result.get(JE_PPDF)).isEqualTo(true);
    }

    @Test
    @DisplayName("je-ppdf boolean false is preserved")
    void jePpdf_false() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        addMetadataProperties(concept, model, false, null, null);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertThat(result).containsKey(JE_PPDF);
        assertThat(result.get(JE_PPDF)).isEqualTo(false);
    }

    @Test
    @DisplayName("Agenda property from A104 namespace")
    void agendaProperty() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        addMetadataProperties(concept, model, null, "A123 - Evidence obyvatel", null);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertThat(result).containsKey(AGENDA);
        assertThat(result.get(AGENDA)).isEqualTo("A123 - Evidence obyvatel");
    }

    @Test
    @DisplayName("AIS property from A104 namespace")
    void aisProperty() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        addMetadataProperties(concept, model, null, null, "ISEO");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertThat(result).containsKey(AIS);
        assertThat(result.get(AIS)).isEqualTo("ISEO");
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

        assertThat(result).containsKey(AIS);
        assertThat(result.get(AIS)).isEqualTo("https://rpp-opendata.egon.gov.cz/ais/123");
    }

    @Test
    @DisplayName("ustanovení-dokládající-neveřejnost-údaje from L111 namespace")
    void ustanoveniNeverejnost() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        addUstanoveniNeverejnost(concept, model, "https://zakon.example.org/sb/111-2009/par/5");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertThat(result).containsKey(USTANOVENI_NEVEREJNOST);
        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) result.get(USTANOVENI_NEVEREJNOST);
        assertThat(values).contains("https://zakon.example.org/sb/111-2009/par/5");
    }

    @Test
    @DisplayName("ustanovení read under the canonical OFN property, merged without duplicates")
    void ustanoveniNeverejnost_canonicalProperty() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        String shared = "https://zakon.example.org/sb/111-2009/par/5";
        String canonicalOnly = "https://zakon.example.org/sb/111-2009/par/6";
        addUstanoveniNeverejnost(concept, model, shared);
        org.apache.jena.rdf.model.Property canonical = model.createProperty(
                "https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/" + USTANOVENI_LONG);
        concept.addProperty(canonical, model.createResource(shared));
        concept.addProperty(canonical, model.createResource(canonicalOnly));
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertThat(result.get(USTANOVENI_NEVEREJNOST)).isEqualTo(List.of(shared, canonicalOnly));
    }

    @Test
    @DisplayName("No metadata properties → fields absent")
    void noMetadataProperties_fieldsAbsent() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "osoba", "Osoba");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertThat(result).doesNotContainKey(JE_PPDF);
        assertThat(result).doesNotContainKey(AGENDA);
        assertThat(result).doesNotContainKey(AIS);
        assertThat(result).doesNotContainKey(USTANOVENI_NEVEREJNOST);
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

        assertThat(result.get(JE_PPDF)).isEqualTo(true);
        assertThat(result.get(AGENDA)).isEqualTo("A123");
        assertThat(result.get(AIS)).isEqualTo("ISEO");
        assertThat(result).containsKey(USTANOVENI_NEVEREJNOST);
    }
}
