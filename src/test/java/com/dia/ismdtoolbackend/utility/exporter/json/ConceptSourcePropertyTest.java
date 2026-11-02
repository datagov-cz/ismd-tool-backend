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

@DisplayName("ConceptJsonMapper - Source Properties (ustanovení, exactMatch, non-legislative)")
class ConceptSourcePropertyTest {

    private ConceptJsonMapper processor;

    @BeforeEach
    void setUp() {
        processor = new ConceptJsonMapper();
    }

    @Test
    @DisplayName("exactMatch produces ekvivalentní-pojem array")
    void exactMatch_producesArray() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "osoba", "Osoba");
        addExactMatch(concept, model, "http://eurovoc.europa.eu/100157");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertThat(result).containsKey(EKVIVALENTNI_POJEM);
        @SuppressWarnings("unchecked")
        List<String> matches = (List<String>) result.get(EKVIVALENTNI_POJEM);
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0)).isEqualTo("http://eurovoc.europa.eu/100157");
    }

    @Test
    @DisplayName("Multiple exactMatch values accumulate")
    void multipleExactMatches() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "osoba", "Osoba");
        addExactMatch(concept, model, "http://eurovoc.europa.eu/100157");
        addExactMatch(concept, model, "http://example.org/other");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        List<String> matches = (List<String>) result.get(EKVIVALENTNI_POJEM);
        assertThat(matches).hasSize(2);
    }

    @Test
    @DisplayName("definující-ustanovení produces resource array")
    void definujiciUstanoveni() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "osoba", "Osoba");
        Resource law = model.createResource("https://zakon.example.org/sb/89-2012/par/23");
        addSourceProperty(concept, model, DEFINUJICI_USTANOVENI, law);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertThat(result).containsKey(DEFINUJICI_USTANOVENI_PRAVNIHO_PREDPISU);
        @SuppressWarnings("unchecked")
        List<String> sources = (List<String>) result.get(DEFINUJICI_USTANOVENI_PRAVNIHO_PREDPISU);
        assertThat(sources.get(0)).isEqualTo("https://zakon.example.org/sb/89-2012/par/23");
    }

    @Test
    @DisplayName("související-ustanovení produces resource array")
    void souvisejiciUstanoveni() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "osoba", "Osoba");
        Resource law = model.createResource("https://zakon.example.org/sb/89-2012/par/24");
        addSourceProperty(concept, model, SOUVISEJICI_USTANOVENI, law);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertThat(result).containsKey(SOUVISEJICI_USTANOVENI_PRAVNIHO_PREDPISU);
    }

    @Test
    @DisplayName("Non-legislative source with digital document object")
    void nonLegislativeSource_withDigitalDoc() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "osoba", "Osoba");
        Resource doc = addDigitalDocument(model, TEST_POJEM_NS + "doc1", "Dokument A", "https://example.org/doc.pdf");

        concept.addProperty(
                model.createProperty(TEST_POJEM_NS + DEFINUJICI_NELEGISLATIVNI_ZDROJ),
                doc);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) result.get(DEFINUJICI_NELEGISLATIVNI_ZDROJ);
        assertThat(sources).as("source linked under any namespace is found by its local name").isNotNull();
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).get("iri")).isEqualTo(TEST_POJEM_NS + "doc1");
    }

    @Test
    @DisplayName("Digital document object has correct structure")
    void digitalDocumentStructure() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "test-dd", "TestDD");
        Resource doc = addDigitalDocument(model, "https://example.org/doc/1",
                "Testovací dokument", "Popis dokumentu", "https://example.org/files/doc.pdf");

        concept.addProperty(
                model.createProperty(TEST_POJEM_NS + DEFINUJICI_NELEGISLATIVNI_ZDROJ),
                doc);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertThat(result.get(DEFINUJICI_NELEGISLATIVNI_ZDROJ)).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) result.get(DEFINUJICI_NELEGISLATIVNI_ZDROJ);
        Map<String, Object> docObj = sources.get(0);
        assertThat(docObj.get("iri")).isEqualTo("https://example.org/doc/1");
        assertThat(docObj.get("typ")).isEqualTo("Digitální objekt");
        @SuppressWarnings("unchecked")
        Map<String, Object> title = (Map<String, Object>) docObj.get(NAZEV);
        assertThat(title.get("cs")).isEqualTo("Testovací dokument");
        @SuppressWarnings("unchecked")
        Map<String, Object> description = (Map<String, Object>) docObj.get(POPIS);
        assertThat(description).isNotNull();
        assertThat(description.get("cs")).isEqualTo("Popis dokumentu");
        assertThat(docObj.get("url")).isEqualTo("https://example.org/files/doc.pdf");
    }

    @Test
    @DisplayName("No exactMatch → field absent")
    void noExactMatch_fieldAbsent() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "osoba", "Osoba");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertThat(result).doesNotContainKey(EKVIVALENTNI_POJEM);
    }

    @Test
    @DisplayName("No source properties → fields absent")
    void noSourceProperties_fieldsAbsent() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "osoba", "Osoba");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertThat(result).doesNotContainKey(DEFINUJICI_USTANOVENI_PRAVNIHO_PREDPISU);
        assertThat(result).doesNotContainKey(SOUVISEJICI_USTANOVENI_PRAVNIHO_PREDPISU);
    }
}
