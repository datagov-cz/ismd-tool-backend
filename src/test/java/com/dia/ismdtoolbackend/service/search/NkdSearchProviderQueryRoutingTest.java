package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.enums.SearchType;
import com.dia.ismdtoolbackend.query.NKDSPARQLBrowseQuery;
import com.dia.ismdtoolbackend.query.NKDSPARQLSearchQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The NKD provider answered per query: each SPARQL string the provider builds is routed to its own canned
 * response, so a test states which query fails or returns what, independent of call order.
 */
@ExtendWith(MockitoExtension.class)
class NkdSearchProviderQueryRoutingTest {

    private static final String QUERY = "osoba";
    private static final String ONTOLOGY = "https://data.gov.cz/zdroj/slovník/osoby";
    private static final String OTHER_ONTOLOGY = "https://data.gov.cz/zdroj/slovník/adresy";
    private static final String CONCEPT = ONTOLOGY + "/pojem/osoba";

    @Mock
    private NkdSparqlClient nkd;

    private NkdSearchProvider provider;
    private final Map<String, Supplier<List<Map<String, String>>>> answers = new HashMap<>();

    @BeforeEach
    void setUp() {
        provider = new NkdSearchProvider(nkd);
        when(nkd.isEndpointConfigured()).thenReturn(true);
        when(nkd.executeSelect(anyString())).thenAnswer(call -> {
            Supplier<List<Map<String, String>>> answer = answers.get(call.<String>getArgument(0));
            assertThat(answer).as("no canned answer for query:%n%s", call.<String>getArgument(0)).isNotNull();
            return answer.get();
        });
    }

    private void answerOntologyIris(List<Map<String, String>> rows) {
        answers.put(NKDSPARQLSearchQuery.buildOntologyIriListQuery(), () -> rows);
    }

    private void answerOntologySearch(List<String> candidates, List<Map<String, String>> rows) {
        answers.put(NKDSPARQLSearchQuery.buildOntologySearchQuery(QUERY, "cs", 20, 0, candidates), () -> rows);
    }

    private void answerConceptSearch(List<Map<String, String>> rows) {
        answers.put(NKDSPARQLSearchQuery.buildConceptSearchQuery(QUERY, "cs", 20, 0, null, null, null), () -> rows);
    }

    private void answerConceptCounts(List<String> ontologies, Supplier<List<Map<String, String>>> rows) {
        answers.put(NKDSPARQLBrowseQuery.buildConceptCountsForOntologiesQuery(ontologies), rows);
    }

    private void answerOntologyTotal(List<String> candidates, Supplier<List<Map<String, String>>> rows) {
        answers.put(NKDSPARQLSearchQuery.buildOntologySearchCountQuery(QUERY, candidates), rows);
    }

    private void answerConceptTotal(Supplier<List<Map<String, String>>> rows) {
        answers.put(NKDSPARQLSearchQuery.buildConceptSearchCountQuery(QUERY, null, null, null), rows);
    }

    private SearchProvider.SearchProviderResult searchAll() {
        return provider.search(QUERY, null, 20, 0, "cs", null, null, null, false, null);
    }

    @Test
    void sameIriFromBothBranchesKeepsTheOntologyRowAndTakesTheConceptsNonNullFields() {
        answerOntologyIris(List.of(Map.of("resource", ONTOLOGY)));
        answerOntologySearch(List.of(ONTOLOGY), List.of(Map.of(
                "resource", ONTOLOGY, "label", "Osoby", "description", "Slovník osob")));
        answerConceptSearch(List.of(Map.of("resource", ONTOLOGY, "label", "Osoby (pojem)", "definition", "Def")));
        answerConceptCounts(List.of(ONTOLOGY), () -> List.of(Map.of("ontology", ONTOLOGY, "cnt", "5")));
        answerOntologyTotal(List.of(ONTOLOGY), () -> List.of(Map.of("total", "1")));
        answerConceptTotal(() -> List.of(Map.of("total", "1")));

        List<SearchResultDto> results = searchAll().results();

        assertThat(results).singleElement().satisfies(row -> {
            assertThat(row.getType()).isEqualTo(SearchType.ONTOLOGY);
            assertThat(row.getLabel()).isEqualTo("Osoby (pojem)");
            assertThat(row.getDescription()).isEqualTo("Slovník osob");
            assertThat(row.getDefinition()).isEqualTo("Def");
            assertThat(row.getConceptCount()).isEqualTo(5);
        });
    }

