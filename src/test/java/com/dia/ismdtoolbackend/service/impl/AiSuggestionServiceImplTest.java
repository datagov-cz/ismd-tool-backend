package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.ai.IsmdAiClient;
import com.dia.ismdtoolbackend.client.ai.dto.IsmdAiClassJobRequest;
import com.dia.ismdtoolbackend.client.ai.dto.IsmdAiSelectedClassJobRequest;
import com.dia.ismdtoolbackend.config.AiServiceConfig;
import com.dia.ismdtoolbackend.controller.dto.ai.AiClassSuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiClassSuggestionRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiIdReferenceDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiJobStartResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiVocabularySuggestionRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiVocabularySuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiKnownConceptualModelDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiSelectedClassSuggestionRequestDto;
import com.dia.ismdtoolbackend.enums.AiJobStatus;
import com.dia.ismdtoolbackend.enums.AiTermType;
import com.dia.ismdtoolbackend.mapper.AiKnownConceptualModelMapper;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.service.OntologyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

    @Mock
    private IsmdAiClient aiClient;

    @Mock
    private OntologyService ontologyService;

    @Mock
    private AiKnownConceptualModelMapper knownConceptualModelMapper;

    private AiServiceConfig config;

    private AiSuggestionServiceImpl service;

    @BeforeEach
    void setUp() {
        config = new AiServiceConfig();
        config.setSuggestionCount(7);
        service = new AiSuggestionServiceImpl(aiClient, config, ontologyService, knownConceptualModelMapper);
    }

    @Test
    void startClassSuggestionsAddsConfiguredCountAndMapsCompleteRequest() {
        config.setMaxKnownConceptualModelSlugs(2);
        AiKnownConceptualModelDto knownModel = knownModelDto();
        AiClassSuggestionRequestDto input = new AiClassSuggestionRequestDto(
                List.of("/eli/cz/sb/2024/1/par_2", "/eli/cz/sb/2024/1/par_3"),
                "Kontext tříd",
                ONTOLOGY_SLUGS
        );
        mockKnownConceptualModel(knownModel);
        AiJobStartResponseDto expectedResponse = jobResponse();
        when(aiClient.startClassSuggestions(
                eq(BEARER_TOKEN),
                eq(YEAR),
                eq(NUMBER),
                eq(DATE),
                any(IsmdAiClassJobRequest.class)
        )).thenReturn(expectedResponse);

        AiJobStartResponseDto result = service.startClassSuggestions(
                BEARER_TOKEN,
                YEAR,
                NUMBER,
                DATE,
                input
        );

        assertSame(expectedResponse, result);
        ArgumentCaptor<IsmdAiClassJobRequest> requestCaptor = ArgumentCaptor.forClass(IsmdAiClassJobRequest.class);
        verify(aiClient).startClassSuggestions(
                eq(BEARER_TOKEN),
                eq(YEAR),
                eq(NUMBER),
                eq(DATE),
                requestCaptor.capture()
        );
        IsmdAiClassJobRequest capturedRequest = requestCaptor.getValue();
        assertEquals(
                new IsmdAiClassJobRequest(
                        7,
                        input.structuralElementIds(),
                        input.contextText(),
                        knownModel
                ),
                capturedRequest
        );
        assertSame(knownModel, capturedRequest.knownConceptualModel());
    }

    @Test
    void startClassSuggestionsRejectsMoreThanConfiguredMaximumDistinctSlugsBeforeLoadingOntologies() {
        config.setMaxKnownConceptualModelSlugs(1);
        AiClassSuggestionRequestDto input = new AiClassSuggestionRequestDto(
                null,
                null,
                List.of(FIRST_ONTOLOGY_SLUG, SECOND_ONTOLOGY_SLUG, FIRST_ONTOLOGY_SLUG)
        );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.startClassSuggestions(BEARER_TOKEN, YEAR, NUMBER, DATE, input)
        );

        assertEquals(
                "Maximální počet různých hodnot parametru knownConceptualModelSlugs je 1.",
                exception.getMessage()
        );
        verifyNoInteractions(aiClient, ontologyService, knownConceptualModelMapper);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void startClassSuggestionsWithoutKnownModelSlugsForwardsNullKnownModel(List<String> slugs) {
        AiClassSuggestionRequestDto input = new AiClassSuggestionRequestDto(null, null, slugs);
        AiJobStartResponseDto expectedResponse = jobResponse();
        when(aiClient.startClassSuggestions(
                eq(BEARER_TOKEN),
                eq(YEAR),
                eq(NUMBER),
                eq(DATE),
                any(IsmdAiClassJobRequest.class)
        )).thenReturn(expectedResponse);

        AiJobStartResponseDto result = service.startClassSuggestions(
                BEARER_TOKEN,
                YEAR,
                NUMBER,
                DATE,
                input
        );

        assertSame(expectedResponse, result);
        ArgumentCaptor<IsmdAiClassJobRequest> requestCaptor =
                ArgumentCaptor.forClass(IsmdAiClassJobRequest.class);
        verify(aiClient).startClassSuggestions(
                eq(BEARER_TOKEN),
                eq(YEAR),
                eq(NUMBER),
                eq(DATE),
                requestCaptor.capture()
        );
        assertNull(requestCaptor.getValue().knownConceptualModel());
        verifyNoInteractions(ontologyService, knownConceptualModelMapper);
    }

    @Test
    void startPropertySuggestionsAddsConfiguredCountAndMapsCompleteRequest() {
        AiSelectedClassSuggestionRequestDto input = new AiSelectedClassSuggestionRequestDto(
                SELECTED_CLASS_ID,
                List.of("/eli/cz/sb/2024/1/par_2"),
                "Kontext vlastností",
                ONTOLOGY_SLUGS
        );
        AiKnownConceptualModelDto knownModel = knownModelDto();
        mockKnownConceptualModel(knownModel);
        AiJobStartResponseDto expectedResponse = jobResponse();
        when(aiClient.startPropertySuggestions(
                eq(BEARER_TOKEN),
                eq(YEAR),
                eq(NUMBER),
                eq(DATE),
                any(IsmdAiSelectedClassJobRequest.class)
        )).thenReturn(expectedResponse);

        AiJobStartResponseDto result = service.startPropertySuggestions(
                BEARER_TOKEN,
                YEAR,
                NUMBER,
                DATE,
                input
        );

        assertSame(expectedResponse, result);
        ArgumentCaptor<IsmdAiSelectedClassJobRequest> requestCaptor =
                ArgumentCaptor.forClass(IsmdAiSelectedClassJobRequest.class);
        verify(aiClient).startPropertySuggestions(
                eq(BEARER_TOKEN),
                eq(YEAR),
                eq(NUMBER),
                eq(DATE),
                requestCaptor.capture()
        );
        IsmdAiSelectedClassJobRequest capturedRequest = requestCaptor.getValue();
        assertEquals(expectedSelectedRequest(input, knownModel), capturedRequest);
        assertSame(knownModel, capturedRequest.knownConceptualModel());
    }

    @Test
    void startRelationshipSuggestionsAddsConfiguredCountAndMapsCompleteRequest() {
        AiSelectedClassSuggestionRequestDto input = new AiSelectedClassSuggestionRequestDto(
                SELECTED_CLASS_ID,
                List.of("/eli/cz/sb/2024/1/par_3"),
                "Kontext vztahů",
                ONTOLOGY_SLUGS
        );
        AiKnownConceptualModelDto knownModel = knownModelDto();
        mockKnownConceptualModel(knownModel);
        AiJobStartResponseDto expectedResponse = jobResponse();
        when(aiClient.startRelationshipSuggestions(
                eq(BEARER_TOKEN),
                eq(YEAR),
                eq(NUMBER),
                eq(DATE),
                any(IsmdAiSelectedClassJobRequest.class)
        )).thenReturn(expectedResponse);

        AiJobStartResponseDto result = service.startRelationshipSuggestions(
                BEARER_TOKEN,
                YEAR,
                NUMBER,
                DATE,
                input
        );

        assertSame(expectedResponse, result);
        ArgumentCaptor<IsmdAiSelectedClassJobRequest> requestCaptor =
                ArgumentCaptor.forClass(IsmdAiSelectedClassJobRequest.class);
        verify(aiClient).startRelationshipSuggestions(
                eq(BEARER_TOKEN),
                eq(YEAR),
                eq(NUMBER),
                eq(DATE),
                requestCaptor.capture()
        );
        IsmdAiSelectedClassJobRequest capturedRequest = requestCaptor.getValue();
        assertEquals(expectedSelectedRequest(input, knownModel), capturedRequest);
        assertSame(knownModel, capturedRequest.knownConceptualModel());
    }

    @Test
    void getSuggestionsAcceptsConfiguredMaximumJobIds() {
        config.setMaxJobIds(2);
        List<UUID> jobIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        List<AiClassSuggestionsJobResponseDto> expectedResponse = List.of();
        when(aiClient.getClassSuggestions(BEARER_TOKEN, jobIds)).thenReturn(expectedResponse);

        List<AiClassSuggestionsJobResponseDto> result = service.getClassSuggestions(BEARER_TOKEN, jobIds);

        assertSame(expectedResponse, result);
        verify(aiClient).getClassSuggestions(BEARER_TOKEN, jobIds);
    }

    @Test
    void getSuggestionsRejectsMoreThanConfiguredMaximumJobIds() {
        config.setMaxJobIds(1);
        List<UUID> jobIds = List.of(UUID.randomUUID(), UUID.randomUUID());

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> service.getClassSuggestions(BEARER_TOKEN, jobIds)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> service.getPropertySuggestions(BEARER_TOKEN, jobIds)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> service.getRelationshipSuggestions(BEARER_TOKEN, jobIds)
                )
        );
        verifyNoInteractions(aiClient);
    }

    @Test
    void vocabularyStartForwardsRequestWithoutCreatingOrLoadingOntologies() {
        var input = new AiVocabularySuggestionRequestDto(2, 0, 1, null, "Vozidla", knownModelDto());
        var expected = jobResponse();
        when(aiClient.startVocabularySuggestions(BEARER_TOKEN, YEAR, NUMBER, DATE, input)).thenReturn(expected);
        assertSame(expected, service.startVocabularySuggestions(BEARER_TOKEN, YEAR, NUMBER, DATE, input));
        verify(aiClient).startVocabularySuggestions(BEARER_TOKEN, YEAR, NUMBER, DATE, input);
        verifyNoInteractions(ontologyService, knownConceptualModelMapper);
    }

    @Test
    void vocabularyPollingEnforcesExistingJobIdLimit() {
        config.setMaxJobIds(1);
        var ids = List.of(UUID.randomUUID());
        List<AiVocabularySuggestionsJobResponseDto> expected = List.of();
        when(aiClient.getVocabularySuggestions(BEARER_TOKEN, ids)).thenReturn(expected);
        assertSame(expected, service.getVocabularySuggestions(BEARER_TOKEN, ids));
        assertThrows(IllegalArgumentException.class, () -> service.getVocabularySuggestions(
                BEARER_TOKEN, List.of(UUID.randomUUID(), UUID.randomUUID())));
        verify(aiClient).getVocabularySuggestions(BEARER_TOKEN, ids);
        verifyNoInteractions(ontologyService, knownConceptualModelMapper);
    }

    private IsmdAiSelectedClassJobRequest expectedSelectedRequest(
            AiSelectedClassSuggestionRequestDto input,
            AiKnownConceptualModelDto knownModel
    ) {
        return new IsmdAiSelectedClassJobRequest(
                7,
                input.selectedClassId(),
                input.structuralElementIds(),
                input.contextText(),
                knownModel
        );
    }

    private void mockKnownConceptualModel(AiKnownConceptualModelDto knownModel) {
        OntologyDetailModel firstOntologyDetail = OntologyDetailModel.builder()
                .iri("https://example.org/ontology/requests")
                .build();
        OntologyDetailModel secondOntologyDetail = OntologyDetailModel.builder()
                .iri("https://example.org/ontology/authorities")
                .build();
        when(ontologyService.getOntologyDetail(FIRST_ONTOLOGY_SLUG)).thenReturn(firstOntologyDetail);
        when(ontologyService.getOntologyDetail(SECOND_ONTOLOGY_SLUG)).thenReturn(secondOntologyDetail);
        when(knownConceptualModelMapper.map(List.of(firstOntologyDetail, secondOntologyDetail)))
                .thenReturn(knownModel);
    }

    private AiKnownConceptualModelDto knownModelDto() {
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
                        "/eli/cz/sb/2024/1"
                )),
                List.of(new AiKnownConceptualModelDto.KnownAttributeTermDto(
                        "https://example.org/ontology/jmeno",
                        new AiIdReferenceDto(SELECTED_CLASS_ID),
                        Map.of("cs", "jméno", "en", "name"),
                        Map.of("cs", "Jméno žadatele.", "en", "Applicant name."),
                        Map.of("cs", "Atribut jména.", "en", "Name attribute."),
                        "/eli/cz/sb/2024/1"
                )),
                List.of(new AiKnownConceptualModelDto.KnownRelationshipTermDto(
                        "https://example.org/ontology/podava",
                        new AiIdReferenceDto(SELECTED_CLASS_ID),
                        new AiIdReferenceDto(TARGET_CLASS_ID),
                        Map.of("cs", "podává", "en", "files"),
                        Map.of("cs", "Žadatel podává žádost.", "en", "An applicant files an application."),
                        Map.of("cs", "Vztah mezi žadatelem a žádostí.", "en", "Applicant-to-application relation."),
                        "/eli/cz/sb/2024/1"
                ))
        );
    }

    private AiJobStartResponseDto jobResponse() {
        return new AiJobStartResponseDto(UUID.randomUUID(), AiJobStatus.IN_PROGRESS);
    }
}
