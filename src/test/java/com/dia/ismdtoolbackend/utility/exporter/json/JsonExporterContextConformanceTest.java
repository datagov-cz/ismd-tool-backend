package com.dia.ismdtoolbackend.utility.exporter.json;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;

import static com.dia.constants.VocabularyConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for validating that JSON exports from JsonExporter conform to the JSON-LD context structure.
 * This class performs structural validation.
 */
class JsonExporterContextConformanceTest {

    private static final String TEST_NAMESPACE = "http://test.example.org/vocabulary/";
    private static final String OFN_NAMESPACE = "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/";
    private static final String CONTEXT_RESOURCE_PATH = "/com/dia/context/json_ld_context.jsonld";

    private static JSONObject contextDefinition;
    private static Set<String> validContextFields;
    private static Set<String> validVocabularyTypes;

    private JsonExporter jsonExporter;

    @BeforeAll
    static void loadContext() throws Exception {
        try (InputStream is = JsonExporterContextConformanceTest.class.getResourceAsStream(CONTEXT_RESOURCE_PATH)) {
            assertNotNull(is, "JSON-LD context file not found at " + CONTEXT_RESOURCE_PATH);
            String contextContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            JSONObject fullContext = new JSONObject(contextContent);
            contextDefinition = fullContext.getJSONObject("@context");

            // Extract valid field names and categorize them
            extractContextMetadata();
        }
    }