    @Test
    void blankCandidateIrisAreNotSearched() {
        answerOntologyIris(List.of(Map.of("resource", " "), Map.of("label", "no iri")));
        answerConceptSearch(List.of());
        answerConceptTotal(() -> List.of(Map.of("total", "0")));

        SearchProvider.SearchProviderResult result = searchAll();

        assertThat(result.results()).isEmpty();
        assertThat(result.totalOntologies()).isZero();
    }

    @Test
    void failingCandidateListMeansNoOntologiesButConceptsStillAnswer() {
        answers.put(NKDSPARQLSearchQuery.buildOntologyIriListQuery(), () -> {
            throw new IllegalStateException("NKD down");
        });
        answerConceptSearch(List.of(Map.of("resource", CONCEPT, "label", "Osoba", "roleVztah", "x")));
        answerConceptTotal(() -> List.of(Map.of("total", "1")));

        SearchProvider.SearchProviderResult result = searchAll();

        assertThat(result.results()).singleElement().satisfies(row ->
                assertThat(row.getConceptType()).isEqualTo(com.dia.ismdtoolbackend.enums.ConceptType.VZTAH));
        assertThat(result.totalOntologies()).isZero();
        assertThat(result.totalConcepts()).isEqualTo(1);
    }

    @Test
    void conceptCountsDefaultToZeroAndStayUnknownWhenUnreadable() {
        answerOntologyIris(List.of(Map.of("resource", ONTOLOGY), Map.of("resource", OTHER_ONTOLOGY)));
        answerOntologySearch(List.of(ONTOLOGY, OTHER_ONTOLOGY), List.of(
                Map.of("resource", ONTOLOGY, "label", "Osoby"),
                Map.of("resource", OTHER_ONTOLOGY, "label", "Adresy")));
        answerConceptSearch(List.of());
        answerConceptCounts(List.of(ONTOLOGY, OTHER_ONTOLOGY),
                () -> List.of(Map.of("ontology", ONTOLOGY, "cnt", "not-a-number")));
        answerOntologyTotal(List.of(ONTOLOGY, OTHER_ONTOLOGY), () -> List.of(Map.of("total", "2")));
        answerConceptTotal(() -> List.of(Map.of("total", "0")));

        List<SearchResultDto> results = searchAll().results();

        assertThat(results).extracting(SearchResultDto::getIri).containsExactly(ONTOLOGY, OTHER_ONTOLOGY);
        assertThat(results.get(0).getConceptCount()).isNull();
        assertThat(results.get(1).getConceptCount()).isZero();
    }

    @Test
    void failedCountBatchLeavesEveryCountUnknown() {
        answerOntologyIris(List.of(Map.of("resource", ONTOLOGY)));
        answerOntologySearch(List.of(ONTOLOGY), List.of(Map.of("resource", ONTOLOGY, "label", "Osoby")));
        answerConceptSearch(List.of());
        answerConceptCounts(List.of(ONTOLOGY), () -> {
            throw new IllegalStateException("timeout");
        });
        answerOntologyTotal(List.of(ONTOLOGY), () -> List.of(Map.of("total", "1")));
        answerConceptTotal(() -> List.of(Map.of("total", "0")));

        assertThat(searchAll().results()).singleElement()
                .satisfies(row -> assertThat(row.getConceptCount()).isNull());
    }

    @Test
    void failingOntologyTotalIsUnknownAndLeftOutOfTheSum() {
        answerOntologyIris(List.of(Map.of("resource", ONTOLOGY)));
        answerOntologySearch(List.of(ONTOLOGY), List.of());
        answerConceptSearch(List.of());
        answerOntologyTotal(List.of(ONTOLOGY), () -> {
            throw new IllegalStateException("timeout");
        });
        answerConceptTotal(() -> List.of(Map.of("total", "4")));

        SearchProvider.SearchProviderResult result = searchAll();

        assertThat(result.totalOntologies()).isNull();
        assertThat(result.totalConcepts()).isEqualTo(4);
        assertThat(result.totalCount()).isEqualTo(4);
    }

    @Test
    void unreadableOrMissingTotalsCountAsZero() {
        answerOntologyIris(List.of(Map.of("resource", ONTOLOGY)));
        answerOntologySearch(List.of(ONTOLOGY), List.of());
        answerConceptSearch(List.of());
        answerOntologyTotal(List.of(ONTOLOGY), () -> List.of(Map.of("total", "lots")));
        answerConceptTotal(() -> List.of(Map.of("other", "1")));

        SearchProvider.SearchProviderResult result = searchAll();

        assertThat(result.totalOntologies()).isZero();
        assertThat(result.totalConcepts()).isZero();
    }
}
