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

import static org.assertj.core.api.Assertions.assertThat;
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
                nkdSearchProvider.search("osoba", null, 20, 0, "cs", null, null, null, false, null);

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
        row.put("roleTrida", "http://www.w3.org/2002/07/owl#Class");

        when(nkdSparqlClient.executeSelect(anyString())).thenReturn(List.of(row));

        SearchProvider.SearchProviderResult result =
                nkdSearchProvider.search("osoba", SearchType.CONCEPT, 20, 0, "cs", null, null, null, false, null);

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
        // Unfiltered search (type=CONCEPT → roleFilter null): conceptType is derived
        // from the projected role marker, not left null.
        assertEquals(com.dia.ismdtoolbackend.enums.ConceptType.TRIDA, dto.getConceptType());
        assertEquals(SearchSource.NKD, dto.getSource());
    }

    @Test
    void search_conceptType_derivedPerRoleMarker() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        Map<String, String> trida = conceptRow("https://example.org/c/trida");
        trida.put("roleTrida", "https://slovník.gov.cz/základní/pojem/třída");
        Map<String, String> vlastnost = conceptRow("https://example.org/c/vlastnost");
        vlastnost.put("roleVlastnost", "http://www.w3.org/2002/07/owl#DatatypeProperty");
        Map<String, String> vztah = conceptRow("https://example.org/c/vztah");
        vztah.put("roleVztah", "http://www.w3.org/2002/07/owl#ObjectProperty");
        Map<String, String> untyped = conceptRow("https://example.org/c/untyped");

        when(nkdSparqlClient.executeSelect(anyString()))
                .thenReturn(List.of(trida, vlastnost, vztah, untyped));

        SearchProvider.SearchProviderResult result =
                nkdSearchProvider.search("x", SearchType.CONCEPT, 20, 0, "cs", null, null, null, false, null);

        Map<String, com.dia.ismdtoolbackend.enums.ConceptType> byIri = new LinkedHashMap<>();
        for (SearchResultDto dto : result.results()) {
            byIri.put(dto.getIri(), dto.getConceptType());
        }
        assertEquals(com.dia.ismdtoolbackend.enums.ConceptType.TRIDA, byIri.get("https://example.org/c/trida"));
        assertEquals(com.dia.ismdtoolbackend.enums.ConceptType.VLASTNOST, byIri.get("https://example.org/c/vlastnost"));
        assertEquals(com.dia.ismdtoolbackend.enums.ConceptType.VZTAH, byIri.get("https://example.org/c/vztah"));
        assertNull(byIri.get("https://example.org/c/untyped"));
    }

    private static Map<String, String> conceptRow(String iri) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("resource", iri);
        row.put("label", "L");
        row.put("labelLang", "cs");
        row.put("ontology", "https://example.org/ontology/1");
        return row;
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

        stubQueries(List.of("https://example.org/ontology/1"), List.of(row), List.of());

        SearchProvider.SearchProviderResult result =
                nkdSearchProvider.search("osoby", SearchType.ONTOLOGY, 20, 0, "cs", null, null, null, false, null);

        assertEquals(1, result.results().size());
        SearchResultDto dto = result.results().get(0);
        assertEquals(SearchType.ONTOLOGY, dto.getType());
        assertEquals(SearchSource.NKD, dto.getSource());
        assertEquals("Slovník osob", dto.getLabel());
    }

    /**
     * Answers each NKD query by its fingerprint, so the test does not depend on call order:
     * the candidate-IRI list, the ontology and concept pages, the concept-count batch
     * (empty) and both totals (1).
     */
    private void stubQueries(List<String> ontologyIris, List<Map<String, String>> ontologyRows,
                             List<Map<String, String>> conceptRows) {
        when(nkdSparqlClient.executeSelect(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.contains("AS ?total")) return List.of(Map.of("total", "1"));
            if (q.contains("AS ?cnt")) return List.of();
            if (q.contains("?roleTrida")) return conceptRows;
            if (q.contains("?ontologyIri")) return ontologyRows;
            if (q.contains("owl:Ontology")) {
                return ontologyIris.stream().map(iri -> Map.of("resource", iri)).toList();
            }
            throw new AssertionError("Unexpected NKD query: " + q);
        });
    }

    /** Stubs the candidate-IRI-list query: the only one with "owl:Ontology" and no bif:contains. */
    private void stubOntologyIriList(List<String> iris) {
        List<Map<String, String>> rows = iris.stream()
                .map(iri -> Map.of("resource", iri))
                .toList();
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.argThat(
                q -> q != null && q.contains("owl:Ontology") && !q.contains("bif:contains"))))
                .thenReturn(rows);
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

        stubQueries(List.of("https://example.org/ontology/1"), List.of(ontologyRow), List.of(conceptRow));

        SearchProvider.SearchProviderResult result =
                nkdSearchProvider.search("test", null, 20, 0, "cs", null, null, null, false, null);

        assertEquals(2, result.results().size());
    }

    @Test
    void search_duplicateIris_deduplicatedByIri() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        // Same IRI returned from ontology and concept searches
        String iri = "https://example.org/resource/1";
        Map<String, String> ontologyRow = new LinkedHashMap<>();
        ontologyRow.put("resource", iri);
        ontologyRow.put("label", "First");
        ontologyRow.put("description", "Popis slovníku");
        ontologyRow.put("ontologyIri", iri);

        Map<String, String> conceptRow = new LinkedHashMap<>();
        conceptRow.put("resource", iri);
        conceptRow.put("label", "Second");
        conceptRow.put("definition", "Definice pojmu");
        conceptRow.put("ontology", iri);

        stubQueries(List.of(iri), List.of(ontologyRow), List.of(conceptRow));

        SearchProvider.SearchProviderResult result =
                nkdSearchProvider.search("test", null, 20, 0, "cs", null, null, null, false, null);

        // The ontology row is kept; non-null concept fields fold into it.
        assertThat(result.results()).singleElement().satisfies(dto -> {
            assertThat(dto.getType()).isEqualTo(SearchType.ONTOLOGY);
            assertThat(dto.getLabel()).isEqualTo("Second");
            assertThat(dto.getDescription()).isEqualTo("Popis slovníku");
            assertThat(dto.getDefinition()).isEqualTo("Definice pojmu");
        });
    }

    @Test
    void search_emptyResults_returnsEmptyList() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        when(nkdSparqlClient.executeSelect(anyString())).thenReturn(List.of());

        SearchProvider.SearchProviderResult result =
                nkdSearchProvider.search("xyz", SearchType.CONCEPT, 20, 0, "cs", null, null, null, false, null);

        assertTrue(result.results().isEmpty());
        assertEquals(0, result.totalCount());
    }

    @Test
    void search_rowWithMissingResourceBinding_skippedAndOthersReturned() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        // Row missing "resource" binding — getIri() will be null
        Map<String, String> badRow = new LinkedHashMap<>();
        badRow.put("label", "No IRI");

        Map<String, String> goodRow = new LinkedHashMap<>();
        goodRow.put("resource", "https://example.org/concept/1");
        goodRow.put("label", "Good");
        goodRow.put("ontology", "https://example.org/ontology/1");

        when(nkdSparqlClient.executeSelect(anyString())).thenReturn(List.of(badRow, goodRow));

        SearchProvider.SearchProviderResult result =
                nkdSearchProvider.search("test", SearchType.CONCEPT, 20, 0, "cs", null, null, null, false, null);

        assertEquals(1, result.results().size());
        assertEquals("https://example.org/concept/1", result.results().get(0).getIri());
    }

    @Test
    void search_ontologyResults_lastModifiedAndConceptCountPopulated() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        Map<String, String> ontologyRow = new LinkedHashMap<>();
        ontologyRow.put("resource", "https://example.org/ontology/1");
        ontologyRow.put("label", "Slovník");
        ontologyRow.put("ontologyIri", "https://example.org/ontology/1");
        ontologyRow.put("modified", "2024-03-04");

        // IRI-list prelude → 1 candidate; page query → ontologyRow;
        // concept-counts batch → cnt=42; ontology COUNT → total=1.
        // Match by query content so each stub hits its query.
        stubOntologyIriList(List.of("https://example.org/ontology/1"));
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("LIMIT")))
                .thenReturn(List.of(ontologyRow));
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("GROUP BY ?ontology")))
                .thenReturn(List.of(Map.of("ontology", "https://example.org/ontology/1", "cnt", "42")));
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("COUNT(DISTINCT ?resource)")))
                .thenReturn(List.of(Map.of("total", "1")));

        SearchProvider.SearchProviderResult result =
                nkdSearchProvider.search("slovník", SearchType.ONTOLOGY, 20, 0, "cs", null, null, null, false, null);

        assertEquals(1, result.results().size());
        SearchResultDto dto = result.results().get(0);
        assertEquals("2024-03-04", dto.getLastModified());
        assertEquals(42, dto.getConceptCount());
        assertEquals(1, result.totalOntologies());
        assertEquals(0, result.totalConcepts());
    }

    @Test
    void search_conceptResults_lastModifiedPopulatedNoConceptCount() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        Map<String, String> conceptRow = new LinkedHashMap<>();
        conceptRow.put("resource", "https://example.org/concept/1");
        conceptRow.put("label", "Pojem");
        conceptRow.put("ontology", "https://example.org/ontology/1");
        conceptRow.put("modified", "2025-06-15T10:30:00");

        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("LIMIT")))
                .thenReturn(List.of(conceptRow));
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("COUNT(DISTINCT ?resource)")))
                .thenReturn(List.of(Map.of("total", "1")));

        SearchProvider.SearchProviderResult result =
                nkdSearchProvider.search("pojem", SearchType.CONCEPT, 20, 0, "cs", null, null, null, false, null);

        assertEquals(1, result.results().size());
        SearchResultDto dto = result.results().get(0);
        assertEquals("2025-06-15T10:30:00", dto.getLastModified());
        // Concept results don't carry conceptCount (it's an ontology-only field).
        assertNull(dto.getConceptCount());
        assertEquals(0, result.totalOntologies());
        assertEquals(1, result.totalConcepts());
    }

    @Test
    void search_ontologyIriListFails_ontologySearchYieldsEmpty() {
        // The IRI-list query is the first call when wantsOntologies is true.
        // If it fails (upstream down, syntax bug, etc.) we must not blow up the
        // whole search — concepts still come through, ontology results are empty.
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        Map<String, String> conceptRow = new LinkedHashMap<>();
        conceptRow.put("resource", "https://example.org/concept/1");
        conceptRow.put("label", "Pojem");
        conceptRow.put("ontology", "https://example.org/ontology/1");

        // IRI list throws; concept page + concept count succeed.
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.argThat(
                q -> q != null && q.contains("owl:Ontology") && !q.contains("bif:contains"))))
                .thenThrow(new RuntimeException("upstream boom"));
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("LIMIT")))
                .thenReturn(List.of(conceptRow));
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("COUNT(DISTINCT ?resource)")))
                .thenReturn(List.of(Map.of("total", "1")));

        SearchProvider.SearchProviderResult result =
                nkdSearchProvider.search("test", null, 20, 0, "cs", null, null, null, false, null);

        assertEquals(1, result.results().size());
        assertEquals("https://example.org/concept/1", result.results().get(0).getIri());
        // Ontology branch returns empty results — totalOntologies must be 0,
        // not null (we attempted the count and got a deterministic zero).
        assertEquals(0, result.totalOntologies());
        assertEquals(1, result.totalConcepts());
    }

    @Test
    void search_countQueryFails_totalsNullButResultsReturn() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        Map<String, String> conceptRow = new LinkedHashMap<>();
        conceptRow.put("resource", "https://example.org/concept/1");
        conceptRow.put("label", "Pojem");
        conceptRow.put("ontology", "https://example.org/ontology/1");

        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("LIMIT")))
                .thenReturn(List.of(conceptRow));
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("COUNT(DISTINCT ?resource)")))
                .thenThrow(new org.apache.jena.sparql.engine.http.QueryExceptionHTTP(503, "Service unavailable"));

        SearchProvider.SearchProviderResult result =
                nkdSearchProvider.search("pojem", SearchType.CONCEPT, 20, 0, "cs", null, null, null, false, null);

        assertEquals(1, result.results().size());
        // Total-count failure must not blank the page — just leave totals null.
        assertNull(result.totalConcepts());
    }
}
