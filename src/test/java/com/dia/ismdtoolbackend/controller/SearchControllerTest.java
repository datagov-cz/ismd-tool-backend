package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.security.TestSecurityConfig;
import com.dia.ismdtoolbackend.config.security.WithMockSecurityUser;
import com.dia.ismdtoolbackend.controller.dto.SearchResponseDto;
import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.controller.dto.SourceStatusDto;
import com.dia.ismdtoolbackend.enums.*;
import com.dia.ismdtoolbackend.service.SearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = SearchController.class,
        excludeAutoConfiguration = {
                org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
                org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration.class
        })
@Import({TestSecurityConfig.class, com.dia.ismdtoolbackend.config.GlobalExceptionHandler.class})
@ActiveProfiles("junit")
class SearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SearchService searchService;

    @Test
    void search_withValidQuery_returns200() throws Exception {
        SearchResponseDto response = SearchResponseDto.builder()
                .results(List.of(
                        SearchResultDto.builder()
                                .iri("https://example.org/concept/1")
                                .label("Osoba")
                                .type(SearchType.CONCEPT)
                                .source(SearchSource.NKD)
                                .build()
                ))
                .returnedCount(1)
                .limit(20)
                .offset(0)
                .sourceStatuses(Map.of(
                        SearchSource.NKD, SourceStatusDto.builder()
                                .status(SearchSourceStatus.OK)
                                .returnedCount(1)
                                .totalOntologies(0)
                                .totalConcepts(1)
                                .build()
                ))
                .build();

        when(searchService.search(anyString(), any(), any(), anyInt(), anyInt(),
                anyString(), any(), any(), any())).thenReturn(response);

        mockMvc.perform(get("/api/search").param("q", "osoba"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.results").isArray())
                .andExpect(jsonPath("$.data.results[0].iri").value("https://example.org/concept/1"))
                .andExpect(jsonPath("$.data.returnedCount").value(1));
    }

    @Test
    void search_withQueryTooShort_returns400() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "a"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void search_withThreeCharQuery_returns400() throws Exception {
        // Min length is 4 — matches FE validation and Virtuoso bif:contains FT370
        // (wildcard word needs at least 4 leading characters).
        mockMvc.perform(get("/api/search").param("q", "oso"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void search_withFourCharQuery_isAccepted() throws Exception {
        SearchResponseDto response = SearchResponseDto.builder()
                .results(List.of())
                .returnedCount(0)
                .limit(20)
                .offset(0)
                .build();
        when(searchService.search(anyString(), any(), any(), anyInt(), anyInt(),
                anyString(), any(), any(), any())).thenReturn(response);

        mockMvc.perform(get("/api/search").param("q", "osob"))
                .andExpect(status().isOk());
    }

    @Test
    void search_withEmptyQuery_returns400() throws Exception {
        mockMvc.perform(get("/api/search").param("q", ""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void search_withMissingQuery_returns400() throws Exception {
        mockMvc.perform(get("/api/search"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void search_withInvalidLimit_returns400() throws Exception {
        mockMvc.perform(get("/api/search")
                        .param("q", "osoba")
                        .param("limit", "200"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void search_withZeroLimit_returns400() throws Exception {
        mockMvc.perform(get("/api/search")
                        .param("q", "osoba")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void search_withNegativeOffset_returns400() throws Exception {
        mockMvc.perform(get("/api/search")
                        .param("q", "osoba")
                        .param("offset", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void search_withInvalidType_returns400() throws Exception {
        mockMvc.perform(get("/api/search")
                        .param("q", "osoba")
                        .param("type", "INVALID"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void search_withInvalidSource_returns400() throws Exception {
        mockMvc.perform(get("/api/search")
                        .param("q", "osoba")
                        .param("source", "INVALID"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void search_withValidTypeParam_returns200() throws Exception {
        when(searchService.search(anyString(), any(), any(), anyInt(), anyInt(),
                anyString(), any(), any(), any()))
                .thenReturn(SearchResponseDto.builder()
                        .results(List.of())
                        .returnedCount(0)
                        .limit(20)
                        .offset(0)
                        .sourceStatuses(Map.of())
                        .build());

        mockMvc.perform(get("/api/search")
                        .param("q", "osoba")
                        .param("type", "CONCEPT"))
                .andExpect(status().isOk());
    }

    @Test
    void search_withSourceNKD_returns200() throws Exception {
        when(searchService.search(anyString(), any(), any(), anyInt(), anyInt(),
                anyString(), any(), any(), any()))
                .thenReturn(SearchResponseDto.builder()
                        .results(List.of())
                        .returnedCount(0)
                        .limit(20)
                        .offset(0)
                        .sourceStatuses(Map.of())
                        .build());

        mockMvc.perform(get("/api/search")
                        .param("q", "osoba")
                        .param("source", "NKD"))
                .andExpect(status().isOk());
    }

    @Test
    void search_anonymousWithSourceISMD_returns401() throws Exception {
        when(searchService.search(anyString(), any(), any(), anyInt(), anyInt(),
                anyString(), any(), any(), any()))
                .thenThrow(new SecurityException("Authentication required to search ISMD resources"));

        mockMvc.perform(get("/api/search")
                        .param("q", "osoba")
                        .param("source", "ISMD"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockSecurityUser(userId = "user1")
    void search_authenticatedWithSourceALL_returns200() throws Exception {
        when(searchService.search(anyString(), any(), any(), anyInt(), anyInt(),
                anyString(), any(), any(), any()))
                .thenReturn(SearchResponseDto.builder()
                        .results(List.of())
                        .returnedCount(0)
                        .limit(20)
                        .offset(0)
                        .sourceStatuses(Map.of())
                        .build());

        mockMvc.perform(get("/api/search")
                        .param("q", "osoba")
                        .param("source", "ALL"))
                .andExpect(status().isOk());
    }

    @Test
    void search_withOntologyIriFilter_returns200() throws Exception {
        when(searchService.search(anyString(), any(), any(), anyInt(), anyInt(),
                anyString(), any(), any(), any()))
                .thenReturn(SearchResponseDto.builder()
                        .results(List.of())
                        .returnedCount(0)
                        .limit(20)
                        .offset(0)
                        .sourceStatuses(Map.of())
                        .build());

        mockMvc.perform(get("/api/search")
                        .param("q", "osoba")
                        .param("ontologyIri", "https://example.org/ontology/1"))
                .andExpect(status().isOk());
    }

    @Test
    void search_withRelationTypeFilter_returns200() throws Exception {
        when(searchService.search(anyString(), any(), any(), anyInt(), anyInt(),
                anyString(), any(), any(), any()))
                .thenReturn(SearchResponseDto.builder()
                        .results(List.of())
                        .returnedCount(0)
                        .limit(20)
                        .offset(0)
                        .sourceStatuses(Map.of())
                        .build());

        mockMvc.perform(get("/api/search")
                        .param("q", "osoba")
                        .param("relationTypes", "SUBCLASS"))
                .andExpect(status().isOk());
    }

    @Test
    void search_withCustomPagination_returns200() throws Exception {
        when(searchService.search(anyString(), any(), any(), anyInt(), anyInt(),
                anyString(), any(), any(), any()))
                .thenReturn(SearchResponseDto.builder()
                        .results(List.of())
                        .returnedCount(0)
                        .limit(50)
                        .offset(10)
                        .sourceStatuses(Map.of())
                        .build());

        mockMvc.perform(get("/api/search")
                        .param("q", "osoba")
                        .param("limit", "50")
                        .param("offset", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.limit").value(50))
                .andExpect(jsonPath("$.data.offset").value(10));
    }
}
