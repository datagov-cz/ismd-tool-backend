package com.dia.ismdtoolbackend.client.ai;

import com.dia.ismdtoolbackend.controller.dto.ai.AiVocabularyExpansionRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiVocabularyRegenerationRequestDto;
import com.dia.ismdtoolbackend.client.ai.dto.IsmdAiClassJobRequest;
import com.dia.ismdtoolbackend.client.ai.dto.IsmdAiFeedbackRequest;
import com.dia.ismdtoolbackend.client.ai.dto.IsmdAiSelectedClassJobRequest;
import com.dia.ismdtoolbackend.controller.dto.ai.AiClassSuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiFeedbackRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiJobStartResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiVocabularySuggestionRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiVocabularySuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiPropertySuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiRelationshipSuggestionsJobResponseDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Component
public class IsmdAiClient {

    private static final String CLASS_START_PATH =
            "/legal-acts/{year}/{number}/{date}/class-suggestions-top-k-extraction-jobs";
    private static final String PROPERTY_START_PATH =
            "/legal-acts/{year}/{number}/{date}/property-suggestions-top-k-extraction-jobs";
    private static final String RELATIONSHIP_START_PATH =
            "/legal-acts/{year}/{number}/{date}/relationship-suggestions-top-k-extraction-jobs";
    private static final String VOCABULARY_START_PATH =
            "/legal-acts/{year}/{number}/{date}/vocabulary-suggestions-jobs";
    private static final String VOCABULARY_JOBS_PATH = "/legal-acts/vocabulary-suggestions-jobs";
    private static final String CLASS_JOBS_PATH = "/legal-acts/class-suggestions-jobs";
    private static final String PROPERTY_JOBS_PATH = "/legal-acts/property-suggestions-jobs";
    private static final String RELATIONSHIP_JOBS_PATH = "/legal-acts/relationship-suggestions-jobs";
    private static final String ACCEPT_SUGGESTION_PATH = "/accept-suggestion";
    private static final String LIKE_SUGGESTION_PATH = "/like-suggestion";
    private static final String DISLIKE_SUGGESTION_PATH = "/dislike-suggestion";
    private static final String JOB_IDS = "jobIds";

    private final RestClient restClient;

    public IsmdAiClient(@Qualifier("aiRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public AiJobStartResponseDto startClassSuggestions(
            String bearerToken,
            int year,
            int number,
            LocalDate date,
            IsmdAiClassJobRequest request
    ) {
        return restClient.post()
                .uri(CLASS_START_PATH, year, number, date)
                .headers(headers -> headers.setBearerAuth(bearerToken))
                .body(request)
                .retrieve()
                .body(AiJobStartResponseDto.class);
    }

    public AiJobStartResponseDto startPropertySuggestions(
            String bearerToken,
            int year,
            int number,
            LocalDate date,
            IsmdAiSelectedClassJobRequest request
    ) {
        return restClient.post()
                .uri(PROPERTY_START_PATH, year, number, date)
                .headers(headers -> headers.setBearerAuth(bearerToken))
                .body(request)
                .retrieve()
                .body(AiJobStartResponseDto.class);
    }

    public AiJobStartResponseDto startRelationshipSuggestions(
            String bearerToken,
            int year,
            int number,
            LocalDate date,
            IsmdAiSelectedClassJobRequest request
    ) {
        return restClient.post()
                .uri(RELATIONSHIP_START_PATH, year, number, date)
                .headers(headers -> headers.setBearerAuth(bearerToken))
                .body(request)
                .retrieve()
                .body(AiJobStartResponseDto.class);
    }

    public List<AiClassSuggestionsJobResponseDto> getClassSuggestions(
            String bearerToken,
            List<UUID> jobIds
    ) {
        AiClassSuggestionsJobResponseDto[] responses = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(CLASS_JOBS_PATH)
                        .queryParam(JOB_IDS, jobIds)
                        .build())
                .headers(headers -> headers.setBearerAuth(bearerToken))
                .retrieve()
                .body(AiClassSuggestionsJobResponseDto[].class);
        if (responses == null) {
            throw invalidResponse();
        }
        return Arrays.asList(responses);
    }

