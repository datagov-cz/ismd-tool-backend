package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.controller.dto.ai.AiClassSuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiClassSuggestionRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiFeedbackRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiJobStartResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiPropertySuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiRelationshipSuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiSelectedClassSuggestionRequestDto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AiSuggestionService {

    AiJobStartResponseDto startClassSuggestions(
            String bearerToken,
            int year,
            int number,
            LocalDate date,
            AiClassSuggestionRequestDto request
    );

    AiJobStartResponseDto startPropertySuggestions(
            String bearerToken,
            int year,
            int number,
            LocalDate date,
            AiSelectedClassSuggestionRequestDto request
    );

    AiJobStartResponseDto startRelationshipSuggestions(
            String bearerToken,
            int year,
            int number,
            LocalDate date,
            AiSelectedClassSuggestionRequestDto request
    );

    List<AiClassSuggestionsJobResponseDto> getClassSuggestions(String bearerToken, List<UUID> jobIds);

    List<AiPropertySuggestionsJobResponseDto> getPropertySuggestions(String bearerToken, List<UUID> jobIds);

    List<AiRelationshipSuggestionsJobResponseDto> getRelationshipSuggestions(String bearerToken, List<UUID> jobIds);

    void acceptSuggestions(String bearerToken, List<AiFeedbackRequestDto> requests);

    void likeSuggestions(String bearerToken, List<AiFeedbackRequestDto> requests);

    void dislikeSuggestions(String bearerToken, List<AiFeedbackRequestDto> requests);
}
