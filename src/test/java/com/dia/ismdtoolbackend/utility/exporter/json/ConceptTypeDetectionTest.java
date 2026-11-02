package com.dia.ismdtoolbackend.utility.exporter.json;

import com.dia.ismdtoolbackend.exception.ModelProcessingException;
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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.utility.exporter.RdfTestModelFactory.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@DisplayName("ConceptJsonMapper - Type Detection & Mapping")
class ConceptTypeDetectionTest {

    private ConceptJsonMapper processor;

    @BeforeEach
    void setUp() {
        processor = new ConceptJsonMapper();
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
        assertThat(types).isNotNull();
        assertThat(types).as("Should always contain Pojem").contains(POJEM_JSON_LD);
        assertThat(types).as("Should always contain Koncept").contains("Koncept");
        assertThat(types).as("Should contain " + expectedJsonLd).contains(expectedJsonLd);
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
        assertThat(types).contains(expectedJsonLd);
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
        assertThat(types).contains(TRIDA_JSON_LD);
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
        assertThat(types).contains(VZTAH_JSON_LD);
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
        assertThat(types).contains(VLASTNOST_JSON_LD);
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
        assertThat(types).contains(TRIDA_JSON_LD);
        assertThat(types).contains(TSP_JSON_LD);
    }

    static Stream<Arguments> mixedPropertyRelationshipSignals() {
        return Stream.of(
                Arguments.of(OFN_NAMESPACE + VLASTNOST, OWL2.ObjectProperty.getURI()),
                Arguments.of(OFN_NAMESPACE + VZTAH, OWL2.DatatypeProperty.getURI()),
                Arguments.of(OFN_NAMESPACE + VLASTNOST, OFN_NAMESPACE + VZTAH),
                Arguments.of(OWL2.DatatypeProperty.getURI(), OWL2.ObjectProperty.getURI())
        );
    }

    @ParameterizedTest(name = "{0} + {1} is rejected")
    @MethodSource("mixedPropertyRelationshipSignals")
    @DisplayName("A concept typed as both a property and a relationship is rejected")
    void mixedPropertyAndRelationshipSignals_throw(String firstType, String secondType) {
        OntModel model = createDefaultModel();
        Resource concept = addSkosConcept(model, "mixed", "Mixed");
        concept.addProperty(RDF.type, model.createResource(firstType));
        concept.addProperty(RDF.type, model.createResource(secondType));
        ModelStructure structure = createModelStructure(model);

        ModelProcessingException single = assertThatExceptionOfType(ModelProcessingException.class)
                .isThrownBy(() -> processor.processConceptByIri(model, structure, concept.getURI()))
                .actual();
        assertThat(single).hasMessageContaining(concept.getURI());

        // The whole-vocabulary path must fail too, not drop the concept and carry on.
        ModelProcessingException all = assertThatExceptionOfType(ModelProcessingException.class)
                .isThrownBy(() -> processor.processAllConcepts(model, structure))
                .actual();
        assertThat(all).hasMessageContaining(concept.getURI());
    }

    static Stream<Arguments> agreeingSignals() {
        return Stream.of(
                Arguments.of(OFN_NAMESPACE + VLASTNOST, OWL2.DatatypeProperty.getURI(), VLASTNOST_JSON_LD, VZTAH_JSON_LD),
                Arguments.of(OFN_NAMESPACE + VZTAH, OWL2.ObjectProperty.getURI(), VZTAH_JSON_LD, VLASTNOST_JSON_LD)
        );
    }

    @ParameterizedTest(name = "{0} + {1} is exactly {2}")
    @MethodSource("agreeingSignals")
    @DisplayName("Agreeing OFN and OWL types yield the kind exactly once")
    void agreeingOfnAndOwlTypes_yieldTheKindOnce(String ofnType, String owlType, String expected, String absent) {
        OntModel model = createDefaultModel();
        Resource concept = addSkosConcept(model, "agreeing", "Agreeing");
        concept.addProperty(RDF.type, model.createResource(ofnType));
        concept.addProperty(RDF.type, model.createResource(owlType));
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) result.get("typ");
        assertThat(Collections.frequency(types, expected)).isEqualTo(1);
        assertThat(types).doesNotContain(absent);
    }
}
