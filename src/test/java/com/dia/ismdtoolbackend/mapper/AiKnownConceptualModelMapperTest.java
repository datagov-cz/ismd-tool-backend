package com.dia.ismdtoolbackend.mapper;

import com.dia.ismdtoolbackend.controller.dto.ai.AiIdReferenceDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiKnownConceptualModelDto;
import com.dia.ismdtoolbackend.enums.AiTermType;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Arrays;
import java.util.Map;

import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE;
import static com.dia.constants.VocabularyConstants.TOP;
import static com.dia.constants.VocabularyConstants.TOP_JSON_LD;
import static com.dia.constants.VocabularyConstants.TSP;
import static com.dia.constants.VocabularyConstants.TSP_JSON_LD;
import static org.assertj.core.api.Assertions.assertThat;

class AiKnownConceptualModelMapperTest {

    private static final String LAW_IRI =
            "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2024/1";

    private final AiKnownConceptualModelMapper mapper = new AiKnownConceptualModelMapper();

    @Test
    void mapsOntologyConceptsToAiKnownConceptualModel() {
        OntologyDetailModel.ConceptDetailModel knownClass = OntologyDetailModel.ConceptDetailModel.builder()
                .iri("https://example.test/pojem/zadatel")
                .types(List.of("Pojem", "Třída", "Typ subjektu práva"))
                .name(Map.of("cs", "Žadatel"))
                .definition(Map.of("cs", "Osoba podávající žádost."))
                .description(Map.of("cs", "Známá třída."))
                .broaderClasses(List.of("https://example.test/pojem/osoba"))
                .definingLegalSources(List.of("", LAW_IRI + "/2024-01-01/dokument/norma/par_2"))
                .build();
        OntologyDetailModel.ConceptDetailModel knownAttribute = OntologyDetailModel.ConceptDetailModel.builder()
                .iri("https://example.test/pojem/jmeno")
                .types(List.of("Pojem", "Vlastnost"))
                .domain(knownClass.getIri())
                .name(Map.of("cs", "jméno"))
                .definition(Map.of("cs", "Jméno žadatele."))
                .description(Map.of("cs", "Známý atribut."))
                .definingLegalSources(List.of(LAW_IRI + "/2024-01-01/dokument/norma/par_3"))
                .build();
        OntologyDetailModel.ConceptDetailModel knownRelationship =
                OntologyDetailModel.ConceptDetailModel.builder()
                        .iri("https://example.test/pojem/podava")
                        .types(List.of("Pojem", "Vztah"))
                        .domain(knownClass.getIri())
                        .range("https://example.test/pojem/zadost")
                        .name(Map.of("cs", "podává"))
                        .definition(Map.of("cs", "Žadatel podává žádost."))
                        .description(Map.of("cs", "Známý vztah."))
                        .definingLegalSources(List.of(LAW_IRI + "/2024-01-01/dokument/norma/par_4"))
                        .build();
        OntologyDetailModel.ConceptDetailModel genericConcept =
                OntologyDetailModel.ConceptDetailModel.builder()
                        .iri("https://example.test/pojem/obecny")
                        .types(List.of("Pojem"))
                        .build();

        OntologyDetailModel firstOntology = OntologyDetailModel.builder()
                .concepts(List.of(knownClass, knownAttribute))
                .build();
        OntologyDetailModel secondOntology = OntologyDetailModel.builder()
                .concepts(List.of(knownClass, knownRelationship, genericConcept))
                .build();

        AiKnownConceptualModelDto result = mapper.map(List.of(firstOntology, secondOntology));

        assertThat(result.classes()).containsExactly(
                new AiKnownConceptualModelDto.KnownClassTermDto(
                        knownClass.getIri(),
                        knownClass.getName(),
                        knownClass.getDefinition(),
                        knownClass.getDescription(),
                        AiTermType.SUBJECT,
                        List.of(new AiIdReferenceDto("https://example.test/pojem/osoba")),
                        "/eli/cz/sb/2024/1"
                )
        );
        assertThat(result.attributes()).containsExactly(
                new AiKnownConceptualModelDto.KnownAttributeTermDto(
                        knownAttribute.getIri(),
                        new AiIdReferenceDto(knownClass.getIri()),
                        knownAttribute.getName(),
                        knownAttribute.getDefinition(),
                        knownAttribute.getDescription(),
                        "/eli/cz/sb/2024/1"
                )
        );
        assertThat(result.relationships()).containsExactly(
                new AiKnownConceptualModelDto.KnownRelationshipTermDto(
                        knownRelationship.getIri(),
                        new AiIdReferenceDto(knownClass.getIri()),
                        new AiIdReferenceDto("https://example.test/pojem/zadost"),
                        knownRelationship.getName(),
                        knownRelationship.getDefinition(),
                        knownRelationship.getDescription(),
                        "/eli/cz/sb/2024/1"
                )
        );
    }

