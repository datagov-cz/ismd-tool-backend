package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.enums.SearchType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NkdSearchProviderTest {

    @Mock
    private NkdSparqlClient nkdSparqlClient;

    @InjectMocks
    private NkdSearchProvider nkdSearchProvider;

    @Test
    void search_endpointNotConfigured_returnsEmptyResults() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(false);

        SearchProvider.SearchProviderResult result =
                nkdSearchProvider.search("osoba", null, 20, 0, "cs", null, null, null);

        assertTrue(result.results().isEmpty());
        assertEquals(0, result.totalCount());
    }

    @Test
    void search_conceptType_returnsMappedResults() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        Map<String, String> row = new LinkedHashMap<>();
        row.put("resource", "https://example.org/concept/1");
        row.put("label", "Osoba");
        row.put("labelLang", "cs");
        row.put("altName", "Fyzická osoba");
        row.put("description", "Popis osoby");
        row.put("definition", "Definice osoby");
        row.put("ontology", "https://example.org/ontology/1");

        when(nkdSparqlClient.executeSelect(anyString())).thenReturn(List.of(row));

        SearchProvider.SearchProviderResult result =
                nkdSearchProvider.search("osoba", SearchType.CONCEPT, 20, 0, "cs", null, null, null);

        assertEquals(1, result.results().size());
        SearchResultDto dto = result.results().get(0);
        assertEquals("https://example.org/concept/1", dto.getIri());
        assertEquals("Osoba", dto.getLabel());
        assertEquals("cs", dto.getLabelLang());
        assertEquals("Fyzická osoba", dto.getAltName());
        assertEquals("Popis osoby", dto.getDescription());
        assertEquals("Definice osoby", dto.getDefinition());
        assertEquals("https://example.org/ontology/1", dto.getOntologyIri());
        assertEquals(SearchType.CONCEPT, dto.getType());
        assertEquals(SearchSource.NKD, dto.getSource());
    }

    @Test
    void search_ontologyType_returnsMappedResults() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        Map<String, String> row = new LinkedHashMap<>();
        row.put("resource", "https://example.org/ontology/1");
        row.put("label", "Slovník osob");
        row.put("labelLang", "cs");
        row.put("description", "Ontologie pro osoby");
        row.put("ontologyIri", "https://example.org/ontology/1");

        when(nkdSparqlClient.executeSelect(anyString())).thenReturn(List.of(row));

        SearchProvider.SearchProviderResult result =
                nkdSearchProvider.search("osoby", SearchType.ONTOLOGY, 20, 0, "cs", null, null, null);

        assertEquals(1, result.results().size());
        SearchResultDto dto = result.results().get(0);
        assertEquals(SearchType.ONTOLOGY, dto.getType());
        assertEquals(SearchSource.NKD, dto.getSource());
        assertEquals("Slovník osob", dto.getLabel());
    }

    @Test
    void search_noTypeFilter_searchesBoth() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        Map<String, String> ontologyRow = new LinkedHashMap<>();
        ontologyRow.put("resource", "https://example.org/ontology/1");
        ontologyRow.put("label", "Ontologie");
        ontologyRow.put("ontologyIri", "https://example.org/ontology/1");

        Map<String, String> conceptRow = new LinkedHashMap<>();
        conceptRow.put("resource", "https://example.org/concept/1");
        conceptRow.put("label", "Koncept");
        conceptRow.put("ontology", "https://example.org/ontology/1");

        // First call = ontology search, second call = concept search
        when(nkdSparqlClient.executeSelect(anyString()))
                .thenReturn(List.of(ontologyRow))
                .thenReturn(List.of(conceptRow));

        SearchProvider.SearchProviderResult result =
                nkdSearchProvider.search("test", null, 20, 0, "cs", null, null, null);

        assertEquals(2, result.results().size());
    }

    @Test
    void search_duplicateIris_deduplicatedByIri() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        // Same IRI returned from ontology and concept searches
        Map<String, String> row1 = new LinkedHashMap<>();
        row1.put("resource", "https://example.org/resource/1");
        row1.put("label", "First");
        row1.put("ontologyIri", "https://example.org/resource/1");

        Map<String, String> row2 = new LinkedHashMap<>();
        row2.put("resource", "https://example.org/resource/1");
        row2.put("label", "Second");
        row2.put("ontology", "https://example.org/resource/1");

        when(nkdSparqlClient.executeSelect(anyString()))
                .thenReturn(List.of(row1))
                .thenReturn(List.of(row2));

        SearchProvider.SearchProviderResult result =
                nkdSearchProvider.search("test", null, 20, 0, "cs", null, null, null);

        // Should be deduped to 1 result
        assertEquals(1, result.results().size());
    }

    @Test
    void search_emptyResults_returnsEmptyList() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        when(nkdSparqlClient.executeSelect(anyString())).thenReturn(List.of());

        SearchProvider.SearchProviderResult result =
                nkdSearchProvider.search("xyz", SearchType.CONCEPT, 20, 0, "cs", null, null, null);

        assertTrue(result.results().isEmpty());
        assertEquals(0, result.totalCount());
    }
}
