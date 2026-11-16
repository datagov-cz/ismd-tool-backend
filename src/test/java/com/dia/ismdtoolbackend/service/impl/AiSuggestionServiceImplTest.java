package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.ai.IsmdAiClient;
import com.dia.ismdtoolbackend.client.ai.dto.IsmdAiClassJobRequest;
import com.dia.ismdtoolbackend.client.ai.dto.IsmdAiSelectedClassJobRequest;
import com.dia.ismdtoolbackend.config.AiServiceConfig;
import com.dia.ismdtoolbackend.controller.dto.ai.AiClassSuggestionRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiClassSuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiIdReferenceDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiJobStartResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiKnownConceptualModelDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiSelectedClassSuggestionRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiVocabularySuggestionRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiVocabularySuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.enums.AiJobStatus;
import com.dia.ismdtoolbackend.enums.AiTermType;
import com.dia.ismdtoolbackend.mapper.AiKnownConceptualModelMapper;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.service.OntologyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The AI client is stubbed with the exact request the service must send, so a request with a wrong
 * field matches no stub and fails under strict stubs. The known conceptual model comes from the real
 * mapper over ontology details, and is compared with a model written out by hand.
 */
@ExtendWith(MockitoExtension.class)
class AiSuggestionServiceImplTest {

    private static final String BEARER_TOKEN = "frontend.jwt";
    private static final int YEAR = 2024;
    private static final int NUMBER = 1;
    private static final LocalDate DATE = LocalDate.of(2024, 1, 1);
    private static final String FIRST_ONTOLOGY_SLUG = "zadosti-o-povoleni";
    private static final String SECOND_ONTOLOGY_SLUG = "spravni-organy";
    private static final List<String> ONTOLOGY_SLUGS =
            List.of(FIRST_ONTOLOGY_SLUG, SECOND_ONTOLOGY_SLUG);
    private static final String SELECTED_CLASS_ID = "https://example.org/ontology/zadatel";
    private static final String TARGET_CLASS_ID = "https://example.org/ontology/zadost";
    private static final String LAW_IRI = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2024/1";
    private static final String LAW_PATH = "/eli/cz/sb/2024/1";

    @Mock
    private IsmdAiClient aiClient;

    @Mock
    private OntologyService ontologyService;

    private AiServiceConfig config;

    private AiSuggestionServiceImpl service;

    @BeforeEach
    void setUp() {
        config = new AiServiceConfig();
        config.setSuggestionCount(7);
        service = new AiSuggestionServiceImpl(aiClient, config, ontologyService, new AiKnownConceptualModelMapper());
    }

    @Test
    void startClassSuggestionsAddsConfiguredCountAndMapsCompleteRequest() {
        config.setMaxKnownConceptualModelSlugs(2);
        AiClassSuggestionRequestDto input = new AiClassSuggestionRequestDto(
                List.of("/eli/cz/sb/2024/1/par_2", "/eli/cz/sb/2024/1/par_3"),
                "Kontext tříd",
                ONTOLOGY_SLUGS
        );
        givenKnownOntologies();
        AiJobStartResponseDto expectedResponse = jobResponse();
        when(aiClient.startClassSuggestions(BEARER_TOKEN, YEAR, NUMBER, DATE, new IsmdAiClassJobRequest(
                7, input.structuralElementIds(), input.contextText(), expectedKnownModel())))
                .thenReturn(expectedResponse);

        AiJobStartResponseDto result = service.startClassSuggestions(BEARER_TOKEN, YEAR, NUMBER, DATE, input);

        assertThat(result).isSameAs(expectedResponse);
    }

    @Test
    void startClassSuggestionsLoadsEachDistinctSlugOnce() {
        config.setMaxKnownConceptualModelSlugs(2);
        AiClassSuggestionRequestDto input = new AiClassSuggestionRequestDto(null, null,
                List.of(FIRST_ONTOLOGY_SLUG, SECOND_ONTOLOGY_SLUG, FIRST_ONTOLOGY_SLUG));
        givenKnownOntologies();
        AiJobStartResponseDto expectedResponse = jobResponse();
        when(aiClient.startClassSuggestions(BEARER_TOKEN, YEAR, NUMBER, DATE,
                new IsmdAiClassJobRequest(7, null, null, expectedKnownModel())))
                .thenReturn(expectedResponse);

        assertThat(service.startClassSuggestions(BEARER_TOKEN, YEAR, NUMBER, DATE, input))
                .isSameAs(expectedResponse);
        verify(ontologyService).getOntologyDetail(FIRST_ONTOLOGY_SLUG);
    }

