package com.dia.ismdtoolbackend.utility.exporter.json;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import static com.dia.constants.VocabularyConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Semantic tests for JsonExporter that validate semantic correctness of JSON-LD output.
 * <p>
 * These tests ensure:
 * - RDF semantics are correctly represented in JSON
 * - Context mappings are correctly applied (Czech -> international vocabularies)
 * - Language tags are preserved in multilingual properties
 * - Type hierarchies are correctly represented
 * - Relationships have correct semantic meaning
 * - Multiple concept types correct handling
 */
@DisplayName("JsonExporter - Semantic Validation Tests")
class JsonExporterSemanticTest {

    private static final String TEST_NAMESPACE = "http://test.example.org/vocabulary/";
    private static final String OFN_NAMESPACE = "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/";

    private JsonExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new JsonExporter();
    }

    // ==================== Context Mapping Semantic Tests ====================

    @Test
    @DisplayName("Semantic: skos:prefLabel in RDF maps to 'název' in JSON")
    void testSkosPrefLabelMapsToNazev() throws Exception {
        // Create RDF model with skos:prefLabel
        OntModel model = createModelWithConcept("Testovací název");

        // Export to JSON
        String json = exporter.exportToJson(model);
        JSONObject jsonObj = new JSONObject(json);

        // Verify 'název' field exists in JSON
        JSONArray pojmy = jsonObj.getJSONArray("pojmy");
        assertTrue(pojmy.length() > 0, "Should have at least one concept");

        JSONObject concept = pojmy.getJSONObject(0);
        assertTrue(concept.has("název"), "skos:prefLabel should map to 'název'");

        // Verify the value is correct
        JSONObject nazev = concept.getJSONObject("název");
        assertTrue(nazev.has("cs"), "Should have Czech language key");
        assertEquals("Testovací název", nazev.getString("cs"),
            "Czech label value should be preserved");
    }

    @Test
    @DisplayName("Semantic: skos:definition in RDF maps to 'definice' in JSON")
    void testSkosDefinitionMapsToDefinice() throws Exception {
        OntModel model = createBasicVocabularyModel();

        String conceptURI = TEST_NAMESPACE + "concept_test";
        Resource concept = model.createResource(conceptURI);
        concept.addProperty(RDF.type, SKOS.Concept);
        concept.addProperty(SKOS.prefLabel, "Test Concept", "cs");
        concept.addProperty(SKOS.definition, "Testovací definice pojmu", "cs");

        String json = exporter.exportToJson(model);
        JSONObject jsonObj = new JSONObject(json);

        JSONArray pojmy = jsonObj.getJSONArray("pojmy");
        JSONObject conceptJson = pojmy.getJSONObject(0);

        assertTrue(conceptJson.has("definice"), "skos:definition should map to 'definice'");

        JSONObject definice = conceptJson.getJSONObject("definice");
        assertEquals("Testovací definice pojmu", definice.getString("cs"),
            "Definition content should be preserved");
    }

    @Test
    @DisplayName("Semantic: rdf:type values map to Czech type names")
    void testRdfTypeMapsToTypNames() throws Exception {
        OntModel model = createModelWithOwlClass();

        String json = exporter.exportToJson(model);
        JSONObject jsonObj = new JSONObject(json);

        JSONArray pojmy = jsonObj.getJSONArray("pojmy");
        JSONObject concept = pojmy.getJSONObject(0);

        assertTrue(concept.has("typ"), "rdf:type should map to 'typ' field");

        JSONArray typy = concept.getJSONArray("typ");
        assertTrue(typy.length() > 0, "Should have at least one type");

        // OWL Class should be represented as "Třída"
        boolean hasTrida = false;
        for (int i = 0; i < typy.length(); i++) {
            if ("Třída".equals(typy.getString(i))) {
                hasTrida = true;
                break;
            }
        }
        assertTrue(hasTrida, "owl:Class should be represented as 'Třída'");
    }

    // ==================== Language Tag Preservation Tests ====================

    @Test
    @DisplayName("Semantic: Multiple language tags are preserved correctly")
    void testMultilingualPropertiesPreserved() throws Exception {
        OntModel model = createBasicVocabularyModel();

        String conceptURI = TEST_NAMESPACE + "concept_multilingual";
        Resource concept = model.createResource(conceptURI);
        concept.addProperty(RDF.type, SKOS.Concept);
        concept.addProperty(SKOS.prefLabel, "Testovací pojem", "cs");
        concept.addProperty(SKOS.prefLabel, "Test Concept", "en");

        String json = exporter.exportToJson(model);
        JSONObject jsonObj = new JSONObject(json);

        JSONArray pojmy = jsonObj.getJSONArray("pojmy");
        JSONObject conceptJson = pojmy.getJSONObject(0);
        JSONObject nazev = conceptJson.getJSONObject("název");

        // Verify all three languages are present
        assertTrue(nazev.has("cs"), "Should have Czech label");
        assertTrue(nazev.has("en"), "Should have English label");

        // Verify values
        assertEquals("Testovací pojem", nazev.getString("cs"));
        assertEquals("Test Concept", nazev.getString("en"));
    }

    @Test
    @DisplayName("Semantic: Language tags are correctly structured as objects")
    void testLanguageTagStructure() throws Exception {
        OntModel model = createModelWithConcept("Test");

        String json = exporter.exportToJson(model);
        JSONObject jsonObj = new JSONObject(json);

        // Check vocabulary level
        JSONObject nazev = jsonObj.getJSONObject("název");
        assertInstanceOf(JSONObject.class, nazev, "'název' should be an object");
        assertTrue(nazev.has("cs"), "Should have language key");

        // Check concept level
        JSONArray pojmy = jsonObj.getJSONArray("pojmy");
        JSONObject concept = pojmy.getJSONObject(0);
        JSONObject conceptNazev = concept.getJSONObject("název");
        assertInstanceOf(JSONObject.class, conceptNazev, "Concept 'název' should be an object");
    }

    // ==================== Type Hierarchy Semantic Tests ====================

    @ParameterizedTest
    @MethodSource("provideConceptTypeTestCases")
    @DisplayName("Semantic: OFN concept types are correctly represented")
    void testOfnConceptTypesCorrectlyRepresented(String expectedTypeName, Resource rdfType) throws Exception {
        OntModel model = createBasicVocabularyModel();

        String conceptURI = TEST_NAMESPACE + "concept_typed";
        Resource concept = model.createResource(conceptURI);
        concept.addProperty(RDF.type, SKOS.Concept);
        concept.addProperty(RDF.type, rdfType);
        concept.addProperty(SKOS.prefLabel, "Typed Concept", "cs");

        String json = exporter.exportToJson(model);
        JSONObject jsonObj = new JSONObject(json);

        JSONArray pojmy = jsonObj.getJSONArray("pojmy");
        JSONObject conceptJson = pojmy.getJSONObject(0);
        JSONArray typy = conceptJson.getJSONArray("typ");

        // Find the expected type name in the type array
        boolean foundExpectedType = false;
        for (int i = 0; i < typy.length(); i++) {
            if (expectedTypeName.equals(typy.getString(i))) {
                foundExpectedType = true;
                break;
            }
        }

        assertTrue(foundExpectedType,
            "Expected type '" + expectedTypeName + "' should be present in JSON output");
    }

    private static Stream<Arguments> provideConceptTypeTestCases() {
        OntModel tempModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);
        return Stream.of(
            Arguments.of("Pojem", SKOS.Concept),
            Arguments.of("Třída", OWL.Class),
            Arguments.of("Vlastnost", OWL.DatatypeProperty),
            Arguments.of("Vztah", OWL.ObjectProperty),
            Arguments.of("Typ subjektu práva", tempModel.getResource(OFN_NAMESPACE + "typ-subjektu-práva")),
            Arguments.of("Typ objektu práva", tempModel.getResource(OFN_NAMESPACE + "typ-objektu-práva")),
            Arguments.of("Veřejný údaj", tempModel.getResource(OFN_NAMESPACE_LEGAL + "veřejný-údaj")),
            Arguments.of("Neveřejný údaj", tempModel.getResource(OFN_NAMESPACE_LEGAL + "neveřejný-údaj"))
        );
    }

    @Test
    @DisplayName("Semantic: Multiple RDF types result in multiple JSON type entries")
    void testMultipleRdfTypesPreserved() throws Exception {
        OntModel model = createBasicVocabularyModel();

        String conceptURI = TEST_NAMESPACE + "multi_typed";
        Resource concept = model.createResource(conceptURI);
        concept.addProperty(RDF.type, SKOS.Concept);
        concept.addProperty(RDF.type, OWL.Class);
        concept.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + "třída"));
        concept.addProperty(SKOS.prefLabel, "Multi-typed", "cs");

        String json = exporter.exportToJson(model);
        JSONObject jsonObj = new JSONObject(json);

        JSONArray pojmy = jsonObj.getJSONArray("pojmy");
        JSONObject conceptJson = pojmy.getJSONObject(0);
        JSONArray typy = conceptJson.getJSONArray("typ");

        // Should have multiple types
        assertTrue(typy.length() >= 2,
            "Concept with multiple rdf:type properties should have multiple type entries");

        // Both "Pojem" and "Třída" should be present
        Set<String> typeSet = new HashSet<>();
        for (int i = 0; i < typy.length(); i++) {
            typeSet.add(typy.getString(i));
        }

        assertTrue(typeSet.contains("Pojem"), "Should include 'Pojem' type");
        assertTrue(typeSet.contains("Třída"), "Should include 'Třída' type");
    }

    // ==================== Relationship Semantic Tests ====================

    @Test
    @DisplayName("Semantic: skos:broader relationships are represented correctly")
    void testSkOSBroaderRelationshipsRepresented() throws Exception {
        OntModel model = createModelWithHierarchy();

        String json = exporter.exportToJson(model);
        JSONObject jsonObj = new JSONObject(json);

        JSONArray pojmy = jsonObj.getJSONArray("pojmy");

        // Find the child concept
        JSONObject childConcept = null;
        for (int i = 0; i < pojmy.length(); i++) {
            JSONObject concept = pojmy.getJSONObject(i);
            if (concept.getString("iri").endsWith("child_concept")) {
                childConcept = concept;
                break;
            }
        }

        assertNotNull(childConcept, "Should find child concept");

        assertTrue(childConcept.length() > 3,
            "Child concept should have more iri, typ, and název");
    }

    @Test
    @DisplayName("Semantic: Concept IRIs are preserved correctly")
    void testConceptIrisPreserved() throws Exception {
        OntModel model = createBasicVocabularyModel();

        String conceptURI = TEST_NAMESPACE + "test_concept_123";
        Resource concept = model.createResource(conceptURI);
        concept.addProperty(RDF.type, SKOS.Concept);
        concept.addProperty(SKOS.prefLabel, "Test", "cs");

        String json = exporter.exportToJson(model);
        JSONObject jsonObj = new JSONObject(json);

        JSONArray pojmy = jsonObj.getJSONArray("pojmy");
        JSONObject conceptJson = pojmy.getJSONObject(0);

        assertEquals(conceptURI, conceptJson.getString("iri"),
            "Concept IRI should be preserved exactly");
    }

    // ==================== Vocabulary Semantic Tests ====================

    @Test
    @DisplayName("Semantic: Vocabulary metadata is correctly represented")
    void testVocabularyMetadataSemantics() throws Exception {
        OntModel model = createBasicVocabularyModel();

        String json = exporter.exportToJson(model);
        JSONObject jsonObj = new JSONObject(json);

        // Vocabulary IRI
        assertEquals(TEST_NAMESPACE, jsonObj.getString("iri"),
            "Vocabulary IRI should match ontology URI");

        // Vocabulary type
        JSONArray vocabTyp = jsonObj.getJSONArray("typ");
        assertTrue(vocabTyp.length() > 0, "Vocabulary should have at least one type");

        boolean hasSlovnik = false;
        for (int i = 0; i < vocabTyp.length(); i++) {
            if ("Slovník".equals(vocabTyp.getString(i))) {
                hasSlovnik = true;
                break;
            }
        }
        assertTrue(hasSlovnik, "Vocabulary should be typed as 'Slovník'");

        // Vocabulary name
        JSONObject vocabNazev = jsonObj.getJSONObject("název");
        assertEquals("Test Vocabulary", vocabNazev.getString("cs"),
            "Vocabulary name should be preserved");
    }

    @Test
    @DisplayName("Semantic: Empty vocabulary produces valid minimal JSON")
    void testEmptyVocabularySemantics() throws Exception {
        OntModel model = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);
        model.createOntology(TEST_NAMESPACE);

        String json = exporter.exportToJson(model);
        JSONObject jsonObj = new JSONObject(json);

        assertTrue(jsonObj.has("iri"), "Empty vocabulary should have IRI");
        assertTrue(jsonObj.has("pojmy"), "Should have empty pojmy array");

        JSONArray pojmy = jsonObj.getJSONArray("pojmy");
        assertEquals(0, pojmy.length(), "Empty vocabulary should have no concepts");
    }

    // ==================== Data Integrity Semantic Tests ====================

    @Test
    @DisplayName("Semantic: Special characters in labels are preserved")
    void testSpecialCharactersPreserved() throws Exception {
        OntModel model = createBasicVocabularyModel();

        String complexLabel = "Test: Speciální znaky - čeština, \"uvozovky\", & symboly";
        String conceptURI = TEST_NAMESPACE + "special_chars";
        Resource concept = model.createResource(conceptURI);
        concept.addProperty(RDF.type, SKOS.Concept);
        concept.addProperty(SKOS.prefLabel, complexLabel, "cs");

        String json = exporter.exportToJson(model);
        JSONObject jsonObj = new JSONObject(json);

        JSONArray pojmy = jsonObj.getJSONArray("pojmy");
        JSONObject conceptJson = pojmy.getJSONObject(0);
        JSONObject nazev = conceptJson.getJSONObject("název");

        assertEquals(complexLabel, nazev.getString("cs"),
            "Special characters should be preserved correctly");
    }

    @Test
    @DisplayName("Semantic: URIs with fragments are handled correctly")
    void testUrisWithFragments() throws Exception {
        OntModel model = createBasicVocabularyModel();

        String conceptURI = TEST_NAMESPACE + "#fragment-concept";
        Resource concept = model.createResource(conceptURI);
        concept.addProperty(RDF.type, SKOS.Concept);
        concept.addProperty(SKOS.prefLabel, "Fragment Test", "cs");

        String json = exporter.exportToJson(model);
        JSONObject jsonObj = new JSONObject(json);

        JSONArray pojmy = jsonObj.getJSONArray("pojmy");
        JSONObject conceptJson = pojmy.getJSONObject(0);

        assertEquals(conceptURI, conceptJson.getString("iri"),
            "URIs with fragments should be preserved");
    }

    @Test
    @DisplayName("Semantic: Concepts from different namespaces are handled")
    void testMultipleNamespaces() throws Exception {
        OntModel model = createBasicVocabularyModel();

        String otherNamespace = "http://other.example.org/vocab/";
        String conceptURI = otherNamespace + "external_concept";
        Resource concept = model.createResource(conceptURI);
        concept.addProperty(RDF.type, SKOS.Concept);
        concept.addProperty(SKOS.prefLabel, "External", "cs");

        String json = exporter.exportToJson(model);
        JSONObject jsonObj = new JSONObject(json);

        JSONArray pojmy = jsonObj.getJSONArray("pojmy");

        // Should include concepts from other namespaces
        assertTrue(pojmy.length() > 0, "Should export concepts even from different namespaces");

        boolean foundExternal = false;
        for (int i = 0; i < pojmy.length(); i++) {
            if (pojmy.getJSONObject(i).getString("iri").equals(conceptURI)) {
                foundExternal = true;
                break;
            }
        }

        assertTrue(foundExternal, "Should include concepts from external namespaces");
    }

    // ==================== Helper Methods ====================

    private OntModel createBasicVocabularyModel() {
        OntModel ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);

        ontModel.createOntology(TEST_NAMESPACE);
        Resource ontologyResource = ontModel.getResource(TEST_NAMESPACE);

        if (ontologyResource != null) {
            ontologyResource.addProperty(RDF.type, ontModel.getResource("http://www.w3.org/2002/07/owl#Ontology"));
            ontologyResource.addProperty(RDF.type, SKOS.ConceptScheme);
            ontologyResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + "slovník"));
            ontologyResource.addProperty(SKOS.prefLabel, "Test Vocabulary", "cs");
        }

        return ontModel;
    }

    private OntModel createModelWithConcept(String label) {
        OntModel ontModel = createBasicVocabularyModel();

        String conceptURI = TEST_NAMESPACE + "concept_test";
        Resource concept = ontModel.createResource(conceptURI);
        concept.addProperty(RDF.type, SKOS.Concept);
        concept.addProperty(SKOS.prefLabel, label, "cs");

        return ontModel;
    }

    private OntModel createModelWithOwlClass() {
        OntModel ontModel = createBasicVocabularyModel();

        String classURI = TEST_NAMESPACE + "class_test";
        Resource classResource = ontModel.createResource(classURI);
        classResource.addProperty(RDF.type, OWL.Class);
        classResource.addProperty(RDF.type, SKOS.Concept);
        classResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + "třída"));
        classResource.addProperty(SKOS.prefLabel, "Testovací třída", "cs");

        return ontModel;
    }

    private OntModel createModelWithHierarchy() {
        OntModel ontModel = createBasicVocabularyModel();

        String parentURI = TEST_NAMESPACE + "parent_concept";
        String childURI = TEST_NAMESPACE + "child_concept";

        Resource parent = ontModel.createResource(parentURI);
        parent.addProperty(RDF.type, SKOS.Concept);
        parent.addProperty(SKOS.prefLabel, "Parent Concept", "cs");

        Resource child = ontModel.createResource(childURI);
        child.addProperty(RDF.type, SKOS.Concept);
        child.addProperty(SKOS.prefLabel, "Child Concept", "cs");
        child.addProperty(SKOS.broader, parent);

        return ontModel;
    }
}
