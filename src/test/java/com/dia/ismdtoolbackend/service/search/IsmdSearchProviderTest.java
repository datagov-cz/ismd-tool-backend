package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.MatchedBy;
import com.dia.ismdtoolbackend.enums.RelationType;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.enums.SearchSourceStatus;
import com.dia.ismdtoolbackend.enums.SearchType;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.OntologyLabelLookup;
import com.dia.ismdtoolbackend.service.search.SearchProvider.SearchProviderResult;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The ISMD search provider over real ontology, concept, diagram and Fuseki search classes; only the
 * repositories and lookups are mocked. Unstubbed list queries return empty lists, which is the
 * "nothing found" baseline; each test stubs only the collaborators its case depends on. With no
 * visible ontologies ({@link #givenVisibleOntologies}) the Fuseki text query is skipped altogether.
 */
@ExtendWith(MockitoExtension.class)
class IsmdSearchProviderTest {

    private static final String USER = "user1";
    private static final String ONTOLOGY_1 = "https://example.org/ontology/1";
    private static final String OSOBA = "https://example.org/concept/osoba";

    @Mock
    private OntologyMetadataRepository ontologyMetadataRepository;

    @Mock
    private ConceptMetadataRepository conceptMetadataRepository;

    @Mock
    private JenaTDB2Repository jenaTDB2Repository;

    @Mock
    private DiagramSearchLookup diagramSearchLookup;

    @Mock
    private OntologyLabelLookup ontologyLabelLookup;

    private IsmdSearchProvider provider;

    @BeforeEach
    void createProvider() {
        Executor directExecutor = Runnable::run;
        IsmdFusekiSearch fusekiSearch = new IsmdFusekiSearch(jenaTDB2Repository);
        VisibleGraphResolver visibleGraphs = new VisibleGraphResolver(ontologyMetadataRepository);
        provider = new IsmdSearchProvider(
                new IsmdOntologySearch(ontologyMetadataRepository, conceptMetadataRepository,
                        fusekiSearch, visibleGraphs, directExecutor, 10_000L),
                new IsmdDiagramSearch(diagramSearchLookup, ontologyLabelLookup),
                new IsmdConceptSearch(conceptMetadataRepository, jenaTDB2Repository,
                        fusekiSearch, visibleGraphs, directExecutor, 10_000L, 10_000L));
    }

    @Nested
    class ConceptSearch {

        @Test
        void mapsPgRowToResult() {
            givenPublishedConceptsMatching("osoba", concept(OSOBA, "osoba", "Osoba", ConceptType.TRIDA, true));
            givenNoStoredLabels();

            SearchProviderResult result = search("osoba").type(SearchType.CONCEPT).run();

            assertThat(result.results()).singleElement().satisfies(dto -> {
                assertThat(dto.getIri()).isEqualTo(OSOBA);
                assertThat(dto.getSlug()).isEqualTo("osoba");
                assertThat(dto.getLabel()).isEqualTo("Osoba");
                assertThat(dto.getType()).isEqualTo(SearchType.CONCEPT);
                assertThat(dto.getSource()).isEqualTo(SearchSource.ISMD);
                assertThat(dto.getConceptType()).isEqualTo(ConceptType.TRIDA);
                assertThat(dto.getOntologyIri()).isEqualTo(ONTOLOGY_1);
                assertThat(dto.getIsPublished()).isTrue();
            });
        }

        @Test
        void ontologyIriFilterPassesGraphNamesToConceptQuery() {
            List<String> ontologyIris = List.of(ONTOLOGY_1);

            search("osoba").type(SearchType.CONCEPT).inOntologies(ontologyIris).run();

            verify(conceptMetadataRepository).searchByText("osoba", true, ontologyIris, false, null);
        }

        @Test
        void relationTypeFilterKeepsOnlyMatchingConcepts() {
            givenPublishedConceptsMatching("concept",
                    concept("https://example.org/concept/1", "c1", "Concept 1", ConceptType.TRIDA, true),
                    concept("https://example.org/concept/2", "c2", "Concept 2", ConceptType.TRIDA, true));
            givenNoStoredLabels();
            when(jenaTDB2Repository.filterByRelationTypes(anyList(), eq(List.of(RelationType.SUBCLASS))))
                    .thenReturn(Set.of("https://example.org/concept/1"));

            SearchProviderResult result = search("concept").type(SearchType.CONCEPT)
                    .withRelations(RelationType.SUBCLASS).run();

            assertThat(result.results()).extracting(SearchResultDto::getIri)
                    .containsExactly("https://example.org/concept/1");
        }

        @Test
        void storedLabelsEnrichTheResult() {
            givenPublishedConceptsMatching("osoba", concept(OSOBA, "osoba", "Osoba", ConceptType.TRIDA, true));
            Model labels = ModelFactory.createDefaultModel();
            labels.createResource(OSOBA)
                    .addProperty(SKOS.prefLabel, labels.createLiteral("Osoba", "cs"))
                    .addProperty(DCTerms.description, labels.createLiteral("Fyzická nebo právnická osoba", "cs"));
            when(jenaTDB2Repository.fetchConceptLabels(anyList())).thenReturn(labels);

            SearchProviderResult result = search("osoba").type(SearchType.CONCEPT).run();

            assertThat(result.results()).singleElement().satisfies(dto -> {
                assertThat(dto.getLabel()).isEqualTo("Osoba");
                assertThat(dto.getLabelLang()).isEqualTo("cs");
                assertThat(dto.getDescription()).isEqualTo("Fyzická nebo právnická osoba");
            });
        }
    }

    @Nested
    class OntologySearch {

        @Test
        void mapsPgRowToResult() {
            when(ontologyMetadataRepository.searchByText("test"))
                    .thenReturn(List.of(ontology(ONTOLOGY_1, "test-ontology", false)));

            SearchProviderResult result = search("test").type(SearchType.ONTOLOGY).run();

            assertThat(result.results()).singleElement().satisfies(dto -> {
                assertThat(dto.getIri()).isEqualTo(ONTOLOGY_1);
                assertThat(dto.getSlug()).isEqualTo("test-ontology");
                assertThat(dto.getType()).isEqualTo(SearchType.ONTOLOGY);
                assertThat(dto.getSource()).isEqualTo(SearchSource.ISMD);
                assertThat(dto.getIsPublished()).isFalse();
            });
        }

        /**
         * The PG concept query and the relation filter cannot satisfy an ONTOLOGY-only request. Fuseki still
         * runs, because PG matches ontologies on slug only and labels are found there.
         */
        @Test
        void skipsPgConceptQueryAndRelationFilter() {
            OntologyMetadataEntity ontology = ontology(ONTOLOGY_1, "test-ontology", false);
            when(ontologyMetadataRepository.searchByText("test")).thenReturn(List.of(ontology));
            givenVisibleOntologies(ontology);
            givenNothingFromFuseki();

            search("test").type(SearchType.ONTOLOGY).withRelations(RelationType.SUBCLASS).run();

            verify(conceptMetadataRepository, never()).searchByText(
                    anyString(), anyBoolean(), anyList(), anyBoolean(), any());
            verify(conceptMetadataRepository, never()).searchByTextUnpublished(
                    anyString(), anyBoolean(), anyList(), anyBoolean(), any());
            verify(jenaTDB2Repository, never()).filterByRelationTypes(anyList(), anyList());
            verify(jenaTDB2Repository, atLeastOnce()).searchByText(anyString(), anyList(), anyInt(), any());
        }

        /** For an ontology node the resource IRI is the graph name, so ontologyIri would point at itself. */
        @Test
        void fusekiOntologyHitLeavesOntologyIriNull() {
            givenVisibleOntologies(ontology(ONTOLOGY_1, "test-ontology", true));
            when(jenaTDB2Repository.searchByText(anyString(), anyList(), anyInt(), any()))
                    .thenReturn(List.of(fusekiRow(ONTOLOGY_1).label("Test Ontology", "cs").graph(ONTOLOGY_1)
                            .types(SKOS.ConceptScheme.getURI(), OWL.Ontology.getURI()).build()));

            SearchProviderResult result = search("Test").type(SearchType.ONTOLOGY).run();

            assertThat(result.results()).singleElement().satisfies(dto -> {
                assertThat(dto.getIri()).isEqualTo(ONTOLOGY_1);
                assertThat(dto.getType()).isEqualTo(SearchType.ONTOLOGY);
                assertThat(dto.getOntologyIri()).as("no self-referential ontologyIri").isNull();
            });
        }

        /** Fuseki ontology rows carry no id, slug or publish state; the ontology-only branch backfills them. */
        @Test
        void ontologyFoundByLabelOnlyBackfillsPgFields() {
            givenVisibleOntologies(ontology(ONTOLOGY_1, "unrelated-slug", false));
            when(jenaTDB2Repository.searchByText(eq("škol"), anyList(), anyInt(), isNull()))
                    .thenReturn(List.of(fusekiRow(ONTOLOGY_1).label("Vysoké školy", null)
                            .types(OWL.Ontology.getURI()).build()));
            when(ontologyMetadataRepository.findAllByGraphNameIn(List.of(ONTOLOGY_1)))
                    .thenReturn(List.of(ontology(ONTOLOGY_1, "unrelated-slug", false)));

            SearchProviderResult result = search("škol").type(SearchType.ONTOLOGY).run();

            assertThat(result.results()).singleElement().satisfies(dto -> {
                assertThat(dto.getSlug()).as("slug backfilled from PG").isEqualTo("unrelated-slug");
                assertThat(dto.getIsPublished()).as("publish state backfilled from PG").isFalse();
            });
        }

        /**
         * Without a type filter (the FE's plain search box) the ontology row still arrives from Fuseki with
         * no slug, and the concept-side backfill cannot help: an ontology has no concept_metadata row.
         */
        @Test
        void unfilteredSearchBackfillsTheSlugOfAnOntologyFoundByLabelOnly() {
            givenVisibleOntologies(ontology(ONTOLOGY_1, "unrelated-slug", false));
            when(jenaTDB2Repository.searchByText(eq("škol"), anyList(), anyInt(), isNull()))
                    .thenReturn(List.of(fusekiRow(ONTOLOGY_1).label("Vysoké školy", null)
                            .types(OWL.Ontology.getURI()).build()));
            when(ontologyMetadataRepository.findAllByGraphNameIn(anyList()))
                    .thenReturn(List.of(ontology(ONTOLOGY_1, "unrelated-slug", false)));

            SearchProviderResult result = search("škol").run();

            assertThat(result.results()).singleElement().satisfies(dto -> {
                assertThat(dto.getType()).isEqualTo(SearchType.ONTOLOGY);
                assertThat(dto.getSlug()).as("backfilled on the unfiltered path too").isEqualTo("unrelated-slug");
            });
        }

        /**
         * "TEST slovnik" has slug "test-slovnik": the query "qa test" matches the label in Fuseki but not the
         * slug in PG, so only the Fuseki row survives and needs its slug backfilled.
         */
        @Test
        void multiWordQueryMatchingTheLabelButNotTheSlugBackfillsTheSlug() {
            String graph = "https://slovník.gov.cz/test-slovnik";
            givenVisibleOntologies(ontology(graph, "test-slovnik", false));
            when(jenaTDB2Repository.searchByText(eq("qa test"), anyList(), anyInt(), isNull()))
                    .thenReturn(List.of(fusekiRow(graph).label("TEST slovnik", "cs")
                            .types(SKOS.ConceptScheme.getURI()).build()));
            when(ontologyMetadataRepository.findAllByGraphNameIn(anyList()))
                    .thenReturn(List.of(ontology(graph, "test-slovnik", false)));

            SearchProviderResult result = search("qa test").run();

            assertThat(result.results()).singleElement().satisfies(dto -> {
                assertThat(dto.getLabel()).isEqualTo("TEST slovnik");
                assertThat(dto.getSlug()).as("without a slug the FE links to /dictionary/null")
                        .isEqualTo("test-slovnik");
            });
        }
    }

    /** No type filter: ontologies and concepts searched together. */
    @Nested
    class MixedSearch {

        @Test
        void searchesBothOntologiesAndConcepts() {
            when(ontologyMetadataRepository.searchByText("osoba"))
                    .thenReturn(List.of(ontology(ONTOLOGY_1, "osoba-ontology", true)));
            givenPublishedConceptsMatching("osoba", concept(OSOBA, "osoba", "Osoba", ConceptType.TRIDA, true));
            givenNoStoredLabels();

            SearchProviderResult result = search("osoba").run();

            assertThat(result.results()).hasSize(2);
            assertThat(result.totalCount()).isEqualTo(2);
        }

        @Test
        void sameIriFromOntologyAndConceptIsReturnedOnce() {
            String shared = "https://example.org/shared-iri";
            when(ontologyMetadataRepository.searchByText("shared"))
                    .thenReturn(List.of(ontology(shared, "shared", true)));
            givenPublishedConceptsMatching("shared",
                    concept(shared, "shared-concept", "Shared", ConceptType.TRIDA, true));
            givenNoStoredLabels();

            assertThat(search("shared").run().results()).hasSize(1);
        }

        @Test
        void nothingFoundReturnsEmpty() {

            SearchProviderResult result = search("xyz").run();

            assertThat(result.results()).isEmpty();
            assertThat(result.totalCount()).isZero();
        }
    }

    /** PG and Fuseki text hits merged into one row per IRI. */
    @Nested
    class FusekiMerge {

        @Test
        void mergesPgAndFusekiHitsForTheSameConcept() {
            givenPublishedConceptsMatching("osoba", concept(OSOBA, "osoba", "Osoba", ConceptType.TRIDA, true));
            givenVisibleOntologies(ontology(ONTOLOGY_1, "ont1", true));
            when(jenaTDB2Repository.searchByText(eq("osoba"), anyList(), anyInt(), any()))
                    .thenReturn(List.of(fusekiRow(OSOBA).label("Osoba", "cs").description("Fyzická osoba")
                            .graph(ONTOLOGY_1).types(SKOS.Concept.getURI()).build()));

            SearchProviderResult result = search("osoba").type(SearchType.CONCEPT).run();

            assertThat(result.results()).singleElement().satisfies(dto -> {
                assertThat(dto.getIri()).isEqualTo(OSOBA);
                assertThat(dto.getSlug()).as("from PG").isEqualTo("osoba");
                assertThat(dto.getDescription()).as("from Fuseki").isEqualTo("Fyzická osoba");
                assertThat(dto.getConceptType()).as("from PG").isEqualTo(ConceptType.TRIDA);
                assertThat(dto.getMatchedBy()).isEqualTo(MatchedBy.BOTH);
            });
            assertThat(result.status()).isEqualTo(SearchSourceStatus.OK);
        }

        @Test
        void fusekiAddsConceptsPgMissed() {
            String fusekiOnly = "https://example.org/concept/fuseki-only";
            givenVisibleOntologies(ontology(ONTOLOGY_1, "ont1", true));
            when(jenaTDB2Repository.searchByText(eq("test"), anyList(), anyInt(), any()))
                    .thenReturn(List.of(fusekiRow(fusekiOnly).label("Fuseki Only Concept", "en")
                            .graph(ONTOLOGY_1).types(SKOS.Concept.getURI()).build()));

            SearchProviderResult result = search("test").type(SearchType.CONCEPT).run();

            assertThat(result.results()).singleElement().satisfies(dto -> {
                assertThat(dto.getIri()).isEqualTo(fusekiOnly);
                assertThat(dto.getLabel()).isEqualTo("Fuseki Only Concept");
                assertThat(dto.getMatchedBy()).isEqualTo(MatchedBy.SPARQL);
            });
        }

        /** The default pass scopes Fuseki to every ontology, published and draft. */
        @Test
        void fusekiScopeIncludesAllOntologies() {
            String publicGraph = "https://example.org/ontology/public";
            String privateGraph = "https://example.org/ontology/private";
            givenVisibleOntologies(ontology(publicGraph, "public", true), ontology(privateGraph, "private", false));

            search("test").type(SearchType.CONCEPT).run();

            verify(jenaTDB2Repository).searchByText(eq("test"),
                    argThat(graphs -> Set.copyOf(graphs).equals(Set.of(publicGraph, privateGraph))
                            && graphs.size() == 2),
                    anyInt(), any());
        }
    }

    /** A failing collaborator degrades its slice and keeps the rest. */
    @Nested
    class Degradation {

        @Test
        void fusekiDownDegradesToPgOnly() {
            givenPublishedConceptsMatching("osoba", concept(OSOBA, "osoba", "Osoba", ConceptType.TRIDA, true));
            givenVisibleOntologies(ontology(ONTOLOGY_1, "ont1", true));
            when(jenaTDB2Repository.searchByText(anyString(), anyList(), anyInt(), any()))
                    .thenThrow(new RuntimeException("Connection refused"));

            SearchProviderResult result = search("osoba").type(SearchType.CONCEPT).run();

            assertThat(result.results()).singleElement().satisfies(dto -> {
                assertThat(dto.getIri()).isEqualTo(OSOBA);
                assertThat(dto.getMatchedBy()).isEqualTo(MatchedBy.PG);
            });
            assertThat(result.status()).isEqualTo(SearchSourceStatus.DEGRADED);
            assertThat(result.statusMessage()).isNotNull();
        }

        /** Every row survives the failed filter; DEGRADED reports that it was not applied. */
        @Test
        void failedRelationFilterReturnsUnfilteredResultsAsDegraded() {
            String iri1 = "https://example.org/concept/1";
            String iri2 = "https://example.org/concept/2";
            givenPublishedConceptsMatching("concept",
                    concept(iri1, "c1", "Concept 1", ConceptType.TRIDA, true),
                    concept(iri2, "c2", "Concept 2", ConceptType.TRIDA, true));
            givenNoStoredLabels();
            when(jenaTDB2Repository.filterByRelationTypes(
                    argThat(iris -> iris != null && Set.copyOf(iris).equals(Set.of(iri1, iri2))),
                    eq(List.of(RelationType.SUBCLASS))))
                    .thenThrow(new RuntimeException("Fuseki down"));

            SearchProviderResult result = search("concept").type(SearchType.CONCEPT)
                    .withRelations(RelationType.SUBCLASS).run();

            assertThat(result.results()).extracting(SearchResultDto::getIri).containsExactlyInAnyOrder(iri1, iri2);
            assertThat(result.status()).isEqualTo(SearchSourceStatus.DEGRADED);
        }

        @Test
        void failedLabelEnrichmentKeepsPgLabels() {
            givenPublishedConceptsMatching("osoba", concept(OSOBA, "osoba", "Osoba", ConceptType.TRIDA, true));
            when(jenaTDB2Repository.fetchConceptLabels(anyList())).thenThrow(new RuntimeException("Fuseki down"));

            SearchProviderResult result = search("osoba").type(SearchType.CONCEPT).run();

            assertThat(result.results()).singleElement()
                    .extracting(SearchResultDto::getLabel).isEqualTo("Osoba");
        }

        /**
         * A failing diagram lookup must not discard the ontology and concept results already gathered, nor
         * report the whole source as ERROR.
         */
        @Test
        void failedDiagramLookupKeepsOntologyAndConceptResults() {
            when(ontologyMetadataRepository.searchByText("osoba"))
                    .thenReturn(List.of(ontology(ONTOLOGY_1, "osoba-ontology", true)));
            givenPublishedConceptsMatching("osoba", concept(OSOBA, "osoba", "Osoba", ConceptType.TRIDA, true));
            when(diagramSearchLookup.search("osoba", null, 20, 0))
                    .thenThrow(new RuntimeException("simulated diagram-lookup failure"));
            when(diagramSearchLookup.count("osoba", null))
                    .thenThrow(new RuntimeException("simulated diagram-count failure"));
            givenNoStoredLabels();

            SearchProviderResult result = search("osoba").run();

            assertThat(result.results()).as("only the diagram slice is missing").hasSize(2);
            assertThat(result.totalDiagrams()).as("a failed diagram count reports null, not a wrong number")
                    .isNull();
        }
    }

    /**
     * Diagram rows come from {@link DiagramSearchLookup} as finished DTOs; their mapping and the graphless
     * {@code diagram:<id>} dedup key are covered by {@code DiagramSearchLookupIntegrationTest}.
     */
    @Nested
    class Diagrams {

        @Test
        void diagramRowsPassThroughFromTheLookup() {
            SearchResultDto diagramRow = SearchResultDto.builder()
                    .id(42L).iri("diagram:42").slug("bez-grafu").label("bez-grafu")
                    .type(SearchType.DIAGRAM).source(SearchSource.ISMD).build();
            when(diagramSearchLookup.search("bez-grafu", null, 20, 0)).thenReturn(List.of(diagramRow));
            when(diagramSearchLookup.count("bez-grafu", null)).thenReturn(1L);

            SearchProviderResult result = search("bez-grafu").type(SearchType.DIAGRAM).run();

            assertThat(result.results()).singleElement().satisfies(dto -> {
                assertThat(dto.getIri()).as("a graphless diagram still carries a dedup key").isEqualTo("diagram:42");
                assertThat(dto.getType()).isEqualTo(SearchType.DIAGRAM);
            });
        }

        /**
         * A {@code type=null} pass fetches {@code offset + limit} diagram rows. The sum saturates instead of
         * wrapping to a negative limit, which Postgres rejects and the provider would swallow as "no diagrams".
         */
        @Test
        void hugeOffsetDoesNotOverflowIntoANegativeDiagramFetch() {

            search("osoba").page(20, Integer.MAX_VALUE).run();

            ArgumentCaptor<Integer> fetch = ArgumentCaptor.forClass(Integer.class);
            verify(diagramSearchLookup).search(eq("osoba"), isNull(), fetch.capture(), eq(0));
            assertThat(fetch.getValue()).isEqualTo(Integer.MAX_VALUE);
        }
    }

    /** Ordering and page slicing; #168 hid drafts by slicing an unordered list. */
    @Nested
    class Pagination {

        @Test
        void respectsLimitAndOffset() {
            givenPublishedConceptsMatching("concept",
                    untypedConcept("https://example.org/concept/1", "concept-1", "Concept 1"),
                    untypedConcept("https://example.org/concept/2", "concept-2", "Concept 2"),
                    untypedConcept("https://example.org/concept/3", "concept-3", "Concept 3"));
            givenNoStoredLabels();

            SearchProviderResult result = search("concept").type(SearchType.CONCEPT).page(2, 1).run();

            assertThat(result.results()).extracting(SearchResultDto::getIri)
                    .containsExactly("https://example.org/concept/2", "https://example.org/concept/3");
            assertThat(result.totalCount()).isEqualTo(3);
        }

        /** The newest row, a draft, sorts ahead of published rows so the page slice cannot drop it. */
        @Test
        void draftOntologySurvivesTheLimitCutoffAgainstManyPublished() {
            List<OntologyMetadataEntity> ontologies = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                ontologies.add(ontology("https://example.org/ontology/published-" + i, "test-published-" + i, true));
            }
            ontologies.add(ontology("https://example.org/ontology/draft", "test-draft", false));
            when(ontologyMetadataRepository.searchByText("test")).thenReturn(ontologies);
            when(ontologyMetadataRepository.countSearchByText("test")).thenReturn(21L);

            SearchProviderResult result = search("test").type(SearchType.ONTOLOGY).page(5, 0).run();

            assertThat(result.results()).hasSize(5);
            assertThat(result.results().get(0).getIri()).isEqualTo("https://example.org/ontology/draft");
            assertThat(result.totalOntologies()).isEqualTo(21);
        }

        @Test
        void ontologiesSortBeforeConcepts() {
            when(ontologyMetadataRepository.searchByText("test"))
                    .thenReturn(List.of(ontology(ONTOLOGY_1, "test-ontology", true)));
            givenPublishedConceptsMatching("test",
                    concept("https://example.org/concept/test", "test-concept", "Test", ConceptType.TRIDA, true));
            givenNoStoredLabels();

            assertThat(search("test").run().results()).extracting(SearchResultDto::getType)
                    .containsExactly(SearchType.ONTOLOGY, SearchType.CONCEPT);
        }

        /** Draft-first outranks ontology-before-concept. */
        @Test
        void draftConceptOutranksPublishedOntology() {
            when(ontologyMetadataRepository.searchByText("test"))
                    .thenReturn(List.of(ontology(ONTOLOGY_1, "test-ontology", true)));
            givenPublishedConceptsMatching("test",
                    concept("https://example.org/concept/draft", "test-draft", "Draft", ConceptType.TRIDA, false));
            givenNoStoredLabels();

            List<SearchResultDto> results = search("test").run().results();

            assertThat(results).hasSize(2);
            assertThat(results.get(0).getIri()).isEqualTo("https://example.org/concept/draft");
            assertThat(results.get(1).getType()).isEqualTo(SearchType.ONTOLOGY);
        }

        /** Walking every page yields each row exactly once. */
        @Test
        void pagingAcrossOffsetsNeitherRepeatsNorSkipsRows() {
            List<OntologyMetadataEntity> ontologies = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                ontologies.add(ontology("https://example.org/ontology/" + i, "test-" + i, i % 2 == 0));
            }
            when(ontologyMetadataRepository.searchByText("test")).thenReturn(ontologies);

            List<String> seen = new ArrayList<>();
            for (int offset = 0; offset < 7; offset += 3) {
                search("test").type(SearchType.ONTOLOGY).page(3, offset).run()
                        .results().forEach(r -> seen.add(r.getIri()));
            }

            assertThat(seen).hasSize(7).doesNotHaveDuplicates();
        }
    }

    /** UNPUBLISHED ("rozpracovaný") means every local graph, working copies included. */
    @Nested
    class UnpublishedScope {

        /** No row is visible to an anonymous UNPUBLISHED caller, so nothing is queried. */
        @Test
        void anonymousUnpublishedRequestReturnsEmptyAndQueriesNothing() {
            SearchProviderResult result = search("osoba").anonymous().unpublishedOnly().run();

            assertThat(result.results()).isEmpty();
            assertThat(result.totalCount()).isZero();
            verifyNoInteractions(conceptMetadataRepository, ontologyMetadataRepository, jenaTDB2Repository);
        }

        /**
         * A working copy of an NKD vocabulary stays {@code is_published=true} until a concept is edited, so
         * the Fuseki scope covers every local graph rather than the unpublished ones.
         */
        @Test
        void unpublishedOntologySearchScopesFusekiToAllLocalGraphs() {
            String workingCopyGraph = "https://slovník.gov.cz/a3791---registr-vysokých-škol";
            when(ontologyMetadataRepository.findAllGraphNames()).thenReturn(List.of(workingCopyGraph));
            when(jenaTDB2Repository.searchByText(eq("škol"), eq(List.of(workingCopyGraph)), anyInt(), isNull()))
                    .thenReturn(List.of(fusekiRow(workingCopyGraph).label("A3791 - Registr Vysokých škol", "cs")
                            .types(SKOS.ConceptScheme.getURI()).build()));
            when(ontologyMetadataRepository.findAllByGraphNameIn(List.of(workingCopyGraph)))
                    .thenReturn(List.of(ontology(workingCopyGraph, "a3791---registr-vysokých-škol", true)));

            SearchProviderResult result = search("škol").type(SearchType.ONTOLOGY).unpublishedOnly().run();

            assertThat(result.results()).as("the working copy is reachable under UNPUBLISHED")
                    .singleElement().satisfies(dto -> {
                        assertThat(dto.getIri()).isEqualTo(workingCopyGraph);
                        assertThat(dto.getLabel()).isEqualTo("A3791 - Registr Vysokých škol");
                    });
            verify(ontologyMetadataRepository, never()).findAllByIsPublished(false);
        }
    }

    // ---- fixtures ----

    private SearchCall search(String query) {
        return new SearchCall(query);
    }

    /** One {@code search(...)} call; defaults to an authenticated non-admin user, page 20/0, no filters. */
    private final class SearchCall {
        private final String query;
        private SearchType type;
        private int limit = 20;
        private int offset = 0;
        private List<String> ontologyIris;
        private List<RelationType> relationTypes;
        private String userId = USER;
        private Boolean publishedFilter;

        private SearchCall(String query) {
            this.query = query;
        }

        SearchCall type(SearchType type) {
            this.type = type;
            return this;
        }

        SearchCall page(int limit, int offset) {
            this.limit = limit;
            this.offset = offset;
            return this;
        }

        SearchCall inOntologies(List<String> ontologyIris) {
            this.ontologyIris = ontologyIris;
            return this;
        }

        SearchCall withRelations(RelationType... relationTypes) {
            this.relationTypes = List.of(relationTypes);
            return this;
        }

        SearchCall anonymous() {
            this.userId = null;
            return this;
        }

        SearchCall unpublishedOnly() {
            this.publishedFilter = Boolean.FALSE;
            return this;
        }

        SearchProviderResult run() {
            return provider.search(query, type, limit, offset, "cs", ontologyIris, relationTypes, userId,
                    false, publishedFilter);
        }
    }

    /** PG's concept text query on the default (unfiltered, published-scope) pass. */
    private void givenPublishedConceptsMatching(String query, ConceptMetadataEntity... concepts) {
        when(conceptMetadataRepository.searchByText(eq(query), eq(false), anyList(), eq(false), isNull()))
                .thenReturn(List.of(concepts));
    }

    /** The ontologies whose graphs scope the Fuseki text query. */
    private void givenVisibleOntologies(OntologyMetadataEntity... ontologies) {
        when(ontologyMetadataRepository.findAll()).thenReturn(List.of(ontologies));
    }

    /** The Fuseki text index answers, with no hits. */
    private void givenNothingFromFuseki() {
        when(jenaTDB2Repository.searchByText(anyString(), anyList(), anyInt(), any())).thenReturn(List.of());
    }

    /** The stored-label lookup answers, with no labels. */
    private void givenNoStoredLabels() {
        when(jenaTDB2Repository.fetchConceptLabels(anyList())).thenReturn(ModelFactory.createDefaultModel());
    }

    /** A concept of {@link #ONTOLOGY_1}. */
    private static ConceptMetadataEntity concept(String iri, String slug, String name, ConceptType type,
                                                 boolean published) {
        ConceptMetadataEntity entity = untypedConcept(iri, slug, name);
        entity.setConceptType(type);
        entity.setGraphName(ONTOLOGY_1);
        entity.setIsPublished(published);
        return entity;
    }

    /** A published concept with no type and no graph. */
    private static ConceptMetadataEntity untypedConcept(String iri, String slug, String name) {
        ConceptMetadataEntity entity = new ConceptMetadataEntity();
        entity.setConceptIri(iri);
        entity.setSlug(slug);
        entity.setConceptName(name);
        entity.setIsPublished(true);
        return entity;
    }

    private static OntologyMetadataEntity ontology(String graphName, String slug, boolean published) {
        OntologyMetadataEntity entity = new OntologyMetadataEntity();
        entity.setGraphName(graphName);
        entity.setSlug(slug);
        entity.setIsPublished(published);
        return entity;
    }

    private static FusekiRow fusekiRow(String resourceIri) {
        return new FusekiRow(resourceIri);
    }

    /** A Fuseki text-search row as {@link JenaTDB2Repository#searchByText} returns it. */
    private static final class FusekiRow {
        private final Map<String, String> row = new HashMap<>();

        private FusekiRow(String resourceIri) {
            row.put("resourceIri", resourceIri);
        }

        FusekiRow label(String label, String lang) {
            row.put("prefLabel", label);
            if (lang != null) {
                row.put("prefLabelLang", lang);
            }
            return this;
        }

        FusekiRow description(String description) {
            row.put("description", description);
            return this;
        }

        FusekiRow graph(String graphName) {
            row.put("graphName", graphName);
            return this;
        }

        FusekiRow types(String... typeIris) {
            row.put("types", Arrays.stream(typeIris).collect(Collectors.joining("|")));
            return this;
        }

        Map<String, String> build() {
            return row;
        }
    }
}
