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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VocabularyConceptBuilderTest {
    @Test
    void resolvesReferencesAndPreservesReviewedMetadataWithoutMutatingRequest() {
        var request = sample();
        var result = VocabularyConceptBuilder.prepare(request, GRAPH, "user");
        assertThat(result.concepts()).hasSize(4);
        var attribute = (PropertyConceptModel) result.concepts().get(2);
        var relationship = (RelationshipConceptModel) result.concepts().get(3);
        assertThat(attribute.getDomain()).isEqualTo(result.conceptIris().get("vehicle"));
        assertThat(relationship.getDomain()).isEqualTo(result.conceptIris().get("driver"));
        assertThat(relationship.getRange()).isEqualTo(result.conceptIris().get("vehicle"));
        assertThat(attribute.getDescriptionModel().getDescription()).isEqualTo(Map.of("cs", "Hmotnost v kilogramech"));
        assertThat(attribute.getDefinitionModel().getDefinition()).isEqualTo(Map.of("cs", "Hmotnost vozidla"));
        assertThat(attribute.getDefiningLegalSource())
                .isEqualTo(List.of(LEGAL.replace("https://e-sbirka.gov.cz", "https://opendata.eselpoint.gov.cz/esel-esb")));
        assertThat(attribute.getDataType()).isEqualTo("xsd:decimal");
        assertThat(request.attributes().get(0).associatedClass().ref()).isEqualTo("vehicle");
        assertThat(result.conceptIris().values().stream().allMatch(iri -> iri.startsWith(GRAPH + "/"))).isTrue();
    }

    @Test
    void handlesForwardSpecialization() {
        var source = sample();
        var child = clazz("car", "Auto");
        child = new ClassDto(child.ref(), child.name(), child.definition(), child.explanation(), child.type(), List.of(ref("vehicle")), LEGAL);
        var request = new OntologyCreateWithConceptsRequestDto(source.ontology(), List.of(child, clazz("vehicle", "Vozidlo")), List.of(), List.of());
        var result = VocabularyConceptBuilder.prepare(request, GRAPH, "user");
        assertThat(((ClassConceptModel) result.concepts().get(0)).getBroaderConcept())
                .isEqualTo(List.of(result.conceptIris().get("vehicle")));
    }

    @ParameterizedTest
    @MethodSource("invalidReferences")
    void rejectsDanglingOrMalformedClassReferences(AiConceptReferenceDto reference) {
        var source = sample();
        var attribute = new AttributeDto("weight", reference, Map.of("cs", "Hmotnost"), null, null, null, null);
        var request = new OntologyCreateWithConceptsRequestDto(source.ontology(), source.classes(), List.of(attribute), List.of());
        assertThatThrownBy(() -> VocabularyConceptBuilder.prepare(request, GRAPH, "user"))
                .isInstanceOf(ConceptValidationException.class);
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
        assertThat(((RelationshipConceptModel) result.concepts().get(2)).getRange()).isEqualTo(external);
    }

    @Test
    void rejectsDuplicateRefsAcrossTypes() {
        var source = sample();
        var attribute = new AttributeDto("vehicle", ref("driver"), Map.of("cs", "Hmotnost"), null, null, null, null);
        assertThatThrownBy(() -> VocabularyConceptBuilder.prepare(
                new OntologyCreateWithConceptsRequestDto(source.ontology(), source.classes(), List.of(attribute), List.of()), GRAPH, "user"))
                .isInstanceOf(ConceptValidationException.class);
    }

    @Test
    void rejectsNamesMappingToSameIri() {
        var source = sample();
        var classes = List.of(clazz("a", "Stejné"), clazz("b", "stejné"));
        assertThatThrownBy(() -> VocabularyConceptBuilder.prepare(
                new OntologyCreateWithConceptsRequestDto(source.ontology(), classes, List.of(), List.of()), GRAPH, "user"))
                .isInstanceOf(ConceptValidationException.class);
    }

    @Test
    void rejectsInvalidNamesAndLegalSourcesUsingOrdinaryCreateValidation() {
        var source = sample();
        for (var term : List.of(new ClassDto("a", Map.of("en", "Car"), null, null, source.classes().get(0).type(), List.of(), null),
                new ClassDto("a", Map.of("cs", "Auto"), null, null, source.classes().get(0).type(), List.of(), "https://invalid.example.org/law"))) {
            assertThatThrownBy(() -> VocabularyConceptBuilder.prepare(
                    new OntologyCreateWithConceptsRequestDto(source.ontology(), List.of(term), List.of(), List.of()), GRAPH, "user"))
                    .isInstanceOf(ConceptValidationException.class);
        }
    }

    @Test
    void rejectsSpecializationCyclesButAllowsInverseRelationships() {
        var source = sample();
        var a = new ClassDto("a", Map.of("cs", "A"), null, null, source.classes().get(0).type(), List.of(ref("b")), null);
        var b = new ClassDto("b", Map.of("cs", "B"), null, null, a.type(), List.of(ref("a")), null);
        assertThatThrownBy(() -> VocabularyConceptBuilder.prepare(
                new OntologyCreateWithConceptsRequestDto(source.ontology(), List.of(a, b), List.of(), List.of()), GRAPH, "user"))
                .isInstanceOf(ConceptValidationException.class);
        var inverse = new RelationshipDto("drivenBy", ref("vehicle"), ref("driver"), Map.of("cs", "je řízeno"), null, null, null);
        assertThat(VocabularyConceptBuilder.prepare(new OntologyCreateWithConceptsRequestDto(source.ontology(), source.classes(), List.of(),
                List.of(source.relationships().get(0), inverse)), GRAPH, "user").concepts())
                .hasSize(4);
    }

    @Test
    void acceptsEmptySelection() {
        var request = new OntologyCreateWithConceptsRequestDto(sample().ontology(), List.of(), List.of(), List.of());
        assertThat(VocabularyConceptBuilder.prepare(request, GRAPH, "user").concepts()).isEmpty();
    }
}
