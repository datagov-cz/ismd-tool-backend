package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.controller.dto.SearchResponseDto;
import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.enums.*;
import com.dia.ismdtoolbackend.service.impl.SearchServiceImpl;
import com.dia.ismdtoolbackend.service.search.NkdSearchProvider;
import com.dia.ismdtoolbackend.service.search.SearchProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceImplTest {

    @Mock
    private NkdSearchProvider nkdSearchProvider;

    @InjectMocks
    private SearchServiceImpl searchService;

    @Test
    void search_anonymousUser_defaultsToNKD() {
        when(nkdSearchProvider.search(anyString(), any(), anyInt(), anyInt(), anyString(), any(), any(), any()))
                .thenReturn(new SearchProvider.SearchProviderResult(List.of(), 0));

        SearchResponseDto response = searchService.search(
                "osoba", null, null, 20, 0, "cs", null, null, null);

        assertEquals(SearchSourceStatus.OK, response.getSourceStatuses().get(SearchSource.NKD).getStatus());
        assertEquals(SearchSourceStatus.SKIPPED, response.getSourceStatuses().get(SearchSource.ISMD).getStatus());
    }

    @Test
    void search_anonymousWithSourceISMD_throwsSecurityException() {
        assertThrows(SecurityException.class, () ->
                searchService.search("osoba", null, SearchSource.ISMD, 20, 0, "cs", null, null, null));
    }

    @Test
    void search_anonymousWithSourceALL_throwsSecurityException() {
        assertThrows(SecurityException.class, () ->
                searchService.search("osoba", null, SearchSource.ALL, 20, 0, "cs", null, null, null));
    }

    @Test
    void search_authenticatedWithSourceALL_searchesBothSources() {
        SecurityUser user = new SecurityUser("user1", "User One", List.of("ROLE_USER"));

        when(nkdSearchProvider.search(anyString(), any(), anyInt(), anyInt(), anyString(), any(), any(), any()))
                .thenReturn(new SearchProvider.SearchProviderResult(
                        List.of(SearchResultDto.builder()
                                .iri("https://example.org/concept/1")
                                .label("Osoba")
                                .type(SearchType.CONCEPT)
                                .source(SearchSource.NKD)
                                .build()),
                        1));

        SearchResponseDto response = searchService.search(
                "osoba", null, null, 20, 0, "cs", null, null, user);

        assertEquals(SearchSourceStatus.OK, response.getSourceStatuses().get(SearchSource.NKD).getStatus());
        // ISMD is SKIPPED in Phase 2 (not yet implemented)
        assertEquals(SearchSourceStatus.SKIPPED, response.getSourceStatuses().get(SearchSource.ISMD).getStatus());
        assertEquals(1, response.getReturnedCount());
    }

    @Test
    void search_authenticatedWithSourceNKD_onlySearchesNKD() {
        SecurityUser user = new SecurityUser("user1", "User One", List.of("ROLE_USER"));

        when(nkdSearchProvider.search(anyString(), any(), anyInt(), anyInt(), anyString(), any(), any(), any()))
                .thenReturn(new SearchProvider.SearchProviderResult(List.of(), 0));

        SearchResponseDto response = searchService.search(
                "osoba", null, SearchSource.NKD, 20, 0, "cs", null, null, user);

        assertEquals(SearchSourceStatus.OK, response.getSourceStatuses().get(SearchSource.NKD).getStatus());
        assertEquals(SearchSourceStatus.SKIPPED, response.getSourceStatuses().get(SearchSource.ISMD).getStatus());
    }

    @Test
    void search_nkdReturnsResults_resultsIncluded() {
        SearchResultDto nkdResult = SearchResultDto.builder()
                .iri("https://example.org/concept/1")
                .label("Osoba")
                .type(SearchType.CONCEPT)
                .source(SearchSource.NKD)
                .build();

        when(nkdSearchProvider.search(anyString(), any(), anyInt(), anyInt(), anyString(), any(), any(), any()))
                .thenReturn(new SearchProvider.SearchProviderResult(List.of(nkdResult), 1));

        SearchResponseDto response = searchService.search(
                "osoba", null, null, 20, 0, "cs", null, null, null);

        assertEquals(1, response.getResults().size());
        assertEquals("https://example.org/concept/1", response.getResults().get(0).getIri());
    }

    @Test
    void search_nkdThrowsException_returnsErrorStatus() {
        when(nkdSearchProvider.search(anyString(), any(), anyInt(), anyInt(), anyString(), any(), any(), any()))
                .thenThrow(new RuntimeException("Connection refused"));

        SearchResponseDto response = searchService.search(
                "osoba", null, null, 20, 0, "cs", null, null, null);

        assertEquals(SearchSourceStatus.ERROR, response.getSourceStatuses().get(SearchSource.NKD).getStatus());
        assertEquals(0, response.getReturnedCount());
    }

    @Test
    void search_preservesLimitAndOffset() {
        when(nkdSearchProvider.search(anyString(), any(), anyInt(), anyInt(), anyString(), any(), any(), any()))
                .thenReturn(new SearchProvider.SearchProviderResult(List.of(), 0));

        SearchResponseDto response = searchService.search(
                "osoba", null, null, 50, 10, "cs", null, null, null);

        assertEquals(50, response.getLimit());
        assertEquals(10, response.getOffset());
    }
}
