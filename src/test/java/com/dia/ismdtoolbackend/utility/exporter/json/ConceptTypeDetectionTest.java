package com.dia.ismdtoolbackend.utility.exporter.json;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.utility.exporter.RdfTestModelFactory.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConceptProcessor - Type Detection & Mapping")
class ConceptTypeDetectionTest {

    private ConceptProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ConceptProcessor();
    }

    static Stream<Arguments> ofnTypeProvider() {
        return Stream.of(
                Arguments.of(TRIDA, TRIDA_JSON_LD, "třída"),
                Arguments.of(VZTAH, VZTAH_JSON_LD, "vztah"),
                Arguments.of(VLASTNOST, VLASTNOST_JSON_LD, "vlastnost"),
                Arguments.of(TSP, TSP_JSON_LD, "typ-subjektu-práva"),
                Arguments.of(TOP, TOP_JSON_LD, "typ-objektu-práva")
        );
    }

    @ParameterizedTest(name = "OFN type {0} maps to {1}")
    @MethodSource("ofnTypeProvider")
    @DisplayName("OFN types are correctly detected and mapped")
    void ofnType_mapsToJsonLdType(String ofnType, String expectedJsonLd, String description) {
        OntModel model = createDefaultModel();
        Resource concept = addConceptWithType(model, "test-concept", "Test", ofnType);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) result.get("typ");
        assertNotNull(types);
        assertTrue(types.contains(POJEM_JSON_LD), "Should always contain Pojem");
        assertTrue(types.contains("Koncept"), "Should always contain Koncept");
        assertTrue(types.contains(expectedJsonLd), "Should contain " + expectedJsonLd);
    }

    static Stream<Arguments> legalTypeProvider() {
        return Stream.of(
                Arguments.of(VEREJNY_UDAJ, VEREJNY_UDAJ_JSON_LD),
                Arguments.of(NEVEREJNY_UDAJ, NEVEREJNY_UDAJ_JSON_LD)
        );
    }

    @ParameterizedTest(name = "Legal type {0} maps to {1}")
    @MethodSource("legalTypeProvider")
    @DisplayName("Legal types from OFN_NAMESPACE_LEGAL are detected")
    void legalType_mapsToJsonLdType(String legalType, String expectedJsonLd) {
        OntModel model = createDefaultModel();
        Resource concept = addConceptWithLegalType(model, "test-legal", "Test Legal", legalType);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) result.get("typ");
        assertTrue(types.contains(expectedJsonLd));
    }

    @Test
    @DisplayName("owl:Class without OFN třída still gets Třída type")
    void owlClassOnly_getsTridaType() {
        OntModel model = createDefaultModel();
        Resource concept = addSkosConcept(model, "owl-class", "OWL Class");
        concept.addProperty(RDF.type, OWL2.Class);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) result.get("typ");
        assertTrue(types.contains(TRIDA_JSON_LD));
    }

    @Test
    @DisplayName("owl:ObjectProperty without OFN vztah still gets Vztah type")
    void owlObjectPropertyOnly_getsVztahType() {
        OntModel model = createDefaultModel();
        Resource concept = addSkosConcept(model, "owl-op", "OWL OP");
        concept.addProperty(RDF.type, OWL2.ObjectProperty);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) result.get("typ");
        assertTrue(types.contains(VZTAH_JSON_LD));
    }

    @Test
    @DisplayName("owl:DatatypeProperty without OFN vlastnost still gets Vlastnost type")
    void owlDatatypePropertyOnly_getsVlastnostType() {
        OntModel model = createDefaultModel();
        Resource concept = addSkosConcept(model, "owl-dp", "OWL DP");
        concept.addProperty(RDF.type, OWL2.DatatypeProperty);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) result.get("typ");
        assertTrue(types.contains(VLASTNOST_JSON_LD));
    }

    @Test
    @DisplayName("Multiple OFN types on same concept are all detected")
    void multipleTypes_allDetected() {
        OntModel model = createDefaultModel();
        Resource concept = addSkosConcept(model, "multi", "Multi");
        concept.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + TRIDA));
        concept.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + TSP));
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) result.get("typ");
        assertTrue(types.contains(TRIDA_JSON_LD));
        assertTrue(types.contains(TSP_JSON_LD));
    }

    @Test
    @DisplayName("Vlastnost type suppressed when concept is also a Vztah")
    void vlastnostSuppressedWhenVztah() {
        OntModel model = createDefaultModel();
        Resource concept = addSkosConcept(model, "vztah-vlastnost", "Both");
        concept.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + VZTAH));
        concept.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + VLASTNOST));
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) result.get("typ");
        assertTrue(types.contains(VZTAH_JSON_LD));
        assertFalse(types.contains(VLASTNOST_JSON_LD),
                "Vlastnost should be suppressed when Vztah is present");
    }

    @Test
    @DisplayName("DatatypeProperty type suppressed when concept is also a Vztah")
    void datatypePropertySuppressedWhenVztah() {
        OntModel model = createDefaultModel();
        Resource concept = addSkosConcept(model, "vztah-dp", "Vztah+DP");
        concept.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + VZTAH));
        concept.addProperty(RDF.type, OWL2.DatatypeProperty);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) result.get("typ");
        assertTrue(types.contains(VZTAH_JSON_LD));
        assertFalse(types.contains(VLASTNOST_JSON_LD));
    }
}
