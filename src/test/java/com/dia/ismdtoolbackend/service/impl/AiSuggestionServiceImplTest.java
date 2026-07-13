package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.ai.IsmdAiClient;
import com.dia.ismdtoolbackend.client.ai.dto.IsmdAiClassJobRequest;
import com.dia.ismdtoolbackend.client.ai.dto.IsmdAiSelectedClassJobRequest;
import com.dia.ismdtoolbackend.config.AiServiceConfig;
import com.dia.ismdtoolbackend.controller.dto.ai.AiClassSuggestionRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiIdReferenceDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiJobStartResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiKnownConceptualModelDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiSelectedClassSuggestionRequestDto;
import com.dia.ismdtoolbackend.enums.AiJobStatus;
import com.dia.ismdtoolbackend.enums.AiTermType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiSuggestionServiceImplTest {

    private static final String BEARER_TOKEN = "frontend.jwt";
    private static final int YEAR = 2024;
    private static final int NUMBER = 1;
    private static final LocalDate DATE = LocalDate.of(2024, 1, 1);
    private static final String SELECTED_CLASS_ID = "https://example.org/ontology/zadatel";
    private static final String TARGET_CLASS_ID = "https://example.org/ontology/zadost";

    @Mock
    private IsmdAiClient aiClient;

    private AiSuggestionServiceImpl service;

    @BeforeEach
    void setUp() {
        AiServiceConfig config = new AiServiceConfig();
        config.setSuggestionCount(7);
        service = new AiSuggestionServiceImpl(aiClient, config);
    }

    @Test
    void startClassSuggestionsAddsConfiguredCountAndMapsCompleteRequest() {
        AiKnownConceptualModelDto knownModel = knownModelDto();
        AiClassSuggestionRequestDto input = new AiClassSuggestionRequestDto(
                List.of("/eli/cz/sb/2024/1/par_2", "/eli/cz/sb/2024/1/par_3"),
                "Kontext tříd",
                knownModel
        );
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
    void startPropertySuggestionsAddsConfiguredCountAndMapsCompleteRequest() {
        AiSelectedClassSuggestionRequestDto input = new AiSelectedClassSuggestionRequestDto(
                SELECTED_CLASS_ID,
                List.of("/eli/cz/sb/2024/1/par_2"),
                "Kontext vlastností",
                knownModelDto()
        );
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
        assertEquals(expectedSelectedRequest(input), capturedRequest);
        assertSame(input.knownConceptualModel(), capturedRequest.knownConceptualModel());
    }

    @Test
    void startRelationshipSuggestionsAddsConfiguredCountAndMapsCompleteRequest() {
        AiSelectedClassSuggestionRequestDto input = new AiSelectedClassSuggestionRequestDto(
                SELECTED_CLASS_ID,
                List.of("/eli/cz/sb/2024/1/par_3"),
                "Kontext vztahů",
                knownModelDto()
        );
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
        assertEquals(expectedSelectedRequest(input), capturedRequest);
        assertSame(input.knownConceptualModel(), capturedRequest.knownConceptualModel());
    }

    private IsmdAiSelectedClassJobRequest expectedSelectedRequest(AiSelectedClassSuggestionRequestDto input) {
        return new IsmdAiSelectedClassJobRequest(
                7,
                input.selectedClassId(),
                input.structuralElementIds(),
                input.contextText(),
                input.knownConceptualModel()
        );
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
