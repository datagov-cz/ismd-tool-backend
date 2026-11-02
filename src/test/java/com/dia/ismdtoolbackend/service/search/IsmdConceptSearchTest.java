package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.MatchedBy;
import com.dia.ismdtoolbackend.enums.RelationType;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.enums.SearchType;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IsmdConceptSearchTest {

    private static final String QUERY = "adresa";
    private static final String GRAPH = "https://slovník.gov.cz/agendový/104";
    private static final String A = GRAPH + "/pojem/adresa";
    private static final String B = GRAPH + "/pojem/adresní-místo";
    private static final GraphFilter ALL_VISIBLE = new GraphFilter(false, List.of(), List.of(GRAPH));
    private static final LocalDateTime UPDATED = LocalDateTime.of(2026, 10, 1, 8, 0);

    @Mock
    private ConceptMetadataRepository concepts;
    @Mock
    private JenaTDB2Repository tdb2;
    @Mock
    private IsmdFusekiSearch fuseki;
    @Mock
    private VisibleGraphResolver visibleGraphs;

    private IsmdConceptSearch search;
    private final AtomicBoolean degraded = new AtomicBoolean(false);

    @BeforeEach
    void setUp() {
        search = new IsmdConceptSearch(concepts, tdb2, fuseki, visibleGraphs, Runnable::run, 1_000L, 1_000L);
    }

    private List<SearchResultDto> run(Boolean publishedFilter, List<RelationType> relations, SearchType type) {
        when(visibleGraphs.graphFilterFor(null, "user", false, publishedFilter)).thenReturn(ALL_VISIBLE);
        return search.searchConcepts(QUERY, "user", false, publishedFilter, "cs", null, relations, degraded, 20, type);
    }

    @Nested
    class Merging {

        @Test
        void conceptFoundByBothSidesIsOneRowWithPgIdentityAndFusekiText() {
            when(concepts.searchByText(QUERY, false, List.of(), false, null)).thenReturn(List.of(entity(1L, A)));
            when(fuseki.searchFuseki(QUERY, List.of(GRAPH), 20, null))
                    .thenReturn(rows(fusekiRow(A, "Adresa (Fuseki)")));

            List<SearchResultDto> result = run(null, null, null);

            assertThat(result).singleElement().satisfies(row -> {
                assertThat(row.getId()).isEqualTo(1L);
                assertThat(row.getLabel()).isEqualTo("Adresa (Fuseki)");
                assertThat(row.getDefinition()).isEqualTo("Definice z Fuseki");
                assertThat(row.getMatchedBy()).isEqualTo(MatchedBy.BOTH);
                assertThat(row.getSource()).isEqualTo(SearchSource.ISMD);
                assertThat(row.getLastModified()).isEqualTo(UPDATED.toString());
            });
            assertThat(degraded).isFalse();
        }

        @Test
        void pgOnlyRowIsEnrichedWithFusekiLabelsAndFusekiRowsAreNot() {
            when(concepts.searchByText(QUERY, false, List.of(), false, null)).thenReturn(List.of(entity(1L, A)));
            when(fuseki.searchFuseki(QUERY, List.of(GRAPH), 20, null)).thenReturn(rows(fusekiRow(B, "Adresní místo")));
            when(concepts.findByConceptIriIn(List.of(B))).thenReturn(List.of());

            List<SearchResultDto> result = run(null, null, null);

            assertThat(result).extracting(SearchResultDto::getIri).containsExactly(A, B);
            assertThat(result.get(0).getMatchedBy()).isEqualTo(MatchedBy.PG);
            verify(fuseki).enrichWithLabels(List.of(result.get(0)), "cs");
        }

        @Test
        void rowsWithoutIriAreDropped() {
            when(concepts.searchByText(QUERY, false, List.of(), false, null)).thenReturn(List.of(entity(1L, null)));
            when(fuseki.searchFuseki(QUERY, List.of(GRAPH), 20, null)).thenReturn(rows(fusekiRow(null, "?")));

            assertThat(run(null, null, null)).isEmpty();
        }

        @Test
        void unpublishedFilterSearchesTheUnpublishedRows() {
            when(visibleGraphs.graphFilterFor(null, "user", false, false)).thenReturn(ALL_VISIBLE);
            when(concepts.searchByTextUnpublished(QUERY, false, List.of(), false, null)).thenReturn(List.of(entity(1L, A)));
            when(fuseki.searchFuseki(QUERY, List.of(GRAPH), 20, null)).thenReturn(rows(fusekiRow(A, "Adresa")));

            assertThat(search.searchConcepts(QUERY, "user", false, false, "cs", null, null, degraded, 20, null))
                    .singleElement().extracting(SearchResultDto::getId).isEqualTo(1L);
            verify(concepts, never()).searchByText(any(), any(Boolean.class), anyList(), any(Boolean.class), any());
        }

        @Test
        void roleFilterIsPassedToBothSides() {
            when(concepts.searchByText(QUERY, false, List.of(), true, "VLASTNOST")).thenReturn(List.of());
            when(fuseki.searchFuseki(QUERY, List.of(GRAPH), 20, ConceptType.VLASTNOST)).thenReturn(List.of());

            assertThat(run(null, null, SearchType.PROPERTY)).isEmpty();
        }
    }

    @Nested
    class Backfill {

        @Test
        void fusekiOnlyRowGetsItsPgIdentityWithoutOverwritingFusekiValues() {
            when(concepts.searchByText(QUERY, false, List.of(), false, null)).thenReturn(List.of());
            SearchResultDto fusekiOnly = fusekiRow(B, "Adresní místo");
            fusekiOnly.setSlug("z-fuseki");
            when(fuseki.searchFuseki(QUERY, List.of(GRAPH), 20, null)).thenReturn(rows(fusekiOnly));
            ConceptMetadataEntity pgRow = entity(9L, B);
            pgRow.setSlug("z-pg");
            when(concepts.findByConceptIriIn(List.of(B))).thenReturn(List.of(pgRow));

            assertThat(run(null, null, null)).singleElement().satisfies(row -> {
                assertThat(row.getId()).isEqualTo(9L);
                assertThat(row.getSlug()).isEqualTo("z-fuseki");
                assertThat(row.getConceptType()).isEqualTo(ConceptType.TRIDA);
                assertThat(row.getOntologyIri()).isEqualTo(GRAPH);
                assertThat(row.getIsPublished()).isTrue();
                assertThat(row.getLastModified()).isEqualTo(UPDATED.toString());
                assertThat(row.getMatchedBy()).isNull();
            });
        }

        @Test
        void failedBackfillLeavesTheIdEmptyAndStillAnswers() {
            when(concepts.searchByText(QUERY, false, List.of(), false, null)).thenReturn(List.of());
            when(fuseki.searchFuseki(QUERY, List.of(GRAPH), 20, null)).thenReturn(rows(fusekiRow(B, "Adresní místo")));
            when(concepts.findByConceptIriIn(List.of(B))).thenThrow(new IllegalStateException("DB down"));

            assertThat(run(null, null, null)).singleElement().satisfies(row -> assertThat(row.getId()).isNull());
        }

        @Test
        void backfillRowWithoutUpdateTimeLeavesLastModifiedEmpty() {
            when(concepts.searchByText(QUERY, false, List.of(), false, null)).thenReturn(List.of());
            when(fuseki.searchFuseki(QUERY, List.of(GRAPH), 20, null)).thenReturn(rows(fusekiRow(B, "Adresní místo")));
            ConceptMetadataEntity pgRow = entity(9L, B);
            pgRow.setUpdatedAt(null);
            when(concepts.findByConceptIriIn(List.of(B))).thenReturn(List.of(pgRow));

            assertThat(run(null, null, null)).singleElement().satisfies(row -> {
                assertThat(row.getId()).isEqualTo(9L);
                assertThat(row.getLastModified()).isNull();
            });
        }
    }

    @Nested
    class Degradation {

        @Test
        void failingPgStillReturnsFusekiRows() {
            when(concepts.searchByText(QUERY, false, List.of(), false, null)).thenThrow(new IllegalStateException("PG down"));
            when(fuseki.searchFuseki(QUERY, List.of(GRAPH), 20, null)).thenReturn(rows(fusekiRow(B, "Adresní místo")));
            when(concepts.findByConceptIriIn(List.of(B))).thenReturn(List.of());

            assertThat(run(null, null, null)).extracting(SearchResultDto::getIri).containsExactly(B);
            assertThat(degraded).isFalse();
        }

        @Test
        void failingFusekiReturnsPgRowsMarksDegradedAndSkipsEnrichment() {
            when(concepts.searchByText(QUERY, false, List.of(), false, null)).thenReturn(List.of(entity(1L, A)));
            when(fuseki.searchFuseki(QUERY, List.of(GRAPH), 20, null)).thenThrow(new IllegalStateException("Fuseki down"));

            assertThat(run(null, null, null)).extracting(SearchResultDto::getIri).containsExactly(A);
            assertThat(degraded).isTrue();
            verify(fuseki, never()).enrichWithLabels(anyList(), any());
        }

        @Test
        void failingEnrichmentKeepsThePgLabel() {
            when(concepts.searchByText(QUERY, false, List.of(), false, null)).thenReturn(List.of(entity(1L, A)));
            when(fuseki.searchFuseki(QUERY, List.of(GRAPH), 20, null)).thenReturn(List.of());
            org.mockito.Mockito.doThrow(new IllegalStateException("Fuseki slow"))
                    .when(fuseki).enrichWithLabels(anyList(), eq("cs"));

            assertThat(run(null, null, null)).singleElement()
                    .extracting(SearchResultDto::getLabel).isEqualTo("Adresa");
        }
    }

    @Nested
    class RelationFilter {

        @Test
        void keepsOnlyConceptsWithTheRequestedRelation() {
            when(concepts.searchByText(QUERY, false, List.of(), false, null))
                    .thenReturn(List.of(entity(1L, A), entity(2L, B)));
            when(fuseki.searchFuseki(QUERY, List.of(GRAPH), 20, null)).thenReturn(rows(fusekiRow(A, "Adresa"), fusekiRow(B, "Místo")));
            when(tdb2.filterByRelationTypes(List.of(A, B), List.of(RelationType.SUBCLASS))).thenReturn(Set.of(B));

            assertThat(run(null, List.of(RelationType.SUBCLASS), null))
                    .extracting(SearchResultDto::getIri).containsExactly(B);
        }

        @Test
        void failingFilterReturnsTheUnfilteredRows() {
            when(concepts.searchByText(QUERY, false, List.of(), false, null)).thenReturn(List.of(entity(1L, A)));
            when(fuseki.searchFuseki(QUERY, List.of(GRAPH), 20, null)).thenReturn(rows(fusekiRow(A, "Adresa")));
            when(tdb2.filterByRelationTypes(anyList(), anyList())).thenThrow(new IllegalStateException("Fuseki down"));

            assertThat(run(null, List.of(RelationType.EXACT_MATCH), null)).hasSize(1);
        }

        @Test
        void noResultsSkipTheFilter() {
            when(concepts.searchByText(QUERY, false, List.of(), false, null)).thenReturn(List.of());
            when(fuseki.searchFuseki(QUERY, List.of(GRAPH), 20, null)).thenReturn(List.of());

            assertThat(run(null, List.of(RelationType.SUBCLASS), null)).isEmpty();
            verifyNoInteractions(tdb2);
        }
    }

    @Nested
    class Count {

        @Test
        void countsPublishedMatchesWithinTheGivenOntologiesAndRole() {
            when(concepts.countSearchByText(QUERY, true, List.of(GRAPH), true, "TRIDA")).thenReturn(7L);

            assertThat(search.countConceptMatches(QUERY, null, List.of(GRAPH), SearchType.CLASS)).isEqualTo(7);
        }

        @Test
        void countsUnpublishedMatchesEverywhere() {
            when(concepts.countSearchByTextUnpublished(QUERY, false, List.of(), false, null)).thenReturn(3L);

            assertThat(search.countConceptMatches(QUERY, false, List.of(), null)).isEqualTo(3);
        }

        @Test
        void failingCountIsUnknown() {
            when(concepts.countSearchByText(QUERY, false, List.of(), false, null)).thenThrow(new IllegalStateException("PG down"));

            assertThat(search.countConceptMatches(QUERY, true, null, SearchType.CONCEPT)).isNull();
        }
    }

    private static ConceptMetadataEntity entity(Long id, String iri) {
        ConceptMetadataEntity e = new ConceptMetadataEntity();
        e.setId(id);
        e.setConceptIri(iri);
        e.setConceptName("Adresa");
        e.setSlug("adresa");
        e.setConceptType(ConceptType.TRIDA);
        e.setGraphName(GRAPH);
        e.setIsPublished(true);
        e.setUpdatedAt(UPDATED);
        return e;
    }

    private static SearchResultDto fusekiRow(String iri, String label) {
        return SearchResultDto.builder()
                .iri(iri)
                .label(label)
                .definition("Definice z Fuseki")
                .type(SearchType.CONCEPT)
                .source(SearchSource.ISMD)
                .build();
    }

    private static List<SearchResultDto> rows(SearchResultDto... rows) {
        return new ArrayList<>(List.of(rows));
    }
}
