package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.*;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IsmdSearchProviderTest {

    @Mock
    private OntologyMetadataRepository ontologyMetadataRepository;

    @Mock
    private ConceptMetadataRepository conceptMetadataRepository;

    @Mock
    private JenaTDB2Repository jenaTDB2Repository;

    @Mock
    private DiagramRepository diagramRepository;

    @Mock
    private DiagramSearchLookup diagramSearchLookup;

    private IsmdSearchProvider createProvider() {
        Executor directExecutor = Runnable::run;
        return new IsmdSearchProvider(
                ontologyMetadataRepository, conceptMetadataRepository,
                jenaTDB2Repository, diagramRepository, diagramSearchLookup,
                directExecutor, 10_000L, 10_000L);
    }

    // --- Phase 3 tests ---

    @Test
    void search_conceptType_returnsMappedResults() {
        ConceptMetadataEntity concept = createConcept(
                "https://example.org/concept/osoba", "osoba", "Osoba",
                ConceptType.TRIDA, "https://example.org/ontology/1", true);

        when(conceptMetadataRepository.searchByText(eq("osoba"), eq(false), anyList(), eq(false), isNull()))
                .thenReturn(List.of(concept));
        stubVisibleGraphs("user1", List.of());
        stubEmptyFusekiSearch();
        stubEmptyFetchConceptLabels();

        SearchProvider.SearchProviderResult result = createProvider().search(
                "osoba", SearchType.CONCEPT, 20, 0, "cs", null, null, "user1", false, null);

        assertEquals(1, result.results().size());
        SearchResultDto dto = result.results().get(0);
        assertEquals("https://example.org/concept/osoba", dto.getIri());
        assertEquals("osoba", dto.getSlug());
        assertEquals("Osoba", dto.getLabel());
        assertEquals(SearchType.CONCEPT, dto.getType());
        assertEquals(SearchSource.ISMD, dto.getSource());
        assertEquals(ConceptType.TRIDA, dto.getConceptType());
        assertEquals("https://example.org/ontology/1", dto.getOntologyIri());
        assertTrue(dto.getIsPublished());
    }

    @Test
    void search_ontologyType_returnsMappedResults() {
        OntologyMetadataEntity ontology = createOntology(
                "https://example.org/ontology/1", "test-ontology", false);

        when(ontologyMetadataRepository.searchByText("test"))
                .thenReturn(List.of(ontology));

        SearchProvider.SearchProviderResult result = createProvider().search(
                "test", SearchType.ONTOLOGY, 20, 0, "cs", null, null, "user1", false, null);

        assertEquals(1, result.results().size());
        SearchResultDto dto = result.results().get(0);
        assertEquals("https://example.org/ontology/1", dto.getIri());
        assertEquals("test-ontology", dto.getSlug());
        assertEquals(SearchType.ONTOLOGY, dto.getType());
        assertEquals(SearchSource.ISMD, dto.getSource());
        assertFalse(dto.getIsPublished());
    }

    @Test
    void search_noTypeFilter_searchesBothOntologiesAndConcepts() {
        OntologyMetadataEntity ontology = createOntology(
                "https://example.org/ontology/1", "osoba-ontology", true);
        ConceptMetadataEntity concept = createConcept(
                "https://example.org/concept/osoba", "osoba", "Osoba",
                ConceptType.TRIDA, "https://example.org/ontology/1", true);

        when(ontologyMetadataRepository.searchByText("osoba"))
                .thenReturn(List.of(ontology));
        when(conceptMetadataRepository.searchByText(eq("osoba"), eq(false), anyList(), eq(false), isNull()))
                .thenReturn(List.of(concept));
        stubVisibleGraphs("user1", List.of());
        stubEmptyFusekiSearch();
        stubEmptyFetchConceptLabels();

        SearchProvider.SearchProviderResult result = createProvider().search(
                "osoba", null, 20, 0, "cs", null, null, "user1", false, null);

        assertEquals(2, result.results().size());
        assertEquals(2, result.totalCount());
    }

    @Test
    void search_withOntologyIriFilter_passesGraphNamesToConceptQuery() {
        List<String> ontologyIris = List.of("https://example.org/ontology/1");

        when(conceptMetadataRepository.searchByText(eq("osoba"), eq(true), eq(ontologyIris), eq(false), isNull()))
                .thenReturn(List.of());
        stubVisibleGraphs("user1", List.of());
        stubEmptyFusekiSearch();
        stubEmptyFetchConceptLabels();

        createProvider().search("osoba", SearchType.CONCEPT, 20, 0, "cs", ontologyIris, null, "user1", false, null);

        verify(conceptMetadataRepository).searchByText("osoba", true, ontologyIris, false, null);
    }

    @Test
    void search_duplicateIris_deduplicates() {
        OntologyMetadataEntity ontology = createOntology(
                "https://example.org/shared-iri", "shared", true);
        ConceptMetadataEntity concept = createConcept(
                "https://example.org/shared-iri", "shared-concept", "Shared",
                ConceptType.TRIDA, "https://example.org/ontology/1", true);

        when(ontologyMetadataRepository.searchByText("shared"))
                .thenReturn(List.of(ontology));
        when(conceptMetadataRepository.searchByText(eq("shared"), eq(false), anyList(), eq(false), isNull()))
                .thenReturn(List.of(concept));
        stubVisibleGraphs("user1", List.of());
        stubEmptyFusekiSearch();
        stubEmptyFetchConceptLabels();

        SearchProvider.SearchProviderResult result = createProvider().search(
                "shared", null, 20, 0, "cs", null, null, "user1", false, null);

        assertEquals(1, result.results().size());
    }

    @Test
    void search_emptyResults_returnsEmpty() {
        when(ontologyMetadataRepository.searchByText("xyz"))
                .thenReturn(List.of());
        when(conceptMetadataRepository.searchByText(eq("xyz"), eq(false), anyList(), eq(false), isNull()))
                .thenReturn(List.of());
        stubVisibleGraphs("user1", List.of());
        stubEmptyFusekiSearch();
        stubEmptyFetchConceptLabels();

        SearchProvider.SearchProviderResult result = createProvider().search(
                "xyz", null, 20, 0, "cs", null, null, "user1", false, null);

        assertEquals(0, result.results().size());
        assertEquals(0, result.totalCount());
    }

    @Test
    void search_respectsPagination() {
        ConceptMetadataEntity concept1 = createConcept("https://example.org/concept/1", "concept-1", "Concept 1", null, null, true);
        ConceptMetadataEntity concept2 = createConcept("https://example.org/concept/2", "concept-2", "Concept 2", null, null, true);
        ConceptMetadataEntity concept3 = createConcept("https://example.org/concept/3", "concept-3", "Concept 3", null, null, true);

        when(conceptMetadataRepository.searchByText(eq("concept"), eq(false), anyList(), eq(false), isNull()))
                .thenReturn(List.of(concept1, concept2, concept3));
        stubVisibleGraphs("user1", List.of());
        stubEmptyFusekiSearch();
        stubEmptyFetchConceptLabels();

        SearchProvider.SearchProviderResult result = createProvider().search(
                "concept", SearchType.CONCEPT, 2, 1, "cs", null, null, "user1", false, null);

        assertEquals(2, result.results().size());
        assertEquals("https://example.org/concept/2", result.results().get(0).getIri());
        assertEquals("https://example.org/concept/3", result.results().get(1).getIri());
        assertEquals(3, result.totalCount());
    }

    // --- Phase 4 tests: Fuseki text search + merge ---

    @Test
    void search_mergesPgAndFusekiResults() {
        // PG returns concept with slug but no description
        ConceptMetadataEntity pgConcept = createConcept(
                "https://example.org/concept/osoba", "osoba", "Osoba",
                ConceptType.TRIDA, "https://example.org/ontology/1", true);

        when(conceptMetadataRepository.searchByText(eq("osoba"), eq(false), anyList(), eq(false), isNull()))
                .thenReturn(List.of(pgConcept));

        // Fuseki returns same concept with description
        Map<String, String> fusekiRow = new HashMap<>();
        fusekiRow.put("resourceIri", "https://example.org/concept/osoba");
        fusekiRow.put("prefLabel", "Osoba");
        fusekiRow.put("prefLabelLang", "cs");
        fusekiRow.put("description", "Fyzická osoba");
        fusekiRow.put("graphName", "https://example.org/ontology/1");
        fusekiRow.put("types", "http://www.w3.org/2004/02/skos/core#Concept");

        OntologyMetadataEntity published = createOntology("https://example.org/ontology/1", "ont1", true);
        when(ontologyMetadataRepository.findAll()).thenReturn(List.of(published));

        when(jenaTDB2Repository.searchByText(eq("osoba"), anyList(), anyInt(), any()))
                .thenReturn(List.of(fusekiRow));
        stubEmptyFetchConceptLabels();

        SearchProvider.SearchProviderResult result = createProvider().search(
                "osoba", SearchType.CONCEPT, 20, 0, "cs", null, null, "user1", false, null);

        assertEquals(1, result.results().size());
        SearchResultDto dto = result.results().get(0);
        assertEquals("https://example.org/concept/osoba", dto.getIri());
        assertEquals("osoba", dto.getSlug()); // from PG
        assertEquals("Fyzická osoba", dto.getDescription()); // merged from Fuseki
        assertEquals(ConceptType.TRIDA, dto.getConceptType()); // from PG
        assertEquals(MatchedBy.BOTH, dto.getMatchedBy()); // found by both PG and Fuseki
        assertEquals(SearchSourceStatus.OK, result.status());
    }

    @Test
    void search_fusekiAddsNewConceptsNotInPg() {
        when(conceptMetadataRepository.searchByText(eq("test"), eq(false), anyList(), eq(false), isNull()))
                .thenReturn(List.of());

        // Fuseki finds a concept that PG missed
        Map<String, String> fusekiRow = new HashMap<>();
        fusekiRow.put("resourceIri", "https://example.org/concept/fuseki-only");
        fusekiRow.put("prefLabel", "Fuseki Only Concept");
        fusekiRow.put("prefLabelLang", "en");
        fusekiRow.put("graphName", "https://example.org/ontology/1");
        fusekiRow.put("types", "http://www.w3.org/2004/02/skos/core#Concept");

        OntologyMetadataEntity published = createOntology("https://example.org/ontology/1", "ont1", true);
        when(ontologyMetadataRepository.findAll()).thenReturn(List.of(published));

        when(jenaTDB2Repository.searchByText(eq("test"), anyList(), anyInt(), any()))
                .thenReturn(List.of(fusekiRow));
        stubEmptyFetchConceptLabels();

        SearchProvider.SearchProviderResult result = createProvider().search(
                "test", SearchType.CONCEPT, 20, 0, "cs", null, null, "user1", false, null);

        assertEquals(1, result.results().size());
        assertEquals("https://example.org/concept/fuseki-only", result.results().get(0).getIri());
        assertEquals("Fuseki Only Concept", result.results().get(0).getLabel());
        assertEquals(MatchedBy.SPARQL, result.results().get(0).getMatchedBy());
    }

    @Test
    void search_fusekiDown_degradesToPgOnly() {
        ConceptMetadataEntity pgConcept = createConcept(
                "https://example.org/concept/osoba", "osoba", "Osoba",
                ConceptType.TRIDA, "https://example.org/ontology/1", true);

        when(conceptMetadataRepository.searchByText(eq("osoba"), eq(false), anyList(), eq(false), isNull()))
                .thenReturn(List.of(pgConcept));

        OntologyMetadataEntity published = createOntology("https://example.org/ontology/1", "ont1", true);
        when(ontologyMetadataRepository.findAll()).thenReturn(List.of(published));

        // Fuseki throws exception
        when(jenaTDB2Repository.searchByText(anyString(), anyList(), anyInt(), any()))
                .thenThrow(new RuntimeException("Connection refused"));

        SearchProvider.SearchProviderResult result = createProvider().search(
                "osoba", SearchType.CONCEPT, 20, 0, "cs", null, null, "user1", false, null);

        assertEquals(1, result.results().size());
        assertEquals("https://example.org/concept/osoba", result.results().get(0).getIri());
        assertEquals(MatchedBy.PG, result.results().get(0).getMatchedBy()); // Fuseki failed, PG only
        assertEquals(SearchSourceStatus.DEGRADED, result.status());
        assertNotNull(result.statusMessage());
    }

    @Test
    void search_visibleGraphsIncludeAllOntologies() {
        OntologyMetadataEntity published = createOntology("https://example.org/ontology/public", "public", true);
        OntologyMetadataEntity userOwned = createOntology("https://example.org/ontology/private", "private", false);

        when(ontologyMetadataRepository.findAll()).thenReturn(List.of(published, userOwned));

        when(conceptMetadataRepository.searchByText(eq("test"), eq(false), anyList(), eq(false), isNull()))
                .thenReturn(List.of());
        when(jenaTDB2Repository.searchByText(eq("test"), anyList(), anyInt(), any()))
                .thenReturn(List.of());
        stubEmptyFetchConceptLabels();

        createProvider().search("test", SearchType.CONCEPT, 20, 0, "cs", null, null, "user1", false, null);

        // Fuseki should be called with all ontology graphs (published + drafts)
        verify(jenaTDB2Repository).searchByText(eq("test"), argThat(graphs ->
                graphs.contains("https://example.org/ontology/public") &&
                graphs.contains("https://example.org/ontology/private") &&
                graphs.size() == 2), anyInt(), any());
    }

    // --- Phase 4 tests: Fuseki enrichment ---

    @Test
    void search_withRelationTypeFilter_filtersResults() {
        ConceptMetadataEntity concept1 = createConcept(
                "https://example.org/concept/1", "c1", "Concept 1",
                ConceptType.TRIDA, "https://example.org/ontology/1", true);
        ConceptMetadataEntity concept2 = createConcept(
                "https://example.org/concept/2", "c2", "Concept 2",
                ConceptType.TRIDA, "https://example.org/ontology/1", true);

        when(conceptMetadataRepository.searchByText(eq("concept"), eq(false), anyList(), eq(false), isNull()))
                .thenReturn(List.of(concept1, concept2));
        stubVisibleGraphs("user1", List.of());
        stubEmptyFusekiSearch();
        stubEmptyFetchConceptLabels();

        // Only concept/1 has SUBCLASS relation
        when(jenaTDB2Repository.filterByRelationTypes(anyList(), eq(List.of(RelationType.SUBCLASS))))
                .thenReturn(Set.of("https://example.org/concept/1"));

        SearchProvider.SearchProviderResult result = createProvider().search(
                "concept", SearchType.CONCEPT, 20, 0, "cs", null,
                List.of(RelationType.SUBCLASS), "user1", false, null);

        assertEquals(1, result.results().size());
        assertEquals("https://example.org/concept/1", result.results().get(0).getIri());
    }

    @Test
    void search_relationFilterFails_returnsUnfilteredResults() {
        ConceptMetadataEntity concept = createConcept(
                "https://example.org/concept/1", "c1", "Concept 1",
                ConceptType.TRIDA, "https://example.org/ontology/1", true);

        when(conceptMetadataRepository.searchByText(eq("concept"), eq(false), anyList(), eq(false), isNull()))
                .thenReturn(List.of(concept));
        stubVisibleGraphs("user1", List.of());
        stubEmptyFusekiSearch();
        stubEmptyFetchConceptLabels();

        when(jenaTDB2Repository.filterByRelationTypes(anyList(), anyList()))
                .thenThrow(new RuntimeException("Fuseki down"));

        SearchProvider.SearchProviderResult result = createProvider().search(
                "concept", SearchType.CONCEPT, 20, 0, "cs", null,
                List.of(RelationType.SUBCLASS), "user1", false, null);

        assertEquals(1, result.results().size());
    }

    @Test
    void search_enrichmentWithLabels() {
        ConceptMetadataEntity pgConcept = createConcept(
                "https://example.org/concept/osoba", "osoba", "Osoba",
                ConceptType.TRIDA, "https://example.org/ontology/1", true);

        when(conceptMetadataRepository.searchByText(eq("osoba"), eq(false), anyList(), eq(false), isNull()))
                .thenReturn(List.of(pgConcept));
        stubVisibleGraphs("user1", List.of());
        stubEmptyFusekiSearch();

        Model labelsModel = ModelFactory.createDefaultModel();
        org.apache.jena.rdf.model.Resource concept = labelsModel.createResource("https://example.org/concept/osoba");
        concept.addProperty(
                labelsModel.createProperty("http://www.w3.org/2004/02/skos/core#prefLabel"),
                labelsModel.createLiteral("Osoba", "cs"));
        concept.addProperty(
                labelsModel.createProperty("http://purl.org/dc/terms/description"),
                labelsModel.createLiteral("Fyzická nebo právnická osoba", "cs"));

        when(jenaTDB2Repository.fetchConceptLabels(anyList())).thenReturn(labelsModel);

        SearchProvider.SearchProviderResult result = createProvider().search(
                "osoba", SearchType.CONCEPT, 20, 0, "cs", null, null, "user1", false, null);

        assertEquals(1, result.results().size());
        SearchResultDto dto = result.results().get(0);
        assertEquals("Osoba", dto.getLabel());
        assertEquals("cs", dto.getLabelLang());
        assertEquals("Fyzická nebo právnická osoba", dto.getDescription());
    }

    @Test
    void search_enrichmentFails_usesExistingLabels() {
        ConceptMetadataEntity pgConcept = createConcept(
                "https://example.org/concept/osoba", "osoba", "Osoba",
                ConceptType.TRIDA, "https://example.org/ontology/1", true);

        when(conceptMetadataRepository.searchByText(eq("osoba"), eq(false), anyList(), eq(false), isNull()))
                .thenReturn(List.of(pgConcept));
        stubVisibleGraphs("user1", List.of());
        stubEmptyFusekiSearch();

        when(jenaTDB2Repository.fetchConceptLabels(anyList()))
                .thenThrow(new RuntimeException("Fuseki down"));

        SearchProvider.SearchProviderResult result = createProvider().search(
                "osoba", SearchType.CONCEPT, 20, 0, "cs", null, null, "user1", false, null);

        assertEquals(1, result.results().size());
        assertEquals("Osoba", result.results().get(0).getLabel());
    }

    // --- Defense-in-depth: anonymous + UNPUBLISHED ---

    @Test
    void search_anonymousWithUnpublishedFilter_returnsEmptyAndHitsNothing() {
        // Service layer should reject this at resolveSource, but the provider must
        // also refuse — a future refactor of resolveSource must not be allowed to
        // leak unpublished content from other users. publishedFilter=FALSE +
        // userId=null + isAdmin=false has no rows the caller can see, so we should
        // bail before any DB or Fuseki call.
        SearchProvider.SearchProviderResult result = createProvider().search(
                "osoba", null, 20, 0, "cs", null, null,
                /* userId */ null, /* isAdmin */ false,
                /* publishedFilter */ Boolean.FALSE);

        assertTrue(result.results().isEmpty(),
                "Anonymous UNPUBLISHED request must return no results");
        assertEquals(0, result.totalCount());
        verifyNoInteractions(conceptMetadataRepository);
        verifyNoInteractions(ontologyMetadataRepository);
        verifyNoInteractions(jenaTDB2Repository);
    }

    // --- type=ONTOLOGY skips PG concept query and relation filter ---

    @Test
    void search_typeOntology_skipsPgConceptQueryAndRelationFilter() {
        // PG concept query can never satisfy an ONTOLOGY-only request — running it
        // is pure waste. Same for the relation-type filter (concept-only concept).
        // Fuseki MUST still run because it's how we find ontologies by their labels
        // (PG searchOntologies matches on slug only).
        OntologyMetadataEntity ontology = createOntology(
                "https://example.org/ontology/1", "test-ontology", false);
        when(ontologyMetadataRepository.searchByText("test"))
                .thenReturn(List.of(ontology));
        // Give the user at least one visible graph so searchOntologyLabelsViaFuseki
        // doesn't short-circuit before reaching the Fuseki call.
        stubVisibleGraphs("user1", List.of(ontology));
        stubEmptyFusekiSearch();

        createProvider().search(
                "test", SearchType.ONTOLOGY, 20, 0, "cs", null,
                List.of(RelationType.SUBCLASS), "user1", false, null);

        verify(conceptMetadataRepository, never()).searchByText(
                anyString(), anyBoolean(), anyList(), anyBoolean(), any());
        verify(conceptMetadataRepository, never()).searchByTextUnpublished(
                anyString(), anyBoolean(), anyList(), anyBoolean(), any());
        verify(jenaTDB2Repository, never()).filterByRelationTypes(anyList(), anyList());
        // Fuseki text search SHOULD still be invoked so labels can match.
        verify(jenaTDB2Repository, atLeastOnce()).searchByText(anyString(), anyList(), anyInt(), any());
    }

    // --- Fuseki ontology hits don't self-reference via ontologyIri ---

    @Test
    void search_ontologyHitFromFuseki_leavesOntologyIriNull() {
        // When a Fuseki text-index row classifies as ONTOLOGY (rdf:type
        // skos:ConceptScheme / owl:Ontology), ontologyIri must NOT be populated
        // with graphName — the resource IRI IS the graph name for ontology nodes,
        // so doing so would (a) create a meaningless self-reference and
        // (b) overwrite a PG ontology hit's null ontologyIri during merge.
        Map<String, String> fusekiOntologyRow = new HashMap<>();
        fusekiOntologyRow.put("resourceIri", "https://example.org/ontology/1");
        fusekiOntologyRow.put("prefLabel", "Test Ontology");
        fusekiOntologyRow.put("prefLabelLang", "cs");
        fusekiOntologyRow.put("graphName", "https://example.org/ontology/1");
        fusekiOntologyRow.put("types",
                "http://www.w3.org/2004/02/skos/core#ConceptScheme|http://www.w3.org/2002/07/owl#Ontology");

        OntologyMetadataEntity visibleOntology = createOntology(
                "https://example.org/ontology/1", "test-ontology", true);
        when(ontologyMetadataRepository.findAll())
                .thenReturn(List.of(visibleOntology));
        when(ontologyMetadataRepository.searchByText(anyString()))
                .thenReturn(List.of());
        when(jenaTDB2Repository.searchByText(anyString(), anyList(), anyInt(), any()))
                .thenReturn(List.of(fusekiOntologyRow));
        stubEmptyFetchConceptLabels();

        SearchProvider.SearchProviderResult result = createProvider().search(
                "Test", SearchType.ONTOLOGY, 20, 0, "cs", null, null, "user1", false, null);

        assertEquals(1, result.results().size());
        SearchResultDto dto = result.results().get(0);
        assertEquals("https://example.org/ontology/1", dto.getIri());
        assertEquals(SearchType.ONTOLOGY, dto.getType());
        assertNull(dto.getOntologyIri(),
                "Ontology Fuseki hits must not carry a self-referential ontologyIri");
    }

    /**
     * The provider passes DIAGRAM rows through from {@link DiagramSearchLookup} untouched. Entity→DTO
     * mapping (and the graphless {@code diagram:<id>} dedup key) now lives in that bean, where it runs
     * inside an open session — it is covered by {@code DiagramSearchLookupIntegrationTest} against a
     * real database, which is the only place a detached-proxy regression can be caught.
     */
    /**
     * A failing diagram lookup must not abort the whole ISMD provider. An uncaught throw here
     * discarded the ontology and concept results already gathered and reported the entire source as
     * ERROR — finding F4's blast radius, which outlived the fix to its lazy-proxy cause.
     */
    @Test
    void diagramLookupFailure_degradesButKeepsOntologyAndConceptResults() {
        OntologyMetadataEntity ontology = createOntology(
                "https://example.org/ontology/1", "osoba-ontology", true);
        when(ontologyMetadataRepository.searchByText("osoba")).thenReturn(List.of(ontology));
        when(conceptMetadataRepository.searchByText(eq("osoba"), eq(false), anyList(), eq(false), isNull()))
                .thenReturn(List.of(createConcept("https://example.org/concept/osoba", "osoba", "Osoba",
                        ConceptType.TRIDA, "https://example.org/ontology/1", true)));
        when(diagramSearchLookup.search("osoba", null))
                .thenThrow(new RuntimeException("simulated diagram-lookup failure"));
        lenient().when(diagramSearchLookup.count("osoba", null))
                .thenThrow(new RuntimeException("simulated diagram-count failure"));
        stubVisibleGraphs("user1", List.of());
        stubEmptyFusekiSearch();
        stubEmptyFetchConceptLabels();

        SearchProvider.SearchProviderResult result = createProvider().search(
                "osoba", null, 20, 0, "cs", null, null, "user1", false, null);

        // The ontology and concept rows survive; only the diagram slice is missing.
        assertEquals(2, result.results().size());
        assertNull(result.totalDiagrams(), "a failed diagram count reports null, not a wrong number");
    }

    @Test
    void diagramResults_passThroughFromLookup() {
        SearchResultDto diagramRow = SearchResultDto.builder()
                .id(42L)
                .iri("diagram:42")
                .slug("bez-grafu")
                .label("bez-grafu")
                .type(SearchType.DIAGRAM)
                .source(SearchSource.ISMD)
                .build();

        when(diagramSearchLookup.search("bez-grafu", null)).thenReturn(List.of(diagramRow));
        lenient().when(diagramSearchLookup.count("bez-grafu", null)).thenReturn(1L);
        stubVisibleGraphs("user1", List.of());
        stubEmptyFusekiSearch();
        stubEmptyFetchConceptLabels();

        SearchProvider.SearchProviderResult result = createProvider().search(
                "bez-grafu", SearchType.DIAGRAM, 20, 0, "cs", null, null, "user1", false, null);

        assertEquals(1, result.results().size());
        SearchResultDto dto = result.results().get(0);
        assertNotNull(dto.getIri(), "a graphless diagram must still carry a dedup key");
        assertEquals("diagram:42", dto.getIri());
        assertEquals(SearchType.DIAGRAM, dto.getType());
    }

    // --- Helpers ---

    private ConceptMetadataEntity createConcept(String iri, String slug, String name,
                                                 ConceptType type, String graphName, boolean published) {
        ConceptMetadataEntity entity = new ConceptMetadataEntity();
        entity.setConceptIri(iri);
        entity.setSlug(slug);
        entity.setConceptName(name);
        entity.setConceptType(type);
        entity.setGraphName(graphName);
        entity.setIsPublished(published);
        return entity;
    }

    private OntologyMetadataEntity createOntology(String graphName, String slug, boolean published) {
        OntologyMetadataEntity entity = new OntologyMetadataEntity();
        entity.setGraphName(graphName);
        entity.setSlug(slug);
        entity.setIsPublished(published);
        return entity;
    }

    private void stubVisibleGraphs(String userId, List<OntologyMetadataEntity> extra) {
        lenient().when(ontologyMetadataRepository.findAll()).thenReturn(new ArrayList<>(extra));
    }

    private void stubEmptyFusekiSearch() {
        lenient().when(jenaTDB2Repository.searchByText(anyString(), anyList(), anyInt(), any())).thenReturn(List.of());
    }

    private void stubEmptyFetchConceptLabels() {
        lenient().when(jenaTDB2Repository.fetchConceptLabels(anyList()))
                .thenReturn(ModelFactory.createDefaultModel());
    }
}