    public List<AiPropertySuggestionsJobResponseDto> getPropertySuggestions(
            String bearerToken,
            List<UUID> jobIds
    ) {
        AiPropertySuggestionsJobResponseDto[] responses = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(PROPERTY_JOBS_PATH)
                        .queryParam(JOB_IDS, jobIds)
                        .build())
                .headers(headers -> headers.setBearerAuth(bearerToken))
                .retrieve()
                .body(AiPropertySuggestionsJobResponseDto[].class);
        if (responses == null) {
            throw invalidResponse();
        }
        return Arrays.asList(responses);
    }

    public List<AiRelationshipSuggestionsJobResponseDto> getRelationshipSuggestions(
            String bearerToken,
            List<UUID> jobIds
    ) {
        AiRelationshipSuggestionsJobResponseDto[] responses = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(RELATIONSHIP_JOBS_PATH)
                        .queryParam(JOB_IDS, jobIds)
                        .build())
                .headers(headers -> headers.setBearerAuth(bearerToken))
                .retrieve()
                .body(AiRelationshipSuggestionsJobResponseDto[].class);
        if (responses == null) {
            throw invalidResponse();
        }
        return Arrays.asList(responses);
    }

    public AiJobStartResponseDto startVocabularySuggestions(
            String bearerToken, int year, int number, LocalDate date, AiVocabularySuggestionRequestDto request
    ) {
        return startVocabularyRequest(bearerToken, VOCABULARY_START_PATH, year, number, date, request);
    }

    public AiJobStartResponseDto expandVocabulary(String bearerToken, int year, int number, LocalDate date,
                                                  AiVocabularyExpansionRequestDto request) {
        return startVocabularyRequest(bearerToken, VOCABULARY_START_PATH + "/expand", year, number, date, request);
    }

    public AiJobStartResponseDto regenerateVocabularyConcept(String bearerToken, int year, int number, LocalDate date,
                                                             AiVocabularyRegenerationRequestDto request) {
        return startVocabularyRequest(bearerToken, VOCABULARY_START_PATH + "/regenerate", year, number, date, request);
    }

    private AiJobStartResponseDto startVocabularyRequest(String bearerToken, String path, int year, int number,
                                                         LocalDate date, Object request) {
        AiJobStartResponseDto response = restClient.post()
                .uri(path, year, number, date)
                .headers(headers -> headers.setBearerAuth(bearerToken))
                .body(request).retrieve().body(AiJobStartResponseDto.class);
        if (response == null || response.jobId() == null || response.status() == null) throw invalidResponse();
        return response;
    }

    public List<AiVocabularySuggestionsJobResponseDto> getVocabularySuggestions(String bearerToken, List<UUID> jobIds) {
        AiVocabularySuggestionsJobResponseDto[] responses = restClient.get()
                .uri(uriBuilder -> uriBuilder.path(VOCABULARY_JOBS_PATH).queryParam(JOB_IDS, jobIds).build())
                .headers(headers -> headers.setBearerAuth(bearerToken))
                .retrieve()
                .body(AiVocabularySuggestionsJobResponseDto[].class);
        if (responses == null || Arrays.stream(responses).anyMatch(response -> response == null
                || response.jobId() == null || response.status() == null || response.draft() == null)) {
            throw invalidResponse();
        }
        return Arrays.asList(responses);
    }

    public void acceptSuggestions(String bearerToken, List<AiFeedbackRequestDto> requests) {
        feedbackRequests(ACCEPT_SUGGESTION_PATH, bearerToken, requests);
    }

    public void likeSuggestions(String bearerToken, List<AiFeedbackRequestDto> requests) {
        feedbackRequests(LIKE_SUGGESTION_PATH, bearerToken, requests);
    }

    public void dislikeSuggestions(String bearerToken, List<AiFeedbackRequestDto> requests) {
        feedbackRequests(DISLIKE_SUGGESTION_PATH, bearerToken, requests);
    }

    private void feedbackRequests(String uri, String bearerToken, List<AiFeedbackRequestDto> requests) {
        restClient.post()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearerToken))
                .body(requests.stream()
                        .map(request -> new IsmdAiFeedbackRequest(request.jobId(), request.suggestionIds()))
                        .toList()
                )
                .retrieve()
                .toBodilessEntity();
    }

    private RestClientException invalidResponse() {
        return new RestClientException("ISMD AI vrátila neúplnou odpověď.");
    }
}
