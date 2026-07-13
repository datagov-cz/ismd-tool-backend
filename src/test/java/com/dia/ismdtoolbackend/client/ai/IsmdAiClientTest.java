package com.dia.ismdtoolbackend.client.ai;

import com.dia.ismdtoolbackend.client.ai.dto.IsmdAiClassJobRequest;
import com.dia.ismdtoolbackend.client.ai.dto.IsmdAiSelectedClassJobRequest;
import com.dia.ismdtoolbackend.controller.dto.ai.AiClassSuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiFeedbackRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiIdReferenceDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiJobStartResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiKnownConceptualModelDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiPropertySuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiRelationshipSuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.enums.AiJobStatus;
import com.dia.ismdtoolbackend.enums.AiTermType;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IsmdAiClientTest {

    private static final String BEARER_TOKEN = "header.payload.signature";
    private static final UUID FIRST_JOB_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SECOND_JOB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String KNOWN_CONCEPTUAL_MODEL_JSON = """
            {
              "classes": [{
                "termID": "https://example.test/terms/applicant",
                "name": {"cs": "Žadatel"},
                "definition": {"cs": "Osoba podávající žádost."},
                "explanation": {"cs": "Známá třída."},
                "type": "SUBJECT",
                "specializes": [{"id": "https://example.test/terms/person"}],
                "legal_act": "/eli/cz/sb/2025/7"
              }],
              "attributes": [{
                "termID": "https://example.test/terms/name",
                "associated_class": {"id": "https://example.test/terms/applicant"},
                "name": {"cs": "jméno"},
                "definition": {"cs": "Jméno žadatele."},
                "explanation": {"cs": "Známý atribut."},
                "legal_act": "/eli/cz/sb/2025/7"
              }],
              "relationships": [{
                "termID": "https://example.test/terms/submits",
                "source_class": {"id": "https://example.test/terms/applicant"},
                "target_class": {"id": "https://example.test/terms/application"},
                "name": {"cs": "podává"},
                "definition": {"cs": "Žadatel podává žádost."},
                "explanation": {"cs": "Známý vztah."},
                "legal_act": "/eli/cz/sb/2025/7"
              }]
            }
            """;

    private WireMockServer wireMock;
    private IsmdAiClient client;

    @BeforeAll
    void startServer() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JsonMapper aiJsonMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        client = new IsmdAiClient(RestClient.builder()
                .baseUrl(wireMock.baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .configureMessageConverters(converters -> converters.withJsonConverter(
                        new JacksonJsonHttpMessageConverter(aiJsonMapper)
                ))
                .build());
    }

    @AfterAll
    void stopServer() {
        wireMock.stop();
    }

    @BeforeEach
    void resetServer() {
        wireMock.resetAll();
    }

    @Test
    void startClassSuggestions_forwardsBearerAndUsesExactUpstreamJsonContract() {
        String path = "/legal-acts/2025/7/2025-02-03/class-suggestions-top-k-extraction-jobs";
        wireMock.stubFor(post(urlEqualTo(path))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.ACCEPTED.value())
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody("""
                                {
                                  "job_id": "11111111-1111-1111-1111-111111111111",
                                  "status": "in_progress"
                                }
                                """)));

        IsmdAiClassJobRequest request = new IsmdAiClassJobRequest(
                4,
                List.of("/eli/cz/sb/2025/7/par_2"),
                "Libovolný doplňující kontext.",
                fullKnownConceptualModel()
        );

        AiJobStartResponseDto response = client.startClassSuggestions(
                BEARER_TOKEN,
                2025,
                7,
                LocalDate.of(2025, 2, 3),
                request
        );

        assertEquals(FIRST_JOB_ID, response.jobId());
        assertEquals(AiJobStatus.IN_PROGRESS, response.status());
        wireMock.verify(postRequestedFor(urlEqualTo(path))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + BEARER_TOKEN))
                .withRequestBody(equalToJson("""
                        {
                          "k": 4,
                          "structural_element_ids": ["/eli/cz/sb/2025/7/par_2"],
                          "context_text": "Libovolný doplňující kontext.",
                          "known_conceptual_model": %s
                        }
                        """.formatted(KNOWN_CONCEPTUAL_MODEL_JSON))));
    }

    @Test
    void startSelectedClassSuggestions_useExactSnakeCaseUpstreamJsonContract() {
        String propertyPath =
                "/legal-acts/2025/7/2025-02-03/property-suggestions-top-k-extraction-jobs";
        String relationshipPath =
                "/legal-acts/2025/7/2025-02-03/relationship-suggestions-top-k-extraction-jobs";
        wireMock.stubFor(post(urlEqualTo(propertyPath))
                .willReturn(jsonResponse(HttpStatus.ACCEPTED, """
                        {"job_id":"11111111-1111-1111-1111-111111111111","status":"in_progress"}
                        """)));
        wireMock.stubFor(post(urlEqualTo(relationshipPath))
                .willReturn(jsonResponse(HttpStatus.ACCEPTED, """
                        {"job_id":"22222222-2222-2222-2222-222222222222","status":"in_progress"}
                        """)));
        IsmdAiSelectedClassJobRequest request = new IsmdAiSelectedClassJobRequest(
                3,
                "https://example.test/terms/applicant",
                List.of("/eli/cz/sb/2025/7/par_3"),
                "Kontext vybrané třídy.",
                fullKnownConceptualModel()
        );
        String expectedBody = """
                {
                  "k": 3,
                  "selected_class_id": "https://example.test/terms/applicant",
                  "structural_element_ids": ["/eli/cz/sb/2025/7/par_3"],
                  "context_text": "Kontext vybrané třídy.",
                  "known_conceptual_model": %s
                }
                """.formatted(KNOWN_CONCEPTUAL_MODEL_JSON);

        client.startPropertySuggestions(BEARER_TOKEN, 2025, 7, LocalDate.of(2025, 2, 3), request);
        client.startRelationshipSuggestions(BEARER_TOKEN, 2025, 7, LocalDate.of(2025, 2, 3), request);

        wireMock.verify(postRequestedFor(urlEqualTo(propertyPath))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + BEARER_TOKEN))
                .withRequestBody(equalToJson(expectedBody)));
        wireMock.verify(postRequestedFor(urlEqualTo(relationshipPath))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + BEARER_TOKEN))
                .withRequestBody(equalToJson(expectedBody)));
    }

    @Test
    void getClassSuggestions_sendsRepeatedJobIdsAndMapsSnakeCaseResponse() {
        String path = "/legal-acts/class-suggestions-jobs?jobIds=" + FIRST_JOB_ID
                + "&jobIds=" + SECOND_JOB_ID;
        wireMock.stubFor(get(urlEqualTo(path))
                .willReturn(jsonResponse(HttpStatus.OK, """
                        [{
                          "job_id": "11111111-1111-1111-1111-111111111111",
                          "status": "completed",
                          "new_suggestions": [{
                            "suggestion_id": "class-1",
                            "name": {"cs": "Příslušný orgán"},
                            "definition": {"cs": "Orgán rozhodující o žádosti."},
                            "explanation": {"cs": "Návrh z právního aktu."},
                            "type": "OBJECT",
                            "specializes": [{"id": "https://example.test/terms/authority"}],
                            "legal_act": "/eli/cz/sb/2025/7"
                          }]
                        }]
                        """)));

        List<AiClassSuggestionsJobResponseDto> responses = client.getClassSuggestions(
                BEARER_TOKEN,
                List.of(FIRST_JOB_ID, SECOND_JOB_ID)
        );

        assertEquals(1, responses.size());
        AiClassSuggestionsJobResponseDto response = responses.get(0);
        assertEquals(FIRST_JOB_ID, response.jobId());
        assertEquals(AiJobStatus.COMPLETED, response.status());
        AiClassSuggestionsJobResponseDto.AiClassSuggestionDto suggestion = response.newSuggestions().get(0);
        assertEquals("class-1", suggestion.suggestionId());
        assertEquals(Map.of("cs", "Příslušný orgán"), suggestion.name());
        assertEquals(AiTermType.OBJECT, suggestion.type());
        assertEquals("https://example.test/terms/authority", suggestion.specializes().get(0).id());
        assertEquals("/eli/cz/sb/2025/7", suggestion.legalAct());
        wireMock.verify(getRequestedFor(urlEqualTo(path))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + BEARER_TOKEN)));
    }

    @Test
    void getSelectedClassSuggestions_mapsPropertyAndRelationshipResponseShapes() {
        String propertyPath = "/legal-acts/property-suggestions-jobs?jobIds=" + FIRST_JOB_ID;
        wireMock.stubFor(get(urlEqualTo(propertyPath))
                .willReturn(jsonResponse(HttpStatus.OK, """
                        [{
                          "job_id": "11111111-1111-1111-1111-111111111111",
                          "selected_class_id": "https://example.test/terms/applicant",
                          "status": "completed",
                          "new_attribute_suggestions": [{
                            "suggestion_id": "attribute-1",
                            "associated_class": {"id": "https://example.test/terms/applicant"},
                            "name": {"cs": "datum podání"},
                            "definition": {"cs": "Datum podání žádosti."},
                            "explanation": {"cs": "Návrh atributu."},
                            "legal_act": "/eli/cz/sb/2025/7"
                          }]
                        }]
                        """)));
        String relationshipPath = "/legal-acts/relationship-suggestions-jobs?jobIds=" + SECOND_JOB_ID;
        wireMock.stubFor(get(urlEqualTo(relationshipPath))
                .willReturn(jsonResponse(HttpStatus.OK, """
                        [{
                          "job_id": "22222222-2222-2222-2222-222222222222",
                          "selected_class_id": "https://example.test/terms/applicant",
                          "status": "completed",
                          "new_relationship_suggestions": [{
                            "suggestion_id": "relationship-1",
                            "source_class": {"id": "https://example.test/terms/applicant"},
                            "target_class": {"id": "https://example.test/terms/application"},
                            "name": {"cs": "podává"},
                            "definition": {"cs": "Žadatel podává žádost."},
                            "explanation": {"cs": "Návrh vztahu."},
                            "legal_act": "/eli/cz/sb/2025/7"
                          }]
                        }]
                        """)));

        AiPropertySuggestionsJobResponseDto property = client
                .getPropertySuggestions(BEARER_TOKEN, List.of(FIRST_JOB_ID))
                .get(0);
        AiRelationshipSuggestionsJobResponseDto relationship = client
                .getRelationshipSuggestions(BEARER_TOKEN, List.of(SECOND_JOB_ID))
                .get(0);

        assertEquals("https://example.test/terms/applicant", property.selectedClassId());
        assertEquals("attribute-1", property.newAttributeSuggestions().get(0).suggestionId());
        assertEquals(
                "https://example.test/terms/applicant",
                property.newAttributeSuggestions().get(0).associatedClass().id()
        );
        assertEquals("https://example.test/terms/applicant", relationship.selectedClassId());
        assertEquals("relationship-1", relationship.newRelationshipSuggestions().get(0).suggestionId());
        assertEquals(
                "https://example.test/terms/application",
                relationship.newRelationshipSuggestions().get(0).targetClass().id()
        );
    }

    @Test
    void feedback_usesLegacyKeysAndAcceptsBothNoContentAndJsonSuccessResponses() {
        AiFeedbackRequestDto feedback = new AiFeedbackRequestDto(
                FIRST_JOB_ID,
                List.of("suggestion-1", "suggestion-2")
        );
        String expectedBody = """
                [{
                  "jobID": "11111111-1111-1111-1111-111111111111",
                  "suggestionID": ["suggestion-1", "suggestion-2"]
                }]
                """;
        wireMock.stubFor(post(urlEqualTo("/accept-suggestion"))
                .willReturn(aResponse().withStatus(HttpStatus.NO_CONTENT.value())));
        wireMock.stubFor(post(urlEqualTo("/like-suggestion"))
                .willReturn(jsonResponse(HttpStatus.OK, "{\"status\":\"recorded\"}")));

        client.acceptSuggestions(BEARER_TOKEN, List.of(feedback));
        client.likeSuggestions(BEARER_TOKEN, List.of(feedback));

        wireMock.verify(postRequestedFor(urlEqualTo("/accept-suggestion"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + BEARER_TOKEN))
                .withRequestBody(equalToJson(expectedBody)));
        wireMock.verify(postRequestedFor(urlEqualTo("/like-suggestion"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + BEARER_TOKEN))
                .withRequestBody(equalToJson(expectedBody)));
    }

    @Test
    void upstreamClientError_isReturnedByRestClientWithOriginalStatus() {
        wireMock.stubFor(get(urlEqualTo("/legal-acts/class-suggestions-jobs?jobIds=" + FIRST_JOB_ID))
                .willReturn(jsonResponse(HttpStatus.NOT_FOUND, "{\"detail\":\"Úloha nebyla nalezena.\"}")));

        RestClientResponseException exception = assertThrows(
                RestClientResponseException.class,
                () -> client.getClassSuggestions(BEARER_TOKEN, List.of(FIRST_JOB_ID))
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }

    @Test
    void upstreamServerError_isReturnedByRestClientWithOriginalStatus() {
        wireMock.stubFor(get(urlEqualTo("/legal-acts/class-suggestions-jobs?jobIds=" + FIRST_JOB_ID))
                .willReturn(jsonResponse(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "{\"detail\":\"Interní chyba AI služby.\"}"
                )));

        RestClientResponseException exception = assertThrows(
                RestClientResponseException.class,
                () -> client.getClassSuggestions(BEARER_TOKEN, List.of(FIRST_JOB_ID))
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatusCode());
    }

    private static AiKnownConceptualModelDto fullKnownConceptualModel() {
        return new AiKnownConceptualModelDto(
                List.of(new AiKnownConceptualModelDto.KnownClassTermDto(
                        "https://example.test/terms/applicant",
                        Map.of("cs", "Žadatel"),
                        Map.of("cs", "Osoba podávající žádost."),
                        Map.of("cs", "Známá třída."),
                        AiTermType.SUBJECT,
                        List.of(new AiIdReferenceDto("https://example.test/terms/person")),
                        "/eli/cz/sb/2025/7"
                )),
                List.of(new AiKnownConceptualModelDto.KnownAttributeTermDto(
                        "https://example.test/terms/name",
                        new AiIdReferenceDto("https://example.test/terms/applicant"),
                        Map.of("cs", "jméno"),
                        Map.of("cs", "Jméno žadatele."),
                        Map.of("cs", "Známý atribut."),
                        "/eli/cz/sb/2025/7"
                )),
                List.of(new AiKnownConceptualModelDto.KnownRelationshipTermDto(
                        "https://example.test/terms/submits",
                        new AiIdReferenceDto("https://example.test/terms/applicant"),
                        new AiIdReferenceDto("https://example.test/terms/application"),
                        Map.of("cs", "podává"),
                        Map.of("cs", "Žadatel podává žádost."),
                        Map.of("cs", "Známý vztah."),
                        "/eli/cz/sb/2025/7"
                ))
        );
    }

    private static ResponseDefinitionBuilder jsonResponse(
            HttpStatus status,
            String body
    ) {
        return aResponse()
                .withStatus(status.value())
                .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .withBody(body);
    }
}
