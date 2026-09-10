package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.ai.AiVocabularyExpansionRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiVocabularyRegenerationRequestDto;
import com.dia.ismdtoolbackend.client.ai.IsmdAiClient;
import com.dia.ismdtoolbackend.client.ai.dto.IsmdAiClassJobRequest;
import com.dia.ismdtoolbackend.client.ai.dto.IsmdAiSelectedClassJobRequest;
import com.dia.ismdtoolbackend.config.AiServiceConfig;
import com.dia.ismdtoolbackend.controller.dto.ai.AiClassSuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiClassSuggestionRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiFeedbackRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiJobStartResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiVocabularySuggestionRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiVocabularySuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiKnownConceptualModelDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiPropertySuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiRelationshipSuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiSelectedClassSuggestionRequestDto;
import com.dia.ismdtoolbackend.mapper.AiKnownConceptualModelMapper;
import com.dia.ismdtoolbackend.service.AiSuggestionService;
import com.dia.ismdtoolbackend.service.OntologyService;
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
    private final OntologyService ontologyService;
    private final AiKnownConceptualModelMapper knownConceptualModelMapper;

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
                        knownConceptualModel(request.knownConceptualModelSlugs())
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
    public AiJobStartResponseDto startVocabularySuggestions(
            String bearerToken, int year, int number, LocalDate date, AiVocabularySuggestionRequestDto request
    ) {
        return aiClient.startVocabularySuggestions(bearerToken, year, number, date, request);
    }

    @Override
    public AiJobStartResponseDto expandVocabulary(String bearerToken, int year, int number, LocalDate date,
                                                  AiVocabularyExpansionRequestDto request) {
        return aiClient.expandVocabulary(bearerToken, year, number, date, request);
    }

    @Override
    public AiJobStartResponseDto regenerateVocabularyConcept(String bearerToken, int year, int number, LocalDate date,
                                                             AiVocabularyRegenerationRequestDto request) {
        return aiClient.regenerateVocabularyConcept(bearerToken, year, number, date, request);
    }

    @Override
    public List<AiVocabularySuggestionsJobResponseDto> getVocabularySuggestions(String bearerToken, List<UUID> jobIds) {
        validateJobIds(jobIds);
        return aiClient.getVocabularySuggestions(bearerToken, jobIds);
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

    private IsmdAiSelectedClassJobRequest selectedClassRequest(AiSelectedClassSuggestionRequestDto request) {
        return new IsmdAiSelectedClassJobRequest(
                config.getSuggestionCount(),
                request.selectedClassId(),
                request.structuralElementIds(),
                request.contextText(),
                knownConceptualModel(request.knownConceptualModelSlugs())
        );
    }

    private AiKnownConceptualModelDto knownConceptualModel(List<String> knownConceptualModelSlugs) {
        if (knownConceptualModelSlugs == null || knownConceptualModelSlugs.isEmpty()) {
            return null;
        }
        List<String> distinctSlugs = knownConceptualModelSlugs.stream()
                .distinct()
                .toList();
        validateKnownConceptualModelSlugs(distinctSlugs);
        return knownConceptualModelMapper.map(
                distinctSlugs.stream()
                        .map(ontologyService::getOntologyDetail)
                        .toList()
        );
    }

    private void validateKnownConceptualModelSlugs(List<String> slugs) {
        if (slugs.size() > config.getMaxKnownConceptualModelSlugs()) {
            throw new IllegalArgumentException(
                    "Maximální počet různých hodnot parametru knownConceptualModelSlugs je "
                            + config.getMaxKnownConceptualModelSlugs() + "."
            );
        }
    }
}
