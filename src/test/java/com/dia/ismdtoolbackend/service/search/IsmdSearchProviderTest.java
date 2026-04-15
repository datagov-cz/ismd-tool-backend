package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.*;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

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

    private IsmdSearchProvider createProvider() {
        return new IsmdSearchProvider(
                ontologyMetadataRepository, conceptMetadataRepository,
                jenaTDB2Repository, 10_000L);
    }

    // --- Phase 3 tests ---

    @Test
    void search_conceptType_returnsMappedResults() {
        ConceptMetadataEntity concept = createConcept(
                "https://example.org/concept/osoba", "osoba", "Osoba",
                ConceptType.TRIDA, "https://example.org/ontology/1", true);

        when(conceptMetadataRepository.searchByText(eq("osoba"), eq("user1"), eq(false), anyList()))
                .thenReturn(List.of(concept));
        stubVisibleGraphs("user1", List.of());
        stubEmptyFusekiSearch();
        stubEmptyFetchConceptLabels();

        SearchProvider.SearchProviderResult result = createProvider().search(
                "osoba", SearchType.CONCEPT, 20, 0, "cs", null, null, "user1");

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

        when(ontologyMetadataRepository.searchByText("test", "user1"))
                .thenReturn(List.of(ontology));

        SearchProvider.SearchProviderResult result = createProvider().search(
                "test", SearchType.ONTOLOGY, 20, 0, "cs", null, null, "user1");

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

        when(ontologyMetadataRepository.searchByText("osoba", "user1"))
                .thenReturn(List.of(ontology));
        when(conceptMetadataRepository.searchByText(eq("osoba"), eq("user1"), eq(false), anyList()))
                .thenReturn(List.of(concept));
        stubVisibleGraphs("user1", List.of());
        stubEmptyFusekiSearch();
        stubEmptyFetchConceptLabels();

        SearchProvider.SearchProviderResult result = createProvider().search(
                "osoba", null, 20, 0, "cs", null, null, "user1");

        assertEquals(2, result.results().size());
        assertEquals(2, result.totalCount());
    }

    @Test
    void search_withOntologyIriFilter_passesGraphNamesToConceptQuery() {
        List<String> ontologyIris = List.of("https://example.org/ontology/1");

        when(conceptMetadataRepository.searchByText(eq("osoba"), eq("user1"), eq(true), eq(ontologyIris)))
                .thenReturn(List.of());
        stubVisibleGraphs("user1", List.of());
        stubEmptyFusekiSearch();
        stubEmptyFetchConceptLabels();

        createProvider().search("osoba", SearchType.CONCEPT, 20, 0, "cs", ontologyIris, null, "user1");

        verify(conceptMetadataRepository).searchByText("osoba", "user1", true, ontologyIris);
    }

    @Test
    void search_duplicateIris_deduplicates() {
        OntologyMetadataEntity ontology = createOntology(
                "https://example.org/shared-iri", "shared", true);
        ConceptMetadataEntity concept = createConcept(
                "https://example.org/shared-iri", "shared-concept", "Shared",
                ConceptType.TRIDA, "https://example.org/ontology/1", true);

        when(ontologyMetadataRepository.searchByText("shared", "user1"))
                .thenReturn(List.of(ontology));
        when(conceptMetadataRepository.searchByText(eq("shared"), eq("user1"), eq(false), anyList()))
                .thenReturn(List.of(concept));
        stubVisibleGraphs("user1", List.of());
        stubEmptyFusekiSearch();
        stubEmptyFetchConceptLabels();

        SearchProvider.SearchProviderResult result = createProvider().search(
                "shared", null, 20, 0, "cs", null, null, "user1");

        assertEquals(1, result.results().size());
    }

    @Test
    void search_emptyResults_returnsEmpty() {
        when(ontologyMetadataRepository.searchByText("xyz", "user1"))
                .thenReturn(List.of());
        when(conceptMetadataRepository.searchByText(eq("xyz"), eq("user1"), eq(false), anyList()))
                .thenReturn(List.of());
        stubVisibleGraphs("user1", List.of());
        stubEmptyFusekiSearch();
        stubEmptyFetchConceptLabels();

        SearchProvider.SearchProviderResult result = createProvider().search(
                "xyz", null, 20, 0, "cs", null, null, "user1");

        assertEquals(0, result.results().size());
        assertEquals(0, result.totalCount());
    }

    @Test
    void search_respectsPagination() {
        ConceptMetadataEntity concept1 = createConcept("https://example.org/concept/1", "concept-1", "Concept 1", null, null, true);
        ConceptMetadataEntity concept2 = createConcept("https://example.org/concept/2", "concept-2", "Concept 2", null, null, true);
        ConceptMetadataEntity concept3 = createConcept("https://example.org/concept/3", "concept-3", "Concept 3", null, null, true);

        when(conceptMetadataRepository.searchByText(eq("concept"), eq("user1"), eq(false), anyList()))
                .thenReturn(List.of(concept1, concept2, concept3));
        stubVisibleGraphs("user1", List.of());
        stubEmptyFusekiSearch();
        stubEmptyFetchConceptLabels();

        SearchProvider.SearchProviderResult result = createProvider().search(
                "concept", SearchType.CONCEPT, 2, 1, "cs", null, null, "user1");

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

        when(conceptMetadataRepository.searchByText(eq("osoba"), eq("user1"), eq(false), anyList()))
                .thenReturn(List.of(pgConcept));

        // Fuseki returns same concept with description
        Map<String, String> fusekiRow = new HashMap<>();
        fusekiRow.put("conceptIri", "https://example.org/concept/osoba");
        fusekiRow.put("prefLabel", "Osoba");
        fusekiRow.put("prefLabelLang", "cs");
        fusekiRow.put("description", "Fyzická osoba");
        fusekiRow.put("graphName", "https://example.org/ontology/1");

        OntologyMetadataEntity published = createOntology("https://example.org/ontology/1", "ont1", true);
        when(ontologyMetadataRepository.findAllByIsPublished(true)).thenReturn(List.of(published));
        when(ontologyMetadataRepository.findAllByUserIdAndIsPublished("user1", false)).thenReturn(List.of());

        when(jenaTDB2Repository.searchByText(eq("osoba"), anyList()))
                .thenReturn(List.of(fusekiRow));
        stubEmptyFetchConceptLabels();

        SearchProvider.SearchProviderResult result = createProvider().search(
                "osoba", SearchType.CONCEPT, 20, 0, "cs", null, null, "user1");

        assertEquals(1, result.results().size());
        SearchResultDto dto = result.results().get(0);
        assertEquals("https://example.org/concept/osoba", dto.getIri());
        assertEquals("osoba", dto.getSlug()); // from PG
        assertEquals("Fyzická osoba", dto.getDescription()); // merged from Fuseki
        assertEquals(ConceptType.TRIDA, dto.getConceptType()); // from PG
        assertEquals(SearchSourceStatus.OK, result.status());
    }

    @Test
    void search_fusekiAddsNewConceptsNotInPg() {
        when(conceptMetadataRepository.searchByText(eq("test"), eq("user1"), eq(false), anyList()))
                .thenReturn(List.of());

        // Fuseki finds a concept that PG missed
        Map<String, String> fusekiRow = new HashMap<>();
        fusekiRow.put("conceptIri", "https://example.org/concept/fuseki-only");
        fusekiRow.put("prefLabel", "Fuseki Only Concept");
        fusekiRow.put("prefLabelLang", "en");
        fusekiRow.put("graphName", "https://example.org/ontology/1");

        OntologyMetadataEntity published = createOntology("https://example.org/ontology/1", "ont1", true);
        when(ontologyMetadataRepository.findAllByIsPublished(true)).thenReturn(List.of(published));
        when(ontologyMetadataRepository.findAllByUserIdAndIsPublished("user1", false)).thenReturn(List.of());

        when(jenaTDB2Repository.searchByText(eq("test"), anyList()))
                .thenReturn(List.of(fusekiRow));
        stubEmptyFetchConceptLabels();

        SearchProvider.SearchProviderResult result = createProvider().search(
                "test", SearchType.CONCEPT, 20, 0, "cs", null, null, "user1");

        assertEquals(1, result.results().size());
        assertEquals("https://example.org/concept/fuseki-only", result.results().get(0).getIri());
        assertEquals("Fuseki Only Concept", result.results().get(0).getLabel());
    }

    @Test
    void search_fusekiDown_degradesToPgOnly() {
        ConceptMetadataEntity pgConcept = createConcept(
                "https://example.org/concept/osoba", "osoba", "Osoba",
                ConceptType.TRIDA, "https://example.org/ontology/1", true);

        when(conceptMetadataRepository.searchByText(eq("osoba"), eq("user1"), eq(false), anyList()))
                .thenReturn(List.of(pgConcept));

        OntologyMetadataEntity published = createOntology("https://example.org/ontology/1", "ont1", true);
        when(ontologyMetadataRepository.findAllByIsPublished(true)).thenReturn(List.of(published));
        when(ontologyMetadataRepository.findAllByUserIdAndIsPublished("user1", false)).thenReturn(List.of());

        // Fuseki throws exception
        when(jenaTDB2Repository.searchByText(anyString(), anyList()))
                .thenThrow(new RuntimeException("Connection refused"));

        SearchProvider.SearchProviderResult result = createProvider().search(
                "osoba", SearchType.CONCEPT, 20, 0, "cs", null, null, "user1");

        assertEquals(1, result.results().size());
        assertEquals("https://example.org/concept/osoba", result.results().get(0).getIri());
        assertEquals(SearchSourceStatus.DEGRADED, result.status());
        assertNotNull(result.statusMessage());
    }

    @Test
    void search_visibleGraphsIncludeUserAndPublished() {
        OntologyMetadataEntity published = createOntology("https://example.org/ontology/public", "public", true);
        OntologyMetadataEntity userOwned = createOntology("https://example.org/ontology/private", "private", false);

        when(ontologyMetadataRepository.findAllByIsPublished(true)).thenReturn(List.of(published));
        when(ontologyMetadataRepository.findAllByUserIdAndIsPublished("user1", false)).thenReturn(List.of(userOwned));

        when(conceptMetadataRepository.searchByText(eq("test"), eq("user1"), eq(false), anyList()))
                .thenReturn(List.of());
        when(jenaTDB2Repository.searchByText(eq("test"), anyList()))
                .thenReturn(List.of());
        stubEmptyFetchConceptLabels();

        createProvider().search("test", SearchType.CONCEPT, 20, 0, "cs", null, null, "user1");

        // Fuseki should be called with both published and user-owned graphs
        verify(jenaTDB2Repository).searchByText(eq("test"), argThat(graphs ->
                graphs.contains("https://example.org/ontology/public") &&
                graphs.contains("https://example.org/ontology/private") &&
                graphs.size() == 2));
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

        when(conceptMetadataRepository.searchByText(eq("concept"), eq("user1"), eq(false), anyList()))
                .thenReturn(List.of(concept1, concept2));
        stubVisibleGraphs("user1", List.of());
        stubEmptyFusekiSearch();
        stubEmptyFetchConceptLabels();

        // Only concept/1 has SUBCLASS relation
        when(jenaTDB2Repository.filterByRelationTypes(anyList(), eq(List.of(RelationType.SUBCLASS))))
                .thenReturn(Set.of("https://example.org/concept/1"));

        SearchProvider.SearchProviderResult result = createProvider().search(
                "concept", SearchType.CONCEPT, 20, 0, "cs", null,
                List.of(RelationType.SUBCLASS), "user1");

        assertEquals(1, result.results().size());
        assertEquals("https://example.org/concept/1", result.results().get(0).getIri());
    }

    @Test
    void search_relationFilterFails_returnsUnfilteredResults() {
        ConceptMetadataEntity concept = createConcept(
                "https://example.org/concept/1", "c1", "Concept 1",
                ConceptType.TRIDA, "https://example.org/ontology/1", true);

        when(conceptMetadataRepository.searchByText(eq("concept"), eq("user1"), eq(false), anyList()))
                .thenReturn(List.of(concept));
        stubVisibleGraphs("user1", List.of());
        stubEmptyFusekiSearch();
        stubEmptyFetchConceptLabels();

        when(jenaTDB2Repository.filterByRelationTypes(anyList(), anyList()))
                .thenThrow(new RuntimeException("Fuseki down"));

        SearchProvider.SearchProviderResult result = createProvider().search(
                "concept", SearchType.CONCEPT, 20, 0, "cs", null,
                List.of(RelationType.SUBCLASS), "user1");

        assertEquals(1, result.results().size());
    }

    @Test
    void search_enrichmentWithLabels() {
        ConceptMetadataEntity pgConcept = createConcept(
                "https://example.org/concept/osoba", "osoba", "Osoba",
                ConceptType.TRIDA, "https://example.org/ontology/1", true);

        when(conceptMetadataRepository.searchByText(eq("osoba"), eq("user1"), eq(false), anyList()))
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
                "osoba", SearchType.CONCEPT, 20, 0, "cs", null, null, "user1");

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

        when(conceptMetadataRepository.searchByText(eq("osoba"), eq("user1"), eq(false), anyList()))
                .thenReturn(List.of(pgConcept));
        stubVisibleGraphs("user1", List.of());
        stubEmptyFusekiSearch();

        when(jenaTDB2Repository.fetchConceptLabels(anyList()))
                .thenThrow(new RuntimeException("Fuseki down"));

        SearchProvider.SearchProviderResult result = createProvider().search(
                "osoba", SearchType.CONCEPT, 20, 0, "cs", null, null, "user1");

        assertEquals(1, result.results().size());
        assertEquals("Osoba", result.results().get(0).getLabel());
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
        lenient().when(ontologyMetadataRepository.findAllByIsPublished(true)).thenReturn(List.of());
        lenient().when(ontologyMetadataRepository.findAllByUserIdAndIsPublished(userId, false)).thenReturn(extra);
    }

    private void stubEmptyFusekiSearch() {
        lenient().when(jenaTDB2Repository.searchByText(anyString(), anyList())).thenReturn(List.of());
    }

    private void stubEmptyFetchConceptLabels() {
        lenient().when(jenaTDB2Repository.fetchConceptLabels(anyList()))
                .thenReturn(ModelFactory.createDefaultModel());
    }
}
