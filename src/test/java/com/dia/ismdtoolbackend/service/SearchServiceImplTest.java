package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.controller.dto.SearchResponseDto;
import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.enums.*;
import com.dia.ismdtoolbackend.service.impl.SearchServiceImpl;
import com.dia.ismdtoolbackend.service.search.SearchProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SearchServiceImplTest {

    @Mock
    private SearchProvider nkdSearchProvider;

    @Mock
    private SearchProvider ismdSearchProvider;

    private SearchServiceImpl searchService;

    @BeforeEach
    void setUp() {
        Executor directExecutor = Runnable::run;
        searchService = new SearchServiceImpl(
                nkdSearchProvider, ismdSearchProvider, directExecutor, 10_000);
    }

    @Test
    void search_anonymousUser_defaultsToNKD() {
        when(nkdSearchProvider.search(anyString(), any(), anyInt(), anyInt(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new SearchProvider.SearchProviderResult(List.of(), 0));

        SearchResponseDto response = searchService.search(
                "osoba", null, null, 20, 0, "cs", null, null, null);

        assertThat(response.getSourceStatuses().get(SearchSource.NKD).getStatus()).isEqualTo(SearchSourceStatus.OK);
        assertThat(response.getSourceStatuses().get(SearchSource.ISMD).getStatus())
                .isEqualTo(SearchSourceStatus.SKIPPED);
    }

    @Test
    void search_anonymousWithSourceISMD_throwsSecurityException() {
        assertThatThrownBy(() ->
                searchService.search("osoba", null, SearchSource.ISMD, 20, 0, "cs", null, null, null))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void search_anonymousWithSourceALL_throwsSecurityException() {
        assertThatThrownBy(() ->
                searchService.search("osoba", null, SearchSource.ALL, 20, 0, "cs", null, null, null))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void search_authenticatedWithSourceALL_searchesBothSources() {
        SecurityUser user = new SecurityUser("user1", "User One", List.of("ROLE_USER"));

        when(nkdSearchProvider.search(anyString(), any(), anyInt(), anyInt(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new SearchProvider.SearchProviderResult(
                        List.of(SearchResultDto.builder()
                                .iri("https://example.org/concept/1")
                                .label("Osoba")
                                .type(SearchType.CONCEPT)
                                .source(SearchSource.NKD)
                                .build()),
                        1));

        when(ismdSearchProvider.search(anyString(), any(), anyInt(), anyInt(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new SearchProvider.SearchProviderResult(
                        List.of(SearchResultDto.builder()
                                .iri("https://example.org/concept/2")
                                .label("Osoba ISMD")
                                .type(SearchType.CONCEPT)
                                .source(SearchSource.ISMD)
                                .build()),
                        1));

        SearchResponseDto response = searchService.search(
                "osoba", null, null, 20, 0, "cs", null, null, user);

        assertThat(response.getSourceStatuses().get(SearchSource.NKD).getStatus()).isEqualTo(SearchSourceStatus.OK);
        assertThat(response.getSourceStatuses().get(SearchSource.ISMD).getStatus()).isEqualTo(SearchSourceStatus.OK);
        assertThat(response.getReturnedCount()).isEqualTo(2);
    }

    @Test
    void search_authenticatedWithSourceNKD_onlySearchesNKD() {
        SecurityUser user = new SecurityUser("user1", "User One", List.of("ROLE_USER"));

        when(nkdSearchProvider.search(anyString(), any(), anyInt(), anyInt(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new SearchProvider.SearchProviderResult(List.of(), 0));

        SearchResponseDto response = searchService.search(
                "osoba", null, SearchSource.NKD, 20, 0, "cs", null, null, user);

        assertThat(response.getSourceStatuses().get(SearchSource.NKD).getStatus()).isEqualTo(SearchSourceStatus.OK);
        assertThat(response.getSourceStatuses().get(SearchSource.ISMD).getStatus())
                .isEqualTo(SearchSourceStatus.SKIPPED);
    }

    @Test
    void search_nkdReturnsResults_resultsIncluded() {
        SearchResultDto nkdResult = SearchResultDto.builder()
                .iri("https://example.org/concept/1")
                .label("Osoba")
                .type(SearchType.CONCEPT)
                .source(SearchSource.NKD)
                .build();

        when(nkdSearchProvider.search(anyString(), any(), anyInt(), anyInt(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new SearchProvider.SearchProviderResult(List.of(nkdResult), 1));

        SearchResponseDto response = searchService.search(
                "osoba", null, null, 20, 0, "cs", null, null, null);

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getIri()).isEqualTo("https://example.org/concept/1");
    }

    @Test
    void search_nkdThrowsException_returnsErrorStatus() {
        when(nkdSearchProvider.search(anyString(), any(), anyInt(), anyInt(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new RuntimeException("Connection refused"));

        SearchResponseDto response = searchService.search(
                "osoba", null, null, 20, 0, "cs", null, null, null);

        assertThat(response.getSourceStatuses().get(SearchSource.NKD).getStatus()).isEqualTo(SearchSourceStatus.ERROR);
        assertThat(response.getReturnedCount()).isEqualTo(0);
    }

    @Test
    void search_preservesLimitAndOffset() {
        when(nkdSearchProvider.search(anyString(), any(), anyInt(), anyInt(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new SearchProvider.SearchProviderResult(List.of(), 0));

        SearchResponseDto response = searchService.search(
                "osoba", null, null, 50, 10, "cs", null, null, null);

        assertThat(response.getLimit()).isEqualTo(50);
        assertThat(response.getOffset()).isEqualTo(10);
        // The page window reaches the provider unchanged.
        verify(nkdSearchProvider).search(eq("osoba"), isNull(), eq(50), eq(10), eq("cs"),
                any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void search_authenticatedWithSourceISMD_onlySearchesISMD() {
        SecurityUser user = new SecurityUser("user1", "User One", List.of("ROLE_USER"));

        when(ismdSearchProvider.search(anyString(), any(), anyInt(), anyInt(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new SearchProvider.SearchProviderResult(List.of(), 0));

        SearchResponseDto response = searchService.search(
                "osoba", null, SearchSource.ISMD, 20, 0, "cs", null, null, user);

        assertThat(response.getSourceStatuses().get(SearchSource.ISMD).getStatus()).isEqualTo(SearchSourceStatus.OK);
        assertThat(response.getSourceStatuses().get(SearchSource.NKD).getStatus())
                .isEqualTo(SearchSourceStatus.SKIPPED);
        verifyNoInteractions(nkdSearchProvider);
    }

    @Test
    void search_bothSourcesReturnSameIri_keepsBothAsDistinctResources() {
        SecurityUser user = new SecurityUser("user1", "User One", List.of("ROLE_USER"));
        String sharedIri = "https://example.org/concept/shared";

        when(nkdSearchProvider.search(anyString(), any(), anyInt(), anyInt(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new SearchProvider.SearchProviderResult(
                        List.of(SearchResultDto.builder()
                                .iri(sharedIri)
                                .label("From NKD")
                                .source(SearchSource.NKD)
                                .build()),
                        1));

        when(ismdSearchProvider.search(anyString(), any(), anyInt(), anyInt(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new SearchProvider.SearchProviderResult(
                        List.of(SearchResultDto.builder()
                                .iri(sharedIri)
                                .label("From ISMD")
                                .source(SearchSource.ISMD)
                                .build()),
                        1));

        SearchResponseDto response = searchService.search(
                "osoba", null, null, 20, 0, "cs", null, null, user);

        // An ISMD working copy shares its NKD original's IRI verbatim; the two are
        // distinct resources, so both survive the merge and are told apart by source.
        assertThat(response.getReturnedCount()).isEqualTo(2);
        assertThat(response.getResults().stream().map(SearchResultDto::getLabel).toList())
                .isEqualTo(List.of("From NKD", "From ISMD"));
        assertThat(response.getResults().stream().map(SearchResultDto::getSource).toList())
                .isEqualTo(List.of(SearchSource.NKD, SearchSource.ISMD));
    }

    @Test
    void search_sameSourceRepeatsIri_deduplicatesWithinSource() {
        SecurityUser user = new SecurityUser("user1", "User One", List.of("ROLE_USER"));
        String repeatedIri = "https://example.org/concept/repeated";

        when(nkdSearchProvider.search(anyString(), any(), anyInt(), anyInt(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new SearchProvider.SearchProviderResult(
                        List.of(SearchResultDto.builder()
                                        .iri(repeatedIri)
                                        .label("First")
                                        .source(SearchSource.NKD)
                                        .build(),
                                SearchResultDto.builder()
                                        .iri(repeatedIri)
                                        .label("Duplicate")
                                        .source(SearchSource.NKD)
                                        .build()),
                        2));

        SearchResponseDto response = searchService.search(
                "osoba", null, SearchSource.NKD, 20, 0, "cs", null, null, user);

        assertThat(response.getReturnedCount()).isEqualTo(1);
        assertThat(response.getResults().get(0).getLabel()).isEqualTo("First");
    }

    @Test
    void search_ontologyWorkingCopySharesNkdIri_bothReturned() {
        SecurityUser user = new SecurityUser("user1", "User One", List.of("ROLE_USER"));
        String sharedIri = "https://slovník.gov.cz/a3791---registr-vysokých-škol";

        when(nkdSearchProvider.search(anyString(), any(), anyInt(), anyInt(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new SearchProvider.SearchProviderResult(
                        List.of(SearchResultDto.builder()
                                .iri(sharedIri)
                                .label("A3791 - Registr Vysokých škol")
                                .type(SearchType.ONTOLOGY)
                                .source(SearchSource.NKD)
                                .build()),
                        1, 1, 0));

        when(ismdSearchProvider.search(anyString(), any(), anyInt(), anyInt(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new SearchProvider.SearchProviderResult(
                        List.of(SearchResultDto.builder()
                                .iri(sharedIri)
                                .label("A3791 - Registr Vysokých škol")
                                .type(SearchType.ONTOLOGY)
                                .source(SearchSource.ISMD)
                                .isPublished(false)
                                .build()),
                        1, 1, 0));

        SearchResponseDto response = searchService.search(
                "škol", SearchType.ONTOLOGY, null, 20, 0, "cs", null, null, user);

        // The local copy must not be swallowed by the NKD original it was cloned from.
        assertThat(response.getReturnedCount()).isEqualTo(2);
        assertThat(response.getTotalOntologies()).isEqualTo(2);
        assertThat(response.getResults().stream()
                .anyMatch(r -> r.getSource() == SearchSource.ISMD))
                .isTrue();
        assertThat(response.getResults().stream()
                .anyMatch(r -> r.getSource() == SearchSource.NKD))
                .isTrue();
    }

    @Test
    void search_ismdThrowsException_returnsErrorStatusWithNkdResults() {
        SecurityUser user = new SecurityUser("user1", "User One", List.of("ROLE_USER"));

        when(nkdSearchProvider.search(anyString(), any(), anyInt(), anyInt(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new SearchProvider.SearchProviderResult(
                        List.of(SearchResultDto.builder()
                                .iri("https://example.org/concept/1")
                                .label("Osoba")
                                .source(SearchSource.NKD)
                                .build()),
                        1));

        when(ismdSearchProvider.search(anyString(), any(), anyInt(), anyInt(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new RuntimeException("Database connection failed"));

        SearchResponseDto response = searchService.search(
                "osoba", null, null, 20, 0, "cs", null, null, user);

        assertThat(response.getSourceStatuses().get(SearchSource.NKD).getStatus()).isEqualTo(SearchSourceStatus.OK);
        assertThat(response.getSourceStatuses().get(SearchSource.ISMD).getStatus()).isEqualTo(SearchSourceStatus.ERROR);
        assertThat(response.getReturnedCount()).isEqualTo(1);
    }
}
