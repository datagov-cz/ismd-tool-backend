package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.enums.SearchType;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IsmdOntologySearchTest {

    private static final String QUERY = "adresy";
    private static final String GRAPH = "https://slovník.gov.cz/agendový/104";
    private static final String OTHER = "https://slovník.gov.cz/agendový/105";
    private static final LocalDateTime UPDATED = LocalDateTime.of(2026, 10, 1, 8, 0);

    @Mock
    private OntologyMetadataRepository ontologies;
    @Mock
    private ConceptMetadataRepository concepts;
    @Mock
    private IsmdFusekiSearch fuseki;
    @Mock
    private VisibleGraphResolver visibleGraphs;

    private IsmdOntologySearch search;

    @BeforeEach
    void setUp() {
        search = new IsmdOntologySearch(ontologies, concepts, fuseki, visibleGraphs, Runnable::run, 1_000L);
    }

    @Nested
    class SlugSearch {

        @Test
        void publishedAndAllSearchesMapPgRowsToOntologyResults() {
            when(ontologies.searchByText(QUERY)).thenReturn(List.of(ontology(1L, GRAPH, "adresy")));

            assertThat(search.searchOntologies(QUERY, null)).singleElement().satisfies(row -> {
                assertThat(row.getId()).isEqualTo(1L);
                assertThat(row.getIri()).isEqualTo(GRAPH);
                assertThat(row.getSlug()).isEqualTo("adresy");
                assertThat(row.getLabel()).isEqualTo("adresy");
                assertThat(row.getType()).isEqualTo(SearchType.ONTOLOGY);
                assertThat(row.getSource()).isEqualTo(SearchSource.ISMD);
                assertThat(row.getIsPublished()).isTrue();
                assertThat(row.getLastModified()).isEqualTo(UPDATED.toString());
            });
        }

        @Test
        void unpublishedSearchUsesTheUnpublishedQuery() {
            OntologyMetadataEntity draft = ontology(2L, GRAPH, "rozpracovany");
            draft.setUpdatedAt(null);
            when(ontologies.searchByTextUnpublished(QUERY)).thenReturn(List.of(draft));

            assertThat(search.searchOntologies(QUERY, false)).singleElement()
                    .satisfies(row -> assertThat(row.getLastModified()).isNull());
        }
    }

    @Nested
    class Counting {

        @Test
        void countsPublishedOrUnpublishedMatches() {
            when(ontologies.countSearchByText(QUERY)).thenReturn(4L);
            when(ontologies.countSearchByTextUnpublished(QUERY)).thenReturn(2L);

            assertThat(search.countOntologyMatches(QUERY, true)).isEqualTo(4);
            assertThat(search.countOntologyMatches(QUERY, false)).isEqualTo(2);
        }

        @Test
        void failingCountIsUnknown() {
            when(ontologies.countSearchByText(QUERY)).thenThrow(new IllegalStateException("PG down"));

            assertThat(search.countOntologyMatches(QUERY, null)).isNull();
        }
    }

    @Nested
    class FusekiLabels {

        private final AtomicBoolean degraded = new AtomicBoolean(false);

        @Test
        void searchesTheVisibleGraphsWithoutARoleFilter() {
            when(visibleGraphs.graphFilterFor(List.of(GRAPH), "user", false, null))
                    .thenReturn(new GraphFilter(true, List.of(GRAPH), List.of(GRAPH)));
            List<SearchResultDto> hits = List.of(row(GRAPH, SearchType.ONTOLOGY));
            when(fuseki.searchFuseki(QUERY, List.of(GRAPH), 20, null)).thenReturn(hits);

            assertThat(search.searchOntologyLabelsViaFuseki(QUERY, "user", false, null, List.of(GRAPH), degraded, 20))
                    .isEqualTo(hits);
            assertThat(degraded).isFalse();
        }

        @Test
        void failingFusekiIsEmptyAndDegraded() {
            when(visibleGraphs.graphFilterFor(null, "user", false, null))
                    .thenReturn(new GraphFilter(false, List.of(), List.of(GRAPH)));
            when(fuseki.searchFuseki(QUERY, List.of(GRAPH), 20, null)).thenThrow(new IllegalStateException("down"));

            assertThat(search.searchOntologyLabelsViaFuseki(QUERY, "user", false, null, null, degraded, 20)).isEmpty();
            assertThat(degraded).isTrue();
        }
    }

    @Nested
    class Backfill {

        @Test
        void ontologyFoundByLabelGetsItsPgSlugIdAndPublishState() {
            SearchResultDto byLabel = row(GRAPH, SearchType.ONTOLOGY);
            when(ontologies.findAllByGraphNameIn(List.of(GRAPH))).thenReturn(List.of(ontology(1L, GRAPH, "adresy")));

            search.backfillPgFieldsForOntologyRows(List.of(byLabel));

            assertThat(byLabel.getId()).isEqualTo(1L);
            assertThat(byLabel.getSlug()).isEqualTo("adresy");
            assertThat(byLabel.getIsPublished()).isTrue();
            assertThat(byLabel.getLastModified()).isEqualTo(UPDATED.toString());
        }

        @Test
        void rowsThatAreNotOntologiesOrAlreadyHaveAnIdAreLeftAlone() {
            SearchResultDto concept = row(GRAPH + "/pojem/a", SearchType.CONCEPT);
            SearchResultDto merged = row(GRAPH, SearchType.ONTOLOGY);
            merged.setId(5L);

            search.backfillPgFieldsForOntologyRows(List.of(concept, merged));

            verifyNoInteractions(ontologies);
            assertThat(concept.getId()).isNull();
        }

        @Test
        void fusekiValuesWinOverPgOnes() {
            SearchResultDto byLabel = row(GRAPH, SearchType.ONTOLOGY);
            byLabel.setSlug("z-fuseki");
            byLabel.setIsPublished(false);
            byLabel.setLastModified("2026-01-01T00:00");
            when(ontologies.findAllByGraphNameIn(List.of(GRAPH))).thenReturn(List.of(ontology(1L, GRAPH, "z-pg")));

            search.backfillPgFieldsForOntologyRows(List.of(byLabel));

            assertThat(byLabel.getId()).isEqualTo(1L);
            assertThat(byLabel.getSlug()).isEqualTo("z-fuseki");
            assertThat(byLabel.getIsPublished()).isFalse();
            assertThat(byLabel.getLastModified()).isEqualTo("2026-01-01T00:00");
        }

        @Test
        void unknownGraphAndFailedLookupLeaveTheRowUnchanged() {
            SearchResultDto unknown = row(OTHER, SearchType.ONTOLOGY);
            when(ontologies.findAllByGraphNameIn(List.of(OTHER))).thenReturn(List.of(), List.of(ontology(9L, null, "x")));

            search.backfillPgFieldsForOntologyRows(List.of(unknown));
            search.backfillPgFieldsForOntologyRows(List.of(unknown));
            assertThat(unknown.getId()).isNull();

            when(ontologies.findAllByGraphNameIn(List.of(OTHER))).thenThrow(new IllegalStateException("PG down"));
            search.backfillPgFieldsForOntologyRows(List.of(unknown));
            assertThat(unknown.getSlug()).isNull();
        }
    }

    @Nested
    class ConceptCounts {

        @Test
        void everyOntologyRowGetsItsCountAndZeroWhenItHasNone() {
            SearchResultDto counted = row(GRAPH, SearchType.ONTOLOGY);
            SearchResultDto empty = row(OTHER, SearchType.ONTOLOGY);
            SearchResultDto concept = row(GRAPH + "/pojem/a", SearchType.CONCEPT);
            when(concepts.countByGraphNameIn(List.of(GRAPH, OTHER))).thenReturn(List.of(
                    new Object[]{GRAPH, 12L}, new Object[]{null, 3L}, new Object[]{OTHER}));

            search.populateOntologyConceptCounts(List.of(counted, empty, concept));

            assertThat(counted.getConceptCount()).isEqualTo(12);
            assertThat(empty.getConceptCount()).isZero();
            assertThat(concept.getConceptCount()).isNull();
        }

        @Test
        void pageWithoutOntologiesDoesNotQuery() {
            search.populateOntologyConceptCounts(List.of(row(GRAPH + "/pojem/a", SearchType.CONCEPT)));

            verifyNoInteractions(concepts);
        }

        @Test
        void failedCountLeavesCountsUnknown() {
            SearchResultDto counted = row(GRAPH, SearchType.ONTOLOGY);
            when(concepts.countByGraphNameIn(List.of(GRAPH))).thenThrow(new IllegalStateException("PG down"));

            search.populateOntologyConceptCounts(List.of(counted));

            assertThat(counted.getConceptCount()).isNull();
        }
    }

    private static OntologyMetadataEntity ontology(Long id, String graph, String slug) {
        OntologyMetadataEntity e = new OntologyMetadataEntity();
        e.setId(id);
        e.setGraphName(graph);
        e.setSlug(slug);
        e.setIsPublished(true);
        e.setUpdatedAt(UPDATED);
        return e;
    }

    private static SearchResultDto row(String iri, SearchType type) {
        return SearchResultDto.builder().iri(iri).type(type).source(SearchSource.ISMD).build();
    }
}
