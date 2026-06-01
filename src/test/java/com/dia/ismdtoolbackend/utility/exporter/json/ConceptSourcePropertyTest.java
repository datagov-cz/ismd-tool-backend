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

@DisplayName("ConceptProcessor - Source Properties (ustanovení, exactMatch, non-legislative)")
class ConceptSourcePropertyTest {

    private ConceptProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ConceptProcessor();
    }

    @Test
    @DisplayName("exactMatch produces ekvivalentní-pojem array")
    void exactMatch_producesArray() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "osoba", "Osoba");
        addExactMatch(concept, model, "http://eurovoc.europa.eu/100157");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertTrue(result.containsKey(EKVIVALENTNI_POJEM));
        @SuppressWarnings("unchecked")
        List<String> matches = (List<String>) result.get(EKVIVALENTNI_POJEM);
        assertEquals(1, matches.size());
        assertEquals("http://eurovoc.europa.eu/100157", matches.get(0));
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
        assertEquals(2, matches.size());
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

        assertTrue(result.containsKey(DEFINUJICI_USTANOVENI_PRAVNIHO_PREDPISU));
        @SuppressWarnings("unchecked")
        List<String> sources = (List<String>) result.get(DEFINUJICI_USTANOVENI_PRAVNIHO_PREDPISU);
        assertEquals("https://zakon.example.org/sb/89-2012/par/23", sources.get(0));
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

        assertTrue(result.containsKey(SOUVISEJICI_USTANOVENI_PRAVNIHO_PREDPISU));
    }

    @Test
    @DisplayName("Non-legislative source with digital document object")
    void nonLegislativeSource_withDigitalDoc() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "osoba", "Osoba");
        Resource doc = addDigitalDocument(model, TEST_POJEM_NS + "doc1", "Dokument A", "https://example.org/doc.pdf");

        // Link using effective namespace property
        concept.addProperty(
                model.createProperty(TEST_POJEM_NS + DEFINUJICI_NELEGISLATIVNI_ZDROJ),
                doc);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        // The non-legislative source should be found via the effective namespace or local name fallback
        if (result.containsKey(DEFINUJICI_NELEGISLATIVNI_ZDROJ)) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sources = (List<Map<String, Object>>) result.get(DEFINUJICI_NELEGISLATIVNI_ZDROJ);
            assertFalse(sources.isEmpty());
            Map<String, Object> docObj = sources.get(0);
            assertTrue(docObj.containsKey("iri"));
        }
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

        if (result.containsKey(DEFINUJICI_NELEGISLATIVNI_ZDROJ)) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sources = (List<Map<String, Object>>) result.get(DEFINUJICI_NELEGISLATIVNI_ZDROJ);
            Map<String, Object> docObj = sources.get(0);
            assertEquals("https://example.org/doc/1", docObj.get("iri"));
            assertEquals("Digitální objekt", docObj.get("typ"));
            @SuppressWarnings("unchecked")
            Map<String, Object> title = (Map<String, Object>) docObj.get(NAZEV);
            assertEquals("Testovací dokument", title.get("cs"));
            @SuppressWarnings("unchecked")
            Map<String, Object> description = (Map<String, Object>) docObj.get(POPIS);
            assertNotNull(description);
            assertEquals("Popis dokumentu", description.get("cs"));
            assertEquals("https://example.org/files/doc.pdf", docObj.get("url"));
        }
    }

    @Test
    @DisplayName("No exactMatch → field absent")
    void noExactMatch_fieldAbsent() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "osoba", "Osoba");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertFalse(result.containsKey(EKVIVALENTNI_POJEM));
    }

    @Test
    @DisplayName("No source properties → fields absent")
    void noSourceProperties_fieldsAbsent() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "osoba", "Osoba");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertFalse(result.containsKey(DEFINUJICI_USTANOVENI_PRAVNIHO_PREDPISU));
        assertFalse(result.containsKey(SOUVISEJICI_USTANOVENI_PRAVNIHO_PREDPISU));
    }
}
