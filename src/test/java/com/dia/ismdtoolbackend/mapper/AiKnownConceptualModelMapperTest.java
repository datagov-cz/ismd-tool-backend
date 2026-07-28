package com.dia.ismdtoolbackend.mapper;

import com.dia.ismdtoolbackend.controller.dto.ai.AiIdReferenceDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiKnownConceptualModelDto;
import com.dia.ismdtoolbackend.enums.AiTermType;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
}
