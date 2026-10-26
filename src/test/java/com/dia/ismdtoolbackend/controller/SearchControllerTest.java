package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.controller.dto.SearchResponseDto;
import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.controller.dto.SourceStatusDto;
import com.dia.ismdtoolbackend.enums.*;
import com.dia.ismdtoolbackend.service.SearchService;
import com.dia.ismdtoolbackend.testsupport.web.ControllerSliceTest;
import com.dia.ismdtoolbackend.testsupport.web.WithMockJwt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Request → {@link SearchService#search} mapping. Stubs and verifications use exact arguments, because
 * the mapping (defaults, trimming, enum and list parsing, the caller) is what the controller does.
 */
@ControllerSliceTest(controllers = SearchController.class)
class SearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SearchService searchService;

    private static SearchResponseDto emptyResponse() {
        return SearchResponseDto.builder()
                .results(List.of())
                .returnedCount(0)
                .limit(20)
                .offset(0)
                .sourceStatuses(Map.of())
                .build();
    }

    /** No optional parameter: limit 20, offset 0, Czech, no filters, anonymous caller. */
    @Test
    void search_defaults_reachServiceAndBodyIsServed() throws Exception {
        SearchResponseDto response = SearchResponseDto.builder()
                .results(List.of(SearchResultDto.builder()
                        .iri("https://example.org/concept/1")
                        .label("Osoba")
                        .type(SearchType.CONCEPT)
                        .source(SearchSource.NKD)
                        .build()))
                .returnedCount(1)
                .limit(20)
                .offset(0)
                .sourceStatuses(Map.of(SearchSource.NKD, SourceStatusDto.builder()
                        .status(SearchSourceStatus.OK)
                        .returnedCount(1)
                        .totalOntologies(0)
                        .totalConcepts(1)
                        .build()))
                .build();
        when(searchService.search("osoba", null, null, 20, 0, "cs", null, null, null)).thenReturn(response);

        mockMvc.perform(get("/api/search").param("q", "osoba"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Search completed successfully"))
                .andExpect(jsonPath("$.data.results[0].iri").value("https://example.org/concept/1"))
                .andExpect(jsonPath("$.data.results[0].type").value("CONCEPT"))
                .andExpect(jsonPath("$.data.returnedCount").value(1))
                .andExpect(jsonPath("$.data.sourceStatuses.NKD.status").value("OK"));

        verify(searchService).search("osoba", null, null, 20, 0, "cs", null, null, null);
    }

    /** The query is trimmed before both the length check and the service call. */
    @Test
    void search_trimsQuery() throws Exception {
        when(searchService.search("osoba", null, null, 20, 0, "cs", null, null, null)).thenReturn(emptyResponse());

        mockMvc.perform(get("/api/search").param("q", "  osoba  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.returnedCount").value(0));

        verify(searchService).search("osoba", null, null, 20, 0, "cs", null, null, null);
    }

    /** Every explicit parameter is parsed and forwarded: enums, pagination, language and both filter lists. */
    @Test
    void search_allParameters_areParsedAndForwarded() throws Exception {
        List<String> ontologies = List.of("https://example.org/ontology/1", "https://example.org/ontology/2");
        List<RelationType> relations = List.of(RelationType.SUBCLASS, RelationType.EXACT_MATCH);
        when(searchService.search("osoba", SearchType.CLASS, SearchSource.NKD, 50, 10, "en",
                ontologies, relations, null)).thenReturn(emptyResponse());

        mockMvc.perform(get("/api/search")
                        .param("q", "osoba")
                        .param("type", "CLASS")
                        .param("source", "NKD")
                        .param("limit", "50")
                        .param("offset", "10")
                        .param("lang", "en")
                        .param("ontologyIri", "https://example.org/ontology/1", "https://example.org/ontology/2")
                        .param("relationTypes", "SUBCLASS,EXACT_MATCH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.returnedCount").value(0));

        verify(searchService).search("osoba", SearchType.CLASS, SearchSource.NKD, 50, 10, "en",
                ontologies, relations, null);
    }

    /** The authenticated caller is the JWT-derived SecurityUser, so the service can scope ISMD results. */
    @Test
    @WithMockJwt(subject = "user1")
    void search_authenticated_forwardsJwtUser() throws Exception {
        when(searchService.search(eq("osoba"), isNull(), eq(SearchSource.ALL), eq(20), eq(0), eq("cs"),
                isNull(), isNull(), argThat(u -> u != null && "user1".equals(u.getUserId()))))
                .thenReturn(emptyResponse());

        mockMvc.perform(get("/api/search").param("q", "osoba").param("source", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.returnedCount").value(0));

        verify(searchService).search(eq("osoba"), isNull(), eq(SearchSource.ALL), eq(20), eq(0), eq("cs"),
                isNull(), isNull(), argThat(u -> u != null && "user1".equals(u.getUserId())));
    }

    @Test
    void search_anonymousWithSourceISMD_returns401() throws Exception {
        when(searchService.search("osoba", null, SearchSource.ISMD, 20, 0, "cs", null, null, null))
                .thenThrow(new SecurityException("Authentication required to search ISMD resources"));

        mockMvc.perform(get("/api/search")
                        .param("q", "osoba")
                        .param("source", "ISMD"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Authentication required to search ISMD resources"));
    }

    /** Boundaries: q needs 4 characters after trimming (FE validation and Virtuoso FT370), limit is 1..100. */
    @ParameterizedTest(name = "q=''{0}'' limit={1} offset={2}")
    @CsvSource(delimiter = '|', textBlock = """
            osoba    | 100 | 0
            osob     | 1   | 0
            """)
    void search_boundaryValues_areAccepted(String q, int limit, int offset) throws Exception {
        when(searchService.search(q, null, null, limit, offset, "cs", null, null, null)).thenReturn(emptyResponse());

        mockMvc.perform(get("/api/search")
                        .param("q", q)
                        .param("limit", String.valueOf(limit))
                        .param("offset", String.valueOf(offset)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.returnedCount").value(0));

        verify(searchService).search(q, null, null, limit, offset, "cs", null, null, null);
    }

    @ParameterizedTest(name = "q=''{0}'' limit={1} offset={2}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            a        | 20  | 0  | Search query must be at least 4 characters long
            oso      | 20  | 0  | Search query must be at least 4 characters long
            "  oso  "| 20  | 0  | Search query must be at least 4 characters long
            ""       | 20  | 0  | Search query must be at least 4 characters long
            osoba    | 101 | 0  | Limit must be between 1 and 100
            osoba    | 0   | 0  | Limit must be between 1 and 100
            osoba    | 20  | -1 | Offset must be non-negative
            """)
    void search_invalidValues_rejectedBeforeService(String q, int limit, int offset, String message) throws Exception {
        mockMvc.perform(get("/api/search")
                        .param("q", q)
                        .param("limit", String.valueOf(limit))
                        .param("offset", String.valueOf(offset)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value(message));

        verifyNoInteractions(searchService);
    }

    @ParameterizedTest
    @CsvSource({"type, INVALID", "source, INVALID", "relationTypes, NOPE"})
    void search_unknownEnumValue_isInvalidParameter(String param, String value) throws Exception {
        mockMvc.perform(get("/api/search")
                        .param("q", "osoba")
                        .param(param, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));

        verifyNoInteractions(searchService);
    }

    @Test
    void search_withMissingQuery_returns400() throws Exception {
        mockMvc.perform(get("/api/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_PARAMETER"));

        verifyNoInteractions(searchService);
    }
}
