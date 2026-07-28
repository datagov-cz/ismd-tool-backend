package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.GlobalExceptionHandler;
import com.dia.ismdtoolbackend.config.security.JwtAuthenticationConverter;
import com.dia.ismdtoolbackend.config.security.TestSecurityConfig;
import com.dia.ismdtoolbackend.controller.dto.ai.AiClassSuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiClassSuggestionRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiFeedbackRequestDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiIdReferenceDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiJobStartResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiPropertySuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiRelationshipSuggestionsJobResponseDto;
import com.dia.ismdtoolbackend.controller.dto.ai.AiSelectedClassSuggestionRequestDto;
import com.dia.ismdtoolbackend.enums.AiJobStatus;
import com.dia.ismdtoolbackend.enums.AiTermType;
import com.dia.ismdtoolbackend.service.AiSuggestionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AiSuggestionController.class,
        excludeAutoConfiguration = {
                org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class,
                org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
                org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration.class,
                org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration.class,
                org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration.class
        })
@Import({TestSecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("junit")
class AiSuggestionControllerTest {

    private static final String BEARER_TOKEN = "raw.jwt.from.frontend";
    private static final UUID FIRST_JOB_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SECOND_JOB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiSuggestionService aiSuggestionService;

    @BeforeEach
    void setUpJwtAuthentication() {
        Jwt jwt = Jwt.withTokenValue(BEARER_TOKEN)
                .header("alg", "RS256")
                .claim("sub", "user-123")
                .claim("realm_access", Map.of("roles", List.of("user")))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationConverter().convert(jwt));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void startClassSuggestions_returnsAcceptedAndMapsRequest() throws Exception {
        when(aiSuggestionService.startClassSuggestions(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(LocalDate.class),
                org.mockito.ArgumentMatchers.any(AiClassSuggestionRequestDto.class)
        )).thenReturn(new AiJobStartResponseDto(FIRST_JOB_ID, AiJobStatus.IN_PROGRESS));

        mockMvc.perform(post("/api/ai/legal-acts/2024/1/2024-01-15/class-suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "structuralElementIds": ["/eli/cz/sb/2024/1/par_2"],
                                  "contextText": "Zákon popisuje žádosti o povolení.",
                                  "knownConceptualModelSlugs": ["zadosti-o-povoleni", "spravni-organy"]
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(FIRST_JOB_ID.toString()))
                .andExpect(jsonPath("$.status").value("in_progress"));

        ArgumentCaptor<AiClassSuggestionRequestDto> requestCaptor =
                ArgumentCaptor.forClass(AiClassSuggestionRequestDto.class);
        verify(aiSuggestionService).startClassSuggestions(
                org.mockito.ArgumentMatchers.eq(BEARER_TOKEN),
                org.mockito.ArgumentMatchers.eq(2024),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(LocalDate.of(2024, 1, 15)),
                requestCaptor.capture()
        );

        AiClassSuggestionRequestDto request = requestCaptor.getValue();
        assertThat(request.structuralElementIds()).containsExactly("/eli/cz/sb/2024/1/par_2");
        assertThat(request.contextText()).isEqualTo("Zákon popisuje žádosti o povolení.");
        assertThat(request.knownConceptualModelSlugs())
                .containsExactly("zadosti-o-povoleni", "spravni-organy");
    }

    @Test
    void startPropertySuggestions_returnsAccepted() throws Exception {
        when(aiSuggestionService.startPropertySuggestions(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(LocalDate.class),
                org.mockito.ArgumentMatchers.any(AiSelectedClassSuggestionRequestDto.class)
        )).thenReturn(new AiJobStartResponseDto(FIRST_JOB_ID, AiJobStatus.IN_PROGRESS));

        mockMvc.perform(post("/api/ai/legal-acts/2024/1/2024-01-15/property-suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "selectedClassId": "class_001",
                                  "knownConceptualModelSlugs": ["zadosti-o-povoleni"]
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(FIRST_JOB_ID.toString()))
                .andExpect(jsonPath("$.status").value("in_progress"));

        verify(aiSuggestionService).startPropertySuggestions(
                BEARER_TOKEN,
                2024,
                1,
                LocalDate.of(2024, 1, 15),
                new AiSelectedClassSuggestionRequestDto(
                        "class_001",
                        null,
                        null,
                        List.of("zadosti-o-povoleni")
                )
        );
    }

    @Test
    void startRelationshipSuggestions_returnsAccepted() throws Exception {
        when(aiSuggestionService.startRelationshipSuggestions(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(LocalDate.class),
                org.mockito.ArgumentMatchers.any(AiSelectedClassSuggestionRequestDto.class)
        )).thenReturn(new AiJobStartResponseDto(FIRST_JOB_ID, AiJobStatus.IN_PROGRESS));

        mockMvc.perform(post("/api/ai/legal-acts/2024/1/2024-01-15/relationship-suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "selectedClassId": "class_001",
                                  "contextText": "Vztahy žadatele.",
                                  "knownConceptualModelSlugs": ["zadosti-o-povoleni"]
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(FIRST_JOB_ID.toString()));

        verify(aiSuggestionService).startRelationshipSuggestions(
                BEARER_TOKEN,
                2024,
                1,
                LocalDate.of(2024, 1, 15),
                new AiSelectedClassSuggestionRequestDto(
                        "class_001",
                        null,
                        "Vztahy žadatele.",
                        List.of("zadosti-o-povoleni")
                )
        );
    }

    @Test
    void getClassSuggestions_returnsJobsInServiceResponseShape() throws Exception {
        var suggestion = new AiClassSuggestionsJobResponseDto.AiClassSuggestionDto(
                "class_002",
                Map.of("cs", "Příslušný orgán"),
                Map.of("cs", "Orgán rozhodující o žádosti."),
                Map.of("cs", "Návrh odvozený z paragrafu."),
                AiTermType.CLASS,
                List.of(),
                "/eli/cz/sb/2024/1"
        );
        when(aiSuggestionService.getClassSuggestions(BEARER_TOKEN, List.of(FIRST_JOB_ID, SECOND_JOB_ID)))
                .thenReturn(List.of(new AiClassSuggestionsJobResponseDto(
                        FIRST_JOB_ID,
                        AiJobStatus.COMPLETED,
                        List.of(suggestion)
                )));

        mockMvc.perform(get("/api/ai/legal-acts/class-suggestions-jobs")
                        .queryParam("jobIds", FIRST_JOB_ID.toString(), SECOND_JOB_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobId").value(FIRST_JOB_ID.toString()))
                .andExpect(jsonPath("$[0].status").value("completed"))
                .andExpect(jsonPath("$[0].newSuggestions[0].suggestionId").value("class_002"))
                .andExpect(jsonPath("$[0].newSuggestions[0].legalAct").value("/eli/cz/sb/2024/1"));

        verify(aiSuggestionService).getClassSuggestions(BEARER_TOKEN, List.of(FIRST_JOB_ID, SECOND_JOB_ID));
    }

    @Test
    void getPropertySuggestions_returnsPropertySpecificResponse() throws Exception {
        var suggestion = new AiPropertySuggestionsJobResponseDto.AiAttributeSuggestionDto(
                "attribute_001",
                new AiIdReferenceDto("class_001"),
                Map.of("cs", "datum podání"),
                Map.of("cs", "Datum podání žádosti."),
                Map.of("cs", "Atribut žadatele."),
                "/eli/cz/sb/2024/1"
        );
        when(aiSuggestionService.getPropertySuggestions(BEARER_TOKEN, List.of(FIRST_JOB_ID)))
                .thenReturn(List.of(new AiPropertySuggestionsJobResponseDto(
                        FIRST_JOB_ID,
                        "class_001",
                        AiJobStatus.COMPLETED,
                        List.of(suggestion)
                )));

        mockMvc.perform(get("/api/ai/legal-acts/property-suggestions-jobs")
                        .queryParam("jobIds", FIRST_JOB_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].selectedClassId").value("class_001"))
                .andExpect(jsonPath("$[0].newAttributeSuggestions[0].suggestionId")
                        .value("attribute_001"))
                .andExpect(jsonPath("$[0].newAttributeSuggestions[0].associatedClass.id")
                        .value("class_001"));

        verify(aiSuggestionService).getPropertySuggestions(BEARER_TOKEN, List.of(FIRST_JOB_ID));
    }

    @Test
    void getRelationshipSuggestions_returnsRelationshipSpecificResponse() throws Exception {
        var suggestion = new AiRelationshipSuggestionsJobResponseDto.AiRelationshipSuggestionDto(
                "relationship_001",
                new AiIdReferenceDto("class_001"),
                new AiIdReferenceDto("class_002"),
                Map.of("cs", "podává žádost orgánu"),
                Map.of("cs", "Vztah mezi žadatelem a orgánem."),
                Map.of("cs", "Vztah odvozený ze zákona."),
                "/eli/cz/sb/2024/1"
        );
        when(aiSuggestionService.getRelationshipSuggestions(BEARER_TOKEN, List.of(FIRST_JOB_ID)))
                .thenReturn(List.of(new AiRelationshipSuggestionsJobResponseDto(
                        FIRST_JOB_ID,
                        "class_001",
                        AiJobStatus.COMPLETED,
                        List.of(suggestion)
                )));

        mockMvc.perform(get("/api/ai/legal-acts/relationship-suggestions-jobs")
                        .queryParam("jobIds", FIRST_JOB_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].selectedClassId").value("class_001"))
                .andExpect(jsonPath("$[0].newRelationshipSuggestions[0].suggestionId")
                        .value("relationship_001"))
                .andExpect(jsonPath("$[0].newRelationshipSuggestions[0].sourceClass.id")
                        .value("class_001"))
                .andExpect(jsonPath("$[0].newRelationshipSuggestions[0].targetClass.id")
                        .value("class_002"));

        verify(aiSuggestionService).getRelationshipSuggestions(BEARER_TOKEN, List.of(FIRST_JOB_ID));
    }

    @ParameterizedTest
    @ValueSource(strings = {"accept", "like", "dislike"})
    void feedbackEndpoints_returnNoContentAndMapLegacyFieldNames(String action) throws Exception {
        mockMvc.perform(post("/api/ai/" + action + "-suggestion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{
                                  "jobId": "11111111-1111-1111-1111-111111111111",
                                  "suggestionIds": ["class_001", "class_002"]
                                }]
                                """))
                .andExpect(status().isNoContent());

        List<AiFeedbackRequestDto> expected = List.of(
                new AiFeedbackRequestDto(FIRST_JOB_ID, List.of("class_001", "class_002"))
        );
        switch (action) {
            case "accept" -> verify(aiSuggestionService).acceptSuggestions(BEARER_TOKEN, expected);
            case "like" -> verify(aiSuggestionService).likeSuggestions(BEARER_TOKEN, expected);
            case "dislike" -> verify(aiSuggestionService).dislikeSuggestions(BEARER_TOKEN, expected);
            default -> throw new IllegalArgumentException("Unexpected action: " + action);
        }
    }

    @Test
    void startRequest_exposesOnlyClientOwnedContext() {
        assertThat(recordComponentNames(AiClassSuggestionRequestDto.class))
                .containsExactly("structuralElementIds", "contextText", "knownConceptualModelSlugs");
        assertThat(recordComponentNames(AiSelectedClassSuggestionRequestDto.class))
                .containsExactly(
                        "selectedClassId",
                        "structuralElementIds",
                        "contextText",
                        "knownConceptualModelSlugs"
                );
    }

    @Test
    void startRequest_withBlankStructuralElementId_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/ai/legal-acts/2024/1/2024-01-15/class-suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "structuralElementIds": [""],
                                  "knownConceptualModelSlugs": ["zadosti-o-povoleni"]
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(aiSuggestionService);
    }

    @Test
    void startClassSuggestions_withoutOptionalContext_isAccepted() throws Exception {
        mockMvc.perform(post("/api/ai/legal-acts/2024/1/2024-01-15/class-suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"knownConceptualModelSlugs": ["zadosti-o-povoleni"]}
                                """))
                .andExpect(status().isAccepted());

        verify(aiSuggestionService).startClassSuggestions(
                BEARER_TOKEN,
                2024,
                1,
                LocalDate.of(2024, 1, 15),
                new AiClassSuggestionRequestDto(null, null, List.of("zadosti-o-povoleni"))
        );
    }

    @Test
    void startClassSuggestions_withoutKnownConceptualModelSlugs_isAccepted() throws Exception {
        mockMvc.perform(post("/api/ai/legal-acts/2024/1/2024-01-15/class-suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isAccepted());

        verify(aiSuggestionService).startClassSuggestions(
                BEARER_TOKEN,
                2024,
                1,
                LocalDate.of(2024, 1, 15),
                new AiClassSuggestionRequestDto(null, null, null)
        );
    }

    @Test
    void startClassSuggestions_withEmptyKnownConceptualModelSlugs_isAccepted() throws Exception {
        mockMvc.perform(post("/api/ai/legal-acts/2024/1/2024-01-15/class-suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"knownConceptualModelSlugs": []}
                                """))
                .andExpect(status().isAccepted());

        verify(aiSuggestionService).startClassSuggestions(
                BEARER_TOKEN,
                2024,
                1,
                LocalDate.of(2024, 1, 15),
                new AiClassSuggestionRequestDto(null, null, List.of())
        );
    }

    @Test
    void startClassSuggestions_withBlankKnownConceptualModelSlug_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/ai/legal-acts/2024/1/2024-01-15/class-suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"knownConceptualModelSlugs": [""]}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(aiSuggestionService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"property", "relationship"})
    void selectedClassSuggestion_withoutSelectedClassId_returnsBadRequest(String suggestionType) throws Exception {
        mockMvc.perform(post("/api/ai/legal-acts/2024/1/2024-01-15/" + suggestionType
                        + "-suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"knownConceptualModelSlugs": ["zadosti-o-povoleni"]}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(aiSuggestionService);
    }

    @Test
    void getJobs_withoutJobIds_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/ai/legal-acts/class-suggestions-jobs"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(aiSuggestionService);
    }

    @Test
    void feedback_withInvalidItem_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/ai/accept-suggestion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"suggestionIds": []}]
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(aiSuggestionService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"accept", "like", "dislike"})
    void feedback_withNullItem_returnsBadRequest(String action) throws Exception {
        mockMvc.perform(post("/api/ai/" + action + "-suggestion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[null]"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(aiSuggestionService);
    }

    private List<String> recordComponentNames(Class<? extends Record> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