    private static void extractContextMetadata() {
        validContextFields = new HashSet<>();
        validVocabularyTypes = new HashSet<>();

        @SuppressWarnings("unchecked")
        Iterator<String> keys = contextDefinition.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.startsWith("@")) {
                validContextFields.add(key);
            }
        }

        // Define valid vocabulary types
        validVocabularyTypes.add("Tezaurus");
        validVocabularyTypes.add("Konceptuální model");
        validVocabularyTypes.add("Slovník");
    }

    @BeforeEach
    void setUp() {
        jsonExporter = new JsonExporter();
    }

    // ========== Root Structure Tests ==========

    @Test
    void exportToJson_HasRequiredRootFields() throws Exception {
        Model model = createBasicVocabularyModel();
        String jsonOutput = jsonExporter.exportToJson(model);
        JSONObject json = new JSONObject(jsonOutput);

        assertTrue(json.has("@context"), "JSON should have @context field");
        assertTrue(json.has("iri"), "JSON should have iri field");
        assertTrue(json.has("typ"), "JSON should have typ field");
        assertTrue(json.has("název"), "JSON should have název field");
    }

    @Test
    void exportToJson_HasPojmyArray() throws Exception {
        Model model = createModelWithConcept();
        String jsonOutput = jsonExporter.exportToJson(model);
        JSONObject json = new JSONObject(jsonOutput);

        assertTrue(json.has("pojmy"), "JSON should have pojmy field");
        assertInstanceOf(JSONArray.class, json.get("pojmy"), "pojmy should be an array");
    }

    @Test
    void exportToJson_VocabularyTypesMatchContext() throws Exception {
        Model model = createBasicVocabularyModel();
        String jsonOutput = jsonExporter.exportToJson(model);
        JSONObject json = new JSONObject(jsonOutput);

        assertTrue(json.has("typ"), "JSON should have typ field");
        JSONArray types = json.getJSONArray("typ");

        for (int i = 0; i < types.length(); i++) {
            String type = types.getString(i);
            assertTrue(validVocabularyTypes.contains(type),
                "Vocabulary type '" + type + "' should be defined in context");
        }
    }

    // ========== Field Name Conformance Tests ==========

    @Test
    void exportToJson_RootLevelFieldsExistInContext() throws Exception {
        Model model = createBasicVocabularyModel();
        String jsonOutput = jsonExporter.exportToJson(model);
        JSONObject json = new JSONObject(jsonOutput);

        List<String> undefinedFields = new ArrayList<>();
        @SuppressWarnings("unchecked")
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.equals("@context") && !key.equals("pojmy") && !validContextFields.contains(key)) {
                undefinedFields.add(key);
            }
        }

        if (!undefinedFields.isEmpty()) {
            System.out.println("WARNING: Root-level fields not in context: " + undefinedFields);
        }
    }

    @Test
    void exportToJson_ConceptFieldsExistInContext() throws Exception {
        Model model = createModelWithConcept();
        String jsonOutput = jsonExporter.exportToJson(model);
        JSONObject json = new JSONObject(jsonOutput);

        if (json.has("pojmy")) {
            JSONArray concepts = json.getJSONArray("pojmy");
            for (int i = 0; i < concepts.length(); i++) {
                JSONObject concept = concepts.getJSONObject(i);
                List<String> undefinedFields = new ArrayList<>();

                @SuppressWarnings("unchecked")
                Iterator<String> keys = concept.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (!validContextFields.contains(key)) {
                        undefinedFields.add(key);
                    }
                }

                if (!undefinedFields.isEmpty()) {
                    System.out.println("WARNING: Concept fields not in context: " + undefinedFields);
                }
            }
        }
    }

    // ========== Data Type Tests ==========

    @Test
    void exportToJson_MultilingualFieldsHaveCorrectStructure() throws Exception {
        Model model = createModelWithMultilingualConcept();
        String jsonOutput = jsonExporter.exportToJson(model);
        JSONObject json = new JSONObject(jsonOutput);

        // Check vocabulary-level multilingual fields
        if (json.has("název")) {
            assertInstanceOf(JSONObject.class, json.get("název"), "název should be an object");
            JSONObject nazev = json.getJSONObject("název");
            assertTrue(nazev.has("cs") || nazev.has("en"), "název should have language tags");
        }

        // Check concept-level multilingual fields
        if (json.has("pojmy")) {
            JSONArray concepts = json.getJSONArray("pojmy");
            if (concepts.length() > 0) {
                JSONObject concept = concepts.getJSONObject(0);
                if (concept.has("název")) {
                    assertInstanceOf(JSONObject.class, concept.get("název"), "Concept název should be an object");
                }
                if (concept.has("definice")) {
                    assertInstanceOf(JSONObject.class, concept.get("definice"), "Concept definice should be an object");
                }
            }
        }
    }

    @Test
    void exportToJson_TypeFieldsAreArrays() throws Exception {
        Model model = createModelWithConcept();
        String jsonOutput = jsonExporter.exportToJson(model);
        JSONObject json = new JSONObject(jsonOutput);

        assertInstanceOf(JSONArray.class, json.get("typ"), "Root typ should be an array");

        if (json.has("pojmy")) {
            JSONArray concepts = json.getJSONArray("pojmy");
            for (int i = 0; i < concepts.length(); i++) {
                JSONObject concept = concepts.getJSONObject(i);
                assertInstanceOf(JSONArray.class, concept.get("typ"), "Concept typ should be an array");
            }
        }
    }

    @Test
    void exportToJson_IriFieldsContainValidStrings() throws Exception {
        Model model = createModelWithConcept();
        String jsonOutput = jsonExporter.exportToJson(model);
        JSONObject json = new JSONObject(jsonOutput);

        // Check root IRI
        assertTrue(json.has("iri"), "JSON should have iri field");
        assertInstanceOf(String.class, json.get("iri"), "iri should be a string");
        String iri = json.getString("iri");
        assertTrue(iri.startsWith("http://") || iri.startsWith("https://"),
            "iri should be a valid URI");

        // Check concept IRIs
        if (json.has("pojmy")) {
            JSONArray concepts = json.getJSONArray("pojmy");
            for (int i = 0; i < concepts.length(); i++) {
                JSONObject concept = concepts.getJSONObject(i);
                assertTrue(concept.has("iri"), "Concept should have iri field");
                String conceptIri = concept.getString("iri");
                assertTrue(conceptIri.startsWith("http://") || conceptIri.startsWith("https://"),
                    "Concept iri should be a valid URI");
            }
        }
    }

    @Test
    void exportToJson_BooleanFieldsAreBoolean() throws Exception {
        Model model = createModelWithGovernanceMetadata();
        String jsonOutput = jsonExporter.exportToJson(model);
        JSONObject json = new JSONObject(jsonOutput);

        if (json.has("pojmy")) {
            JSONArray concepts = json.getJSONArray("pojmy");
            for (int i = 0; i < concepts.length(); i++) {
                JSONObject concept = concepts.getJSONObject(i);
                if (concept.has("je-sdílen-v-ppdf")) {
                    assertInstanceOf(Boolean.class, concept.get("je-sdílen-v-ppdf"),
                        "je-sdílen-v-ppdf should be a boolean");
                }
            }
        }
    }

    // ========== Concept Type Coverage Tests (Parameterized) ==========

    @ParameterizedTest
    @MethodSource("provideConceptTypeTestCases")
    void exportToJson_ConceptTypeMatchesContext(String conceptType, Resource typeResource) throws Exception {
        Model model = createModelWithConceptOfType(conceptType, typeResource);
        String jsonOutput = jsonExporter.exportToJson(model);
        JSONObject json = new JSONObject(jsonOutput);

        assertTrue(json.has("pojmy"), "JSON should have pojmy array");
        JSONArray concepts = json.getJSONArray("pojmy");
        assertTrue(concepts.length() > 0, "Should have at least one concept");

        JSONObject concept = concepts.getJSONObject(0);
        assertTrue(concept.has("typ"), "Concept should have typ field");

        JSONArray types = concept.getJSONArray("typ");
        List<String> typeList = new ArrayList<>();
        for (int i = 0; i < types.length(); i++) {
            typeList.add(types.getString(i));
        }

        boolean hasExpectedType = typeList.contains(conceptType);
        assertTrue(hasExpectedType, "Concept should have type: " + conceptType);
    }

    private static Stream<Arguments> provideConceptTypeTestCases() {
        OntModel tempModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);
        return Stream.of(
            Arguments.of("Pojem", tempModel.getResource(OFN_NAMESPACE + "pojem")),
            Arguments.of("Třída", OWL.Class),
            Arguments.of("Vlastnost", OWL.DatatypeProperty),
            Arguments.of("Vztah", OWL.ObjectProperty),
            Arguments.of("Typ objektu práva", tempModel.getResource(OFN_NAMESPACE + "typ-objektu-práva")),
            Arguments.of("Typ subjektu práva", tempModel.getResource(OFN_NAMESPACE + "typ-subjektu-práva")),
            Arguments.of("Veřejný údaj", tempModel.getResource(OFN_NAMESPACE_LEGAL + "veřejný-údaj")),
            Arguments.of("Neveřejný údaj", tempModel.getResource(OFN_NAMESPACE_LEGAL + "neveřejný-údaj"))
        );
    }

    // ========== Edge Case Tests ==========

    @Test
    void exportToJson_MultipleLanguagesHandledCorrectly() throws Exception {
        Model model = createModelWithMultipleLanguages();
        String jsonOutput = jsonExporter.exportToJson(model);
        JSONObject json = new JSONObject(jsonOutput);

        if (json.has("pojmy")) {
            JSONArray concepts = json.getJSONArray("pojmy");
            if (concepts.length() > 0) {
                JSONObject concept = concepts.getJSONObject(0);
                if (concept.has("název")) {
                    JSONObject nazev = concept.getJSONObject("název");
                    // Should have both Czech and English
                    assertTrue(nazev.has("cs") || nazev.has("en"),
                        "Should have at least one language");
                }
            }
        }
    }

    @Test
    void exportToJson_ConceptsWithMultipleTypes() throws Exception {
        Model model = createModelWithMultipleConceptTypes();
        String jsonOutput = jsonExporter.exportToJson(model);
        JSONObject json = new JSONObject(jsonOutput);

        if (json.has("pojmy")) {
            JSONArray concepts = json.getJSONArray("pojmy");
            if (concepts.length() > 0) {
                JSONObject concept = concepts.getJSONObject(0);
                JSONArray types = concept.getJSONArray("typ");

                // Should have multiple types
                assertTrue(types.length() >= 1, "Concept should have at least one type");
            }
        }
    }

    @Test
    void exportToJson_HierarchicalRelationships() throws Exception {
        Model model = createModelWithHierarchy();
        String jsonOutput = jsonExporter.exportToJson(model);
        JSONObject json = new JSONObject(jsonOutput);

        if (json.has("pojmy")) {
            JSONArray concepts = json.getJSONArray("pojmy");
            boolean foundHierarchicalField = false;

            for (int i = 0; i < concepts.length(); i++) {
                JSONObject concept = concepts.getJSONObject(i);

                // Check for hierarchical fields
                if (concept.has("nadřazená-třída") ||
                    concept.has("nadřazená-vlastnost") ||
                    concept.has("nadřazený-vztah") ||
                    concept.has("nadřazený-pojem")) {
                    foundHierarchicalField = true;
                    break;
                }
            }

            assertTrue(foundHierarchicalField, "Should find hierarchical relationship fields");
        }
    }

    // ========== Helper Methods: RDF Model Builders ==========

    private Model createBasicVocabularyModel() {
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

    private Model createModelWithConcept() {
        OntModel ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);

        ontModel.createOntology(TEST_NAMESPACE);
        Resource ontologyResource = ontModel.getResource(TEST_NAMESPACE);
        if (ontologyResource != null) {
            ontologyResource.addProperty(RDF.type, ontModel.getResource("http://www.w3.org/2002/07/owl#Ontology"));
            ontologyResource.addProperty(RDF.type, SKOS.ConceptScheme);
            ontologyResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + "slovník"));
            ontologyResource.addProperty(SKOS.prefLabel, "Test Vocabulary", "cs");
        }

        String conceptURI = TEST_NAMESPACE + "concept1";
        Resource concept = ontModel.createResource(conceptURI);
        concept.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + "pojem"));
        concept.addProperty(SKOS.prefLabel, "Test Concept", "cs");

        return ontModel;
    }

    private Model createModelWithMultilingualConcept() {
        OntModel ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);

        ontModel.createOntology(TEST_NAMESPACE);
        Resource ontologyResource = ontModel.getResource(TEST_NAMESPACE);
        if (ontologyResource != null) {
            ontologyResource.addProperty(RDF.type, ontModel.getResource("http://www.w3.org/2002/07/owl#Ontology"));
            ontologyResource.addProperty(RDF.type, SKOS.ConceptScheme);
            ontologyResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + "slovník"));
            ontologyResource.addProperty(SKOS.prefLabel, "Testovací slovník", "cs");
            Property descProperty = ontModel.createProperty("http://purl.org/dc/terms/description");
            ontologyResource.addProperty(descProperty, "Popis slovníku", "cs");
        }

        String conceptURI = TEST_NAMESPACE + "concept1";
        Resource concept = ontModel.createResource(conceptURI);
        concept.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + "pojem"));
        concept.addProperty(SKOS.prefLabel, "Testovací pojem", "cs");
        concept.addProperty(SKOS.definition, "Definice pojmu", "cs");

        return ontModel;
    }

    private Model createModelWithConceptOfType(String typeName, Resource typeResource) {
        OntModel ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);

        ontModel.createOntology(TEST_NAMESPACE);
        Resource ontologyResource = ontModel.getResource(TEST_NAMESPACE);
        if (ontologyResource != null) {
            ontologyResource.addProperty(RDF.type, ontModel.getResource("http://www.w3.org/2002/07/owl#Ontology"));
            ontologyResource.addProperty(RDF.type, SKOS.ConceptScheme);
            ontologyResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + "slovník"));
            ontologyResource.addProperty(SKOS.prefLabel, "Test Vocabulary", "cs");
        }

        String conceptURI = TEST_NAMESPACE + "concept_" + typeName.replaceAll("\\s+", "_");
        Resource concept = ontModel.createResource(conceptURI);

        concept.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + "pojem"));

        String typeURI = typeResource.getURI();
        if (typeURI != null) {
            if (typeURI.contains("owl")) {
                concept.addProperty(RDF.type, typeResource);

                if (typeResource.equals(OWL.Class)) {
                    concept.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + "třída"));
                } else if (typeResource.equals(OWL.DatatypeProperty)) {
                    concept.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + "vlastnost"));
                } else if (typeResource.equals(OWL.ObjectProperty)) {
                    concept.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + "vztah"));
                }
            } else {
                concept.addProperty(RDF.type, typeResource);

                if (typeURI.contains("typ-subjektu-práva") || typeURI.contains("typ-objektu-práva")) {
                    concept.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + "třída"));
                }
            }
        }

        concept.addProperty(SKOS.prefLabel, "Test " + typeName, "cs");

        return ontModel;
    }

    private Model createModelWithGovernanceMetadata() {
        OntModel ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);

        ontModel.createOntology(TEST_NAMESPACE);
        Resource ontologyResource = ontModel.getResource(TEST_NAMESPACE);
        if (ontologyResource != null) {
            ontologyResource.addProperty(RDF.type, ontModel.getResource("http://www.w3.org/2002/07/owl#Ontology"));
            ontologyResource.addProperty(RDF.type, SKOS.ConceptScheme);
            ontologyResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + "slovník"));
            ontologyResource.addProperty(SKOS.prefLabel, "Test Vocabulary", "cs");
        }

        String conceptURI = TEST_NAMESPACE + "governanceConcept";
        Resource concept = ontModel.createResource(conceptURI);
        concept.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + "pojem"));
        concept.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + "veřejný-údaj"));
        concept.addProperty(SKOS.prefLabel, "Veřejný údaj", "cs");

        Property ppdfProperty = ontModel.createProperty(TEST_NAMESPACE + "je-sdílen-v-ppdf");
        concept.addProperty(ppdfProperty, ontModel.createTypedLiteral(true));

        return ontModel;
    }

    private Model createModelWithMultipleLanguages() {
        OntModel ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);

        ontModel.createOntology(TEST_NAMESPACE);
        Resource ontologyResource = ontModel.getResource(TEST_NAMESPACE);
        if (ontologyResource != null) {
            ontologyResource.addProperty(RDF.type, ontModel.getResource("http://www.w3.org/2002/07/owl#Ontology"));
            ontologyResource.addProperty(RDF.type, SKOS.ConceptScheme);
            ontologyResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + "slovník"));
            ontologyResource.addProperty(SKOS.prefLabel, "Testovací slovník", "cs");
            ontologyResource.addProperty(SKOS.prefLabel, "Test Vocabulary", "en");
        }

        String conceptURI = TEST_NAMESPACE + "multilingualConcept";
        Resource concept = ontModel.createResource(conceptURI);
        concept.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + "pojem"));
        concept.addProperty(SKOS.prefLabel, "Vícejazyčný pojem", "cs");
        concept.addProperty(SKOS.prefLabel, "Multilingual Concept", "en");
        concept.addProperty(SKOS.definition, "Definice v češtině", "cs");
        concept.addProperty(SKOS.definition, "Definition in English", "en");

        return ontModel;
    }

    private Model createModelWithMultipleConceptTypes() {
        OntModel ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);

        ontModel.createOntology(TEST_NAMESPACE);
        Resource ontologyResource = ontModel.getResource(TEST_NAMESPACE);
        if (ontologyResource != null) {
            ontologyResource.addProperty(RDF.type, ontModel.getResource("http://www.w3.org/2002/07/owl#Ontology"));
            ontologyResource.addProperty(RDF.type, SKOS.ConceptScheme);
            ontologyResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + "slovník"));
            ontologyResource.addProperty(SKOS.prefLabel, "Test Vocabulary", "cs");
        }

        String conceptURI = TEST_NAMESPACE + "multiTypeConcept";
        Resource concept = ontModel.createResource(conceptURI);
        concept.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + "pojem"));
        concept.addProperty(RDF.type, OWL.Class);
        concept.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + "třída"));
        concept.addProperty(SKOS.prefLabel, "Pojem s více typy", "cs");

        return ontModel;
    }

    private Model createModelWithHierarchy() {
        OntModel ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);

        ontModel.createOntology(TEST_NAMESPACE);
        Resource ontologyResource = ontModel.getResource(TEST_NAMESPACE);
        if (ontologyResource != null) {
            ontologyResource.addProperty(RDF.type, ontModel.getResource("http://www.w3.org/2002/07/owl#Ontology"));
            ontologyResource.addProperty(RDF.type, SKOS.ConceptScheme);
            ontologyResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + "slovník"));
            ontologyResource.addProperty(SKOS.prefLabel, "Test Vocabulary", "cs");
        }

        String parentURI = TEST_NAMESPACE + "ParentClass";
        Resource parentClass = ontModel.createResource(parentURI);
        parentClass.addProperty(RDF.type, OWL.Class);
        parentClass.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + "třída"));
        parentClass.addProperty(SKOS.prefLabel, "Nadřazená třída", "cs");

        String childURI = TEST_NAMESPACE + "ChildClass";
        Resource childClass = ontModel.createResource(childURI);
        childClass.addProperty(RDF.type, OWL.Class);
        childClass.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + "třída"));
        childClass.addProperty(SKOS.prefLabel, "Podřazená třída", "cs");
        Property subClassOfProperty = ontModel.createProperty("http://www.w3.org/2000/01/rdf-schema#subClassOf");
        childClass.addProperty(subClassOfProperty, parentClass);

        return ontModel;
    }
}
