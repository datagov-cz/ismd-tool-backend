package com.dia.ismdtoolbackend.utility.exporter.json;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.utility.exporter.RdfTestModelFactory.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConceptProcessor - Multilingual Properties (prefLabel, definition, description)")
class ConceptMultilingualPropertyTest {

    private ConceptProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ConceptProcessor();
    }

    @Test
    @DisplayName("Single Czech prefLabel maps to název.cs")
    void singleCzechPrefLabel() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "osoba", "Osoba");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        Map<String, Object> nazev = (Map<String, Object>) result.get(NAZEV);
        assertNotNull(nazev);
        assertEquals("Osoba", nazev.get("cs"));
    }

    @Test
    @DisplayName("Multiple language prefLabels produce multi-language název object")
    void multiLanguagePrefLabel() {
        OntModel model = createDefaultModel();
        Resource concept = addSkosConcept(model, "osoba", "Osoba");
        concept.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + TRIDA));
        addMultilingualLabel(concept, SKOS.prefLabel, Map.of("en", "Person"), model);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        Map<String, Object> nazev = (Map<String, Object>) result.get(NAZEV);
        assertNotNull(nazev);
        assertEquals("Osoba", nazev.get("cs"));
        assertEquals("Person", nazev.get("en"));
    }

    @Test
    @DisplayName("Label without language tag defaults to cs")
    void noLangTag_defaultsToCs() {
        OntModel model = createDefaultModel();
        Resource concept = addSkosConcept(model, "nolangtag", "placeholder");
        concept.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + TRIDA));
        // Remove the cs-tagged label and add one without a tag
        concept.removeAll(SKOS.prefLabel);
        concept.addProperty(SKOS.prefLabel, "Bez značky");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        Map<String, Object> nazev = (Map<String, Object>) result.get(NAZEV);
        assertNotNull(nazev);
        assertEquals("Bez značky", nazev.get("cs"));
    }

    @Test
    @DisplayName("Duplicate language prefLabels produce array")
    void duplicateLang_producesArray() {
        OntModel model = createDefaultModel();
        Resource concept = addSkosConcept(model, "dupcs", "Prvni");
        concept.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + TRIDA));
        concept.addProperty(SKOS.prefLabel, model.createLiteral("Druhy", "cs"));
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        Map<String, Object> nazev = (Map<String, Object>) result.get(NAZEV);
        assertNotNull(nazev);
        Object csValue = nazev.get("cs");
        assertInstanceOf(List.class, csValue, "Duplicate lang values should produce an array");
        @SuppressWarnings("unchecked")
        List<Object> csArray = (List<Object>) csValue;
        assertEquals(2, csArray.size());
    }

    @Test
    @DisplayName("Czech diacritics preserved in prefLabel")
    void czechDiacriticsPreserved() {
        OntModel model = createDefaultModel();
        String diacritics = "Příjmení žadatele o řízení";
        Resource concept = addOwlClass(model, "diacritics", diacritics);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        Map<String, Object> nazev = (Map<String, Object>) result.get(NAZEV);
        assertEquals(diacritics, nazev.get("cs"));
    }

    @Test
    @DisplayName("skos:definition maps to definice with language")
    void definitionProperty() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "defined", "Definovaný");
        addDefinition(concept, model, "Toto je definice", "cs");
        addDefinition(concept, model, "This is a definition", "en");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        Map<String, Object> definice = (Map<String, Object>) result.get(DEFINICE);
        assertNotNull(definice);
        assertEquals("Toto je definice", definice.get("cs"));
        assertEquals("This is a definition", definice.get("en"));
    }

    @Test
    @DisplayName("dct:description maps to popis with language")
    void descriptionProperty() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "described", "Popsaný");
        addDescription(concept, model, "Detailní popis", "cs");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        Map<String, Object> popis = (Map<String, Object>) result.get(POPIS);
        assertNotNull(popis);
        assertEquals("Detailní popis", popis.get("cs"));
    }

    @Test
    @DisplayName("Missing prefLabel produces no název field")
    void missingPrefLabel_noNazev() {
        OntModel model = createDefaultModel();
        Resource concept = model.createResource(TEST_POJEM_NS + "nolabel");
        concept.addProperty(RDF.type, model.createResource(SKOS_NS + "Concept"));
        concept.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));
        concept.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + TRIDA));
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertFalse(result.containsKey(NAZEV));
    }
}
