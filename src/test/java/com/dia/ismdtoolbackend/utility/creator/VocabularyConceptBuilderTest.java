package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.controller.dto.OntologyCreateWithConceptsRequestDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyCreateWithConceptsRequestDto.*;
import com.dia.ismdtoolbackend.controller.dto.ai.AiConceptReferenceDto;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.models.concept.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import static com.dia.ismdtoolbackend.support.VocabularyCreationRequests.*;
import static org.junit.jupiter.api.Assertions.*;

class VocabularyConceptBuilderTest {
    @Test
    void resolvesReferencesAndPreservesReviewedMetadataWithoutMutatingRequest() {
        var request = sample();
        var result = VocabularyConceptBuilder.prepare(request, GRAPH, "user");
        assertEquals(4, result.concepts().size());
        var attribute = (PropertyConceptModel) result.concepts().get(2);
        var relationship = (RelationshipConceptModel) result.concepts().get(3);
        assertEquals(result.conceptIris().get("vehicle"), attribute.getDomain());
        assertEquals(result.conceptIris().get("driver"), relationship.getDomain());
        assertEquals(result.conceptIris().get("vehicle"), relationship.getRange());
        assertEquals(Map.of("cs", "Hmotnost v kilogramech"), attribute.getDescriptionModel().getDescription());
        assertEquals(Map.of("cs", "Hmotnost vozidla"), attribute.getDefinitionModel().getDefinition());
        assertEquals(List.of(LEGAL.replace("https://e-sbirka.gov.cz", "https://opendata.eselpoint.gov.cz/esel-esb")), attribute.getDefiningLegalSource());
        assertEquals("xsd:decimal", attribute.getDataType());
        assertEquals("vehicle", request.attributes().get(0).associatedClass().ref());
        assertTrue(result.conceptIris().values().stream().allMatch(iri -> iri.startsWith(GRAPH + "/")));
    }

    @Test
    void handlesForwardSpecialization() {
        var source = sample();
        var child = clazz("car", "Auto");
        child = new ClassDto(child.ref(), child.name(), child.definition(), child.explanation(), child.type(), List.of(ref("vehicle")), LEGAL);
        var request = new OntologyCreateWithConceptsRequestDto(source.ontology(), List.of(child, clazz("vehicle", "Vozidlo")), List.of(), List.of());
        var result = VocabularyConceptBuilder.prepare(request, GRAPH, "user");
        assertEquals(List.of(result.conceptIris().get("vehicle")), ((ClassConceptModel) result.concepts().get(0)).getBroaderConcept());
    }

    @ParameterizedTest
    @MethodSource("invalidReferences")
    void rejectsDanglingOrMalformedClassReferences(AiConceptReferenceDto reference) {
        var source = sample();
        var attribute = new AttributeDto("weight", reference, Map.of("cs", "Hmotnost"), null, null, null, null);
        var request = new OntologyCreateWithConceptsRequestDto(source.ontology(), source.classes(), List.of(attribute), List.of());
        assertThrows(ConceptValidationException.class, () -> VocabularyConceptBuilder.prepare(request, GRAPH, "user"));
    }

    static Stream<AiConceptReferenceDto> invalidReferences() {
        return Stream.of(null, ref("unselected"), ref("weight"), new AiConceptReferenceDto(null, null),
                new AiConceptReferenceDto("vehicle", "https://example.org/existing"),
                new AiConceptReferenceDto(null, "relative"), new AiConceptReferenceDto(null, GRAPH + "/missing"));
    }

    @Test
    void preservesExternalIriReferencesAsInOrdinaryCreate() {
        var source = sample();
        String external = "https://other.example.org/Class";
        var relationship = new RelationshipDto("link", ref("vehicle"), new AiConceptReferenceDto(null, external), Map.of("cs", "Odkaz"), null, null, null);
        var result = VocabularyConceptBuilder.prepare(new OntologyCreateWithConceptsRequestDto(source.ontology(), source.classes(), List.of(), List.of(relationship)), GRAPH, "user");
        assertEquals(external, ((RelationshipConceptModel) result.concepts().get(2)).getRange());
    }

    @Test
    void rejectsDuplicateRefsAcrossTypes() {
        var source = sample();
        var attribute = new AttributeDto("vehicle", ref("driver"), Map.of("cs", "Hmotnost"), null, null, null, null);
        assertThrows(ConceptValidationException.class, () -> VocabularyConceptBuilder.prepare(
                new OntologyCreateWithConceptsRequestDto(source.ontology(), source.classes(), List.of(attribute), List.of()), GRAPH, "user"));
    }

    @Test
    void rejectsNamesMappingToSameIri() {
        var source = sample();
        var classes = List.of(clazz("a", "Stejné"), clazz("b", "stejné"));
        assertThrows(ConceptValidationException.class, () -> VocabularyConceptBuilder.prepare(
                new OntologyCreateWithConceptsRequestDto(source.ontology(), classes, List.of(), List.of()), GRAPH, "user"));
    }

    @Test
    void rejectsInvalidNamesAndLegalSourcesUsingOrdinaryCreateValidation() {
        var source = sample();
        for (var term : List.of(new ClassDto("a", Map.of("en", "Car"), null, null, source.classes().get(0).type(), List.of(), null),
                new ClassDto("a", Map.of("cs", "Auto"), null, null, source.classes().get(0).type(), List.of(), "https://invalid.example.org/law"))) {
            assertThrows(ConceptValidationException.class, () -> VocabularyConceptBuilder.prepare(
                    new OntologyCreateWithConceptsRequestDto(source.ontology(), List.of(term), List.of(), List.of()), GRAPH, "user"));
        }
    }

    @Test
    void rejectsSpecializationCyclesButAllowsInverseRelationships() {
        var source = sample();
        var a = new ClassDto("a", Map.of("cs", "A"), null, null, source.classes().get(0).type(), List.of(ref("b")), null);
        var b = new ClassDto("b", Map.of("cs", "B"), null, null, a.type(), List.of(ref("a")), null);
        assertThrows(ConceptValidationException.class, () -> VocabularyConceptBuilder.prepare(
                new OntologyCreateWithConceptsRequestDto(source.ontology(), List.of(a, b), List.of(), List.of()), GRAPH, "user"));
        var inverse = new RelationshipDto("drivenBy", ref("vehicle"), ref("driver"), Map.of("cs", "je řízeno"), null, null, null);
        assertEquals(4, VocabularyConceptBuilder.prepare(new OntologyCreateWithConceptsRequestDto(source.ontology(), source.classes(), List.of(),
                List.of(source.relationships().get(0), inverse)), GRAPH, "user").concepts().size());
    }

    @Test
    void acceptsEmptySelection() {
        var request = new OntologyCreateWithConceptsRequestDto(sample().ontology(), List.of(), List.of(), List.of());
        assertTrue(VocabularyConceptBuilder.prepare(request, GRAPH, "user").concepts().isEmpty());
    }
}