    @Test
    void startClassSuggestionsRejectsMoreThanConfiguredMaximumDistinctSlugsBeforeLoadingOntologies() {
        config.setMaxKnownConceptualModelSlugs(1);
        AiClassSuggestionRequestDto input = new AiClassSuggestionRequestDto(
                null,
                null,
                List.of(FIRST_ONTOLOGY_SLUG, SECOND_ONTOLOGY_SLUG, FIRST_ONTOLOGY_SLUG)
        );

        assertThatThrownBy(() -> service.startClassSuggestions(BEARER_TOKEN, YEAR, NUMBER, DATE, input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Maximální počet různých hodnot parametru knownConceptualModelSlugs je 1.");
        verifyNoInteractions(aiClient, ontologyService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void startClassSuggestionsWithoutKnownModelSlugsForwardsNullKnownModel(List<String> slugs) {
        AiClassSuggestionRequestDto input = new AiClassSuggestionRequestDto(null, null, slugs);
        AiJobStartResponseDto expectedResponse = jobResponse();
        when(aiClient.startClassSuggestions(BEARER_TOKEN, YEAR, NUMBER, DATE,
                new IsmdAiClassJobRequest(7, null, null, null)))
                .thenReturn(expectedResponse);

        AiJobStartResponseDto result = service.startClassSuggestions(BEARER_TOKEN, YEAR, NUMBER, DATE, input);

        assertThat(result).isSameAs(expectedResponse);
        verifyNoInteractions(ontologyService);
    }

    @Test
    void startPropertySuggestionsAddsConfiguredCountAndMapsCompleteRequest() {
        AiSelectedClassSuggestionRequestDto input = new AiSelectedClassSuggestionRequestDto(
                SELECTED_CLASS_ID,
                List.of("/eli/cz/sb/2024/1/par_2"),
                "Kontext vlastností",
                ONTOLOGY_SLUGS
        );
        givenKnownOntologies();
        AiJobStartResponseDto expectedResponse = jobResponse();
        when(aiClient.startPropertySuggestions(BEARER_TOKEN, YEAR, NUMBER, DATE, expectedSelectedRequest(input)))
                .thenReturn(expectedResponse);

        AiJobStartResponseDto result = service.startPropertySuggestions(BEARER_TOKEN, YEAR, NUMBER, DATE, input);

        assertThat(result).isSameAs(expectedResponse);
    }

    @Test
    void startRelationshipSuggestionsAddsConfiguredCountAndMapsCompleteRequest() {
        AiSelectedClassSuggestionRequestDto input = new AiSelectedClassSuggestionRequestDto(
                SELECTED_CLASS_ID,
                List.of("/eli/cz/sb/2024/1/par_3"),
                "Kontext vztahů",
                ONTOLOGY_SLUGS
        );
        givenKnownOntologies();
        AiJobStartResponseDto expectedResponse = jobResponse();
        when(aiClient.startRelationshipSuggestions(BEARER_TOKEN, YEAR, NUMBER, DATE, expectedSelectedRequest(input)))
                .thenReturn(expectedResponse);

        AiJobStartResponseDto result =
                service.startRelationshipSuggestions(BEARER_TOKEN, YEAR, NUMBER, DATE, input);

        assertThat(result).isSameAs(expectedResponse);
    }

    @Test
    void getSuggestionsAcceptsConfiguredMaximumJobIds() {
        config.setMaxJobIds(2);
        List<UUID> jobIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        List<AiClassSuggestionsJobResponseDto> expectedResponse = List.of();
        when(aiClient.getClassSuggestions(BEARER_TOKEN, jobIds)).thenReturn(expectedResponse);

        assertThat(service.getClassSuggestions(BEARER_TOKEN, jobIds)).isSameAs(expectedResponse);
    }

    @Test
    void getSuggestionsRejectsMoreThanConfiguredMaximumJobIds() {
        config.setMaxJobIds(1);
        List<UUID> jobIds = List.of(UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> service.getClassSuggestions(BEARER_TOKEN, jobIds))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getPropertySuggestions(BEARER_TOKEN, jobIds))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getRelationshipSuggestions(BEARER_TOKEN, jobIds))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(aiClient);
    }

    @Test
    void vocabularyStartForwardsRequestWithoutCreatingOrLoadingOntologies() {
        var input = new AiVocabularySuggestionRequestDto(2, 0, 1, null, "Vozidla", expectedKnownModel());
        var expected = jobResponse();
        when(aiClient.startVocabularySuggestions(BEARER_TOKEN, YEAR, NUMBER, DATE, input)).thenReturn(expected);

        assertThat(service.startVocabularySuggestions(BEARER_TOKEN, YEAR, NUMBER, DATE, input)).isSameAs(expected);
        verifyNoInteractions(ontologyService);
    }

    @Test
    void vocabularyPollingEnforcesExistingJobIdLimit() {
        config.setMaxJobIds(1);
        var ids = List.of(UUID.randomUUID());
        List<AiVocabularySuggestionsJobResponseDto> expected = List.of();
        when(aiClient.getVocabularySuggestions(BEARER_TOKEN, ids)).thenReturn(expected);

        assertThat(service.getVocabularySuggestions(BEARER_TOKEN, ids)).isSameAs(expected);
        assertThatThrownBy(() -> service.getVocabularySuggestions(
                BEARER_TOKEN, List.of(UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(ontologyService);
    }

    private IsmdAiSelectedClassJobRequest expectedSelectedRequest(AiSelectedClassSuggestionRequestDto input) {
        return new IsmdAiSelectedClassJobRequest(
                7,
                input.selectedClassId(),
                input.structuralElementIds(),
                input.contextText(),
                expectedKnownModel()
        );
    }

    /** The class and attribute live in the first ontology, the relationship in the second. */
    private void givenKnownOntologies() {
        ConceptDetailModel zadatel = ConceptDetailModel.builder()
                .iri(SELECTED_CLASS_ID)
                .types(List.of("Pojem", "Třída", "Typ subjektu práva"))
                .name(Map.of("cs", "Žadatel", "en", "Applicant"))
                .definition(Map.of("cs", "Osoba podávající žádost.", "en", "A person filing an application."))
                .description(Map.of("cs", "Známá třída žadatele.", "en", "Known applicant class."))
                .broaderClasses(List.of("https://example.org/ontology/osoba",
                        "https://example.org/ontology/subjekt-prava"))
                .definingLegalSources(List.of(LAW_IRI + "/2024-01-01/dokument/norma/par_2"))
                .build();
        ConceptDetailModel jmeno = ConceptDetailModel.builder()
                .iri("https://example.org/ontology/jmeno")
                .types(List.of("Pojem", "Vlastnost"))
                .domain(SELECTED_CLASS_ID)
                .name(Map.of("cs", "jméno", "en", "name"))
                .definition(Map.of("cs", "Jméno žadatele.", "en", "Applicant name."))
                .description(Map.of("cs", "Atribut jména.", "en", "Name attribute."))
                .definingLegalSources(List.of(LAW_IRI + "/2024-01-01/dokument/norma/par_3"))
                .build();
        ConceptDetailModel podava = ConceptDetailModel.builder()
                .iri("https://example.org/ontology/podava")
                .types(List.of("Pojem", "Vztah"))
                .domain(SELECTED_CLASS_ID)
                .range(TARGET_CLASS_ID)
                .name(Map.of("cs", "podává", "en", "files"))
                .definition(Map.of("cs", "Žadatel podává žádost.", "en", "An applicant files an application."))
                .description(Map.of("cs", "Vztah mezi žadatelem a žádostí.",
                        "en", "Applicant-to-application relation."))
                .definingLegalSources(List.of(LAW_IRI + "/2024-01-01/dokument/norma/par_4"))
                .build();
        when(ontologyService.getOntologyDetail(FIRST_ONTOLOGY_SLUG)).thenReturn(OntologyDetailModel.builder()
                .iri("https://example.org/ontology/requests")
                .concepts(List.of(zadatel, jmeno))
                .build());
        when(ontologyService.getOntologyDetail(SECOND_ONTOLOGY_SLUG)).thenReturn(OntologyDetailModel.builder()
                .iri("https://example.org/ontology/authorities")
                .concepts(List.of(podava))
                .build());
    }

    /** What {@link #givenKnownOntologies()} maps to. */
    private AiKnownConceptualModelDto expectedKnownModel() {
        return new AiKnownConceptualModelDto(
                List.of(new AiKnownConceptualModelDto.KnownClassTermDto(
                        SELECTED_CLASS_ID,
                        Map.of("cs", "Žadatel", "en", "Applicant"),
                        Map.of("cs", "Osoba podávající žádost.", "en", "A person filing an application."),
                        Map.of("cs", "Známá třída žadatele.", "en", "Known applicant class."),
                        AiTermType.SUBJECT,
                        List.of(
                                new AiIdReferenceDto("https://example.org/ontology/osoba"),
                                new AiIdReferenceDto("https://example.org/ontology/subjekt-prava")
                        ),
                        LAW_PATH
                )),
                List.of(new AiKnownConceptualModelDto.KnownAttributeTermDto(
                        "https://example.org/ontology/jmeno",
                        new AiIdReferenceDto(SELECTED_CLASS_ID),
                        Map.of("cs", "jméno", "en", "name"),
                        Map.of("cs", "Jméno žadatele.", "en", "Applicant name."),
                        Map.of("cs", "Atribut jména.", "en", "Name attribute."),
                        LAW_PATH
                )),
                List.of(new AiKnownConceptualModelDto.KnownRelationshipTermDto(
                        "https://example.org/ontology/podava",
                        new AiIdReferenceDto(SELECTED_CLASS_ID),
                        new AiIdReferenceDto(TARGET_CLASS_ID),
                        Map.of("cs", "podává", "en", "files"),
                        Map.of("cs", "Žadatel podává žádost.", "en", "An applicant files an application."),
                        Map.of("cs", "Vztah mezi žadatelem a žádostí.", "en", "Applicant-to-application relation."),
                        LAW_PATH
                ))
        );
    }

    private AiJobStartResponseDto jobResponse() {
        return new AiJobStartResponseDto(UUID.randomUUID(), AiJobStatus.IN_PROGRESS);
    }
}
