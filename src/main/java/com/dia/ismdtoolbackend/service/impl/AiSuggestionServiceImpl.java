package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.ai.IsmdAiClient;
import com.dia.ismdtoolbackend.client.ai.dto.IsmdAiClassJobRequest;
import com.dia.ismdtoolbackend.client.ai.dto.IsmdAiSelectedClassJobRequest;
import com.dia.ismdtoolbackend.config.AiServiceConfig;
import com.dia.ismdtoolbackend.controller.dto.ai.AiClassSuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiClassSuggestionRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiFeedbackRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiJobStartResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiPropertySuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiRelationshipSuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiSelectedClassSuggestionRequestDto;
import com.dia.ismdtoolbackend.service.AiSuggestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AiSuggestionServiceImpl implements AiSuggestionService {

    private final IsmdAiClient aiClient;
    private final AiServiceConfig config;

    @Override
    public AiJobStartResponseDto startClassSuggestions(
            String bearerToken,
            int year,
            int number,
            LocalDate date,
            AiClassSuggestionRequestDto request
    ) {
        return aiClient.startClassSuggestions(
                bearerToken,
                year,
                number,
                date,
                new IsmdAiClassJobRequest(
                        config.getSuggestionCount(),
                        request.structuralElementIds(),
                        request.contextText(),
                        request.knownConceptualModel()
                )
        );
    }

    @Override
    public AiJobStartResponseDto startPropertySuggestions(
            String bearerToken,
            int year,
            int number,
            LocalDate date,
            AiSelectedClassSuggestionRequestDto request
    ) {
        return aiClient.startPropertySuggestions(
                bearerToken,
                year,
                number,
                date,
                selectedClassRequest(request)
        );
    }

    @Override
    public AiJobStartResponseDto startRelationshipSuggestions(
            String bearerToken,
            int year,
            int number,
            LocalDate date,
            AiSelectedClassSuggestionRequestDto request
    ) {
        return aiClient.startRelationshipSuggestions(
                bearerToken,
                year,
                number,
                date,
                selectedClassRequest(request)
        );
    }

    private IsmdAiSelectedClassJobRequest selectedClassRequest(AiSelectedClassSuggestionRequestDto request) {
        return new IsmdAiSelectedClassJobRequest(
                config.getSuggestionCount(),
                request.selectedClassId(),
                request.structuralElementIds(),
                request.contextText(),
                request.knownConceptualModel()
        );
    }

    @Override
    public List<AiClassSuggestionsJobResponseDto> getClassSuggestions(String bearerToken, List<UUID> jobIds) {
        validateJobIds(jobIds);
        return aiClient.getClassSuggestions(bearerToken, jobIds);
    }

    @Override
    public List<AiPropertySuggestionsJobResponseDto> getPropertySuggestions(String bearerToken, List<UUID> jobIds) {
        validateJobIds(jobIds);
        return aiClient.getPropertySuggestions(bearerToken, jobIds);
    }

    @Override
    public List<AiRelationshipSuggestionsJobResponseDto> getRelationshipSuggestions(
            String bearerToken,
            List<UUID> jobIds
    ) {
        validateJobIds(jobIds);
        return aiClient.getRelationshipSuggestions(bearerToken, jobIds);
    }

    @Override
    public void acceptSuggestions(String bearerToken, List<AiFeedbackRequestDto> requests) {
        aiClient.acceptSuggestions(bearerToken, requests);
    }

    @Override
    public void likeSuggestions(String bearerToken, List<AiFeedbackRequestDto> requests) {
        aiClient.likeSuggestions(bearerToken, requests);
    }

    @Override
    public void dislikeSuggestions(String bearerToken, List<AiFeedbackRequestDto> requests) {
        aiClient.dislikeSuggestions(bearerToken, requests);
    }

    private void validateJobIds(List<UUID> jobIds) {
        if (jobIds.size() > config.getMaxJobIds()) {
            throw new IllegalArgumentException(
                    "Parametr jobIds může obsahovat nejvýše " + config.getMaxJobIds() + " hodnot."
            );
        }
    }
}