    @Test
    void mapsMissingConceptListToEmptyModel() {
        AiKnownConceptualModelDto result = mapper.map(List.of(OntologyDetailModel.builder().build()));

        assertThat(result.classes()).isEmpty();
        assertThat(result.attributes()).isEmpty();
        assertThat(result.relationships()).isEmpty();
    }

    @Test
    void nullDetailListAndNullEntriesMapToAnEmptyModel() {
        assertThat(mapper.map(null).classes()).isEmpty();

        AiKnownConceptualModelDto result = mapper.map(Arrays.asList(null, OntologyDetailModel.builder().build()));

        assertThat(result.classes()).isEmpty();
        assertThat(result.attributes()).isEmpty();
        assertThat(result.relationships()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "subject (JSON-LD), TSP_JSON_LD, SUBJECT",
            "subject (IRI), TSP_IRI, SUBJECT",
            "object (JSON-LD), TOP_JSON_LD, OBJECT",
            "object (IRI), TOP_IRI, OBJECT",
            "plain class, NONE, CLASS"
    })
    void classTypeComesFromTheSubjectOrObjectMarker(String name, String marker, AiTermType expected) {
        List<String> types = switch (marker) {
            case "TSP_JSON_LD" -> List.of("Třída", TSP_JSON_LD);
            case "TSP_IRI" -> List.of("Třída", OFN_NAMESPACE + TSP);
            case "TOP_JSON_LD" -> List.of("Třída", TOP_JSON_LD);
            case "TOP_IRI" -> List.of("Třída", OFN_NAMESPACE + TOP);
            default -> List.of("Třída");
        };

        AiKnownConceptualModelDto result = mapper.map(List.of(ontologyOf(
                OntologyDetailModel.ConceptDetailModel.builder().iri("https://example.test/pojem/t").types(types).build())));

        assertThat(result.classes()).singleElement().extracting(AiKnownConceptualModelDto.KnownClassTermDto::type)
                .isEqualTo(expected);
    }

    @Test
    void blankReferencesAndSourcesAreLeftOut() {
        OntologyDetailModel.ConceptDetailModel clazz = OntologyDetailModel.ConceptDetailModel.builder()
                .iri("https://example.test/pojem/trida")
                .types(List.of("Třída"))
                .broaderClasses(Arrays.asList("", null, "https://example.test/pojem/nadrazena"))
                .definingLegalSources(Arrays.asList(null, "  "))
                .build();
        OntologyDetailModel.ConceptDetailModel attribute = OntologyDetailModel.ConceptDetailModel.builder()
                .iri("https://example.test/pojem/atribut")
                .types(List.of("Vlastnost"))
                .domain(" ")
                .build();
        OntologyDetailModel.ConceptDetailModel relationship = OntologyDetailModel.ConceptDetailModel.builder()
                .iri("https://example.test/pojem/vztah")
                .types(List.of("Vztah"))
                .range("")
                .build();

        AiKnownConceptualModelDto result = mapper.map(List.of(ontologyOf(clazz, attribute, relationship)));

        assertThat(result.classes()).singleElement().satisfies(c -> {
            assertThat(c.specializes()).containsExactly(new AiIdReferenceDto("https://example.test/pojem/nadrazena"));
            assertThat(c.legalAct()).isNull();
        });
        assertThat(result.attributes()).singleElement().satisfies(a -> assertThat(a.associatedClass()).isNull());
        assertThat(result.relationships()).singleElement().satisfies(r -> {
            assertThat(r.sourceClass()).isNull();
            assertThat(r.targetClass()).isNull();
        });
    }

    @Test
    void classWithoutBroaderClassesSpecializesNothing() {
        AiKnownConceptualModelDto result = mapper.map(List.of(ontologyOf(
                OntologyDetailModel.ConceptDetailModel.builder().iri("https://example.test/pojem/t")
                        .types(List.of("Třída")).build())));

        assertThat(result.classes()).singleElement().satisfies(c -> assertThat(c.specializes()).isEmpty());
    }

    @Test
    void legalSourceThatIsNotAnEliIsPassedAsGiven() {
        OntologyDetailModel.ConceptDetailModel clazz = OntologyDetailModel.ConceptDetailModel.builder()
                .iri("https://example.test/pojem/trida")
                .types(List.of("Třída"))
                .definingLegalSources(List.of("https://example.test/not-an-eli"))
                .build();

        AiKnownConceptualModelDto result = mapper.map(List.of(ontologyOf(clazz)));

        assertThat(result.classes()).singleElement().satisfies(c ->
                assertThat(c.legalAct()).isEqualTo("https://example.test/not-an-eli"));
    }

    private static OntologyDetailModel ontologyOf(OntologyDetailModel.ConceptDetailModel... concepts) {
        return OntologyDetailModel.builder().concepts(List.of(concepts)).build();
    }
}
