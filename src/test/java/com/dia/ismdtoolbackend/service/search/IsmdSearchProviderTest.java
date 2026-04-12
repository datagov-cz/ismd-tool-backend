package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.enums.SearchType;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IsmdSearchProviderTest {

    @Mock
    private OntologyMetadataRepository ontologyMetadataRepository;

    @Mock
    private ConceptMetadataRepository conceptMetadataRepository;

    @InjectMocks
    private IsmdSearchProvider ismdSearchProvider;

    @Test
    void search_conceptType_returnsMappedResults() {
        ConceptMetadataEntity concept = new ConceptMetadataEntity();
        concept.setConceptIri("https://example.org/concept/osoba");
        concept.setSlug("osoba");
        concept.setConceptName("Osoba");
        concept.setConceptType(ConceptType.TRIDA);
        concept.setGraphName("https://example.org/ontology/1");
        concept.setIsPublished(true);

        when(conceptMetadataRepository.searchByText(eq("osoba"), eq("user1"), eq(false), anyList()))
                .thenReturn(List.of(concept));

        SearchProvider.SearchProviderResult result = ismdSearchProvider.search(
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
        OntologyMetadataEntity ontology = new OntologyMetadataEntity();
        ontology.setGraphName("https://example.org/ontology/1");
        ontology.setSlug("test-ontology");
        ontology.setIsPublished(false);

        when(ontologyMetadataRepository.searchByText("test", "user1"))
                .thenReturn(List.of(ontology));

        SearchProvider.SearchProviderResult result = ismdSearchProvider.search(
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
        OntologyMetadataEntity ontology = new OntologyMetadataEntity();
        ontology.setGraphName("https://example.org/ontology/1");
        ontology.setSlug("osoba-ontology");
        ontology.setIsPublished(true);

        ConceptMetadataEntity concept = new ConceptMetadataEntity();
        concept.setConceptIri("https://example.org/concept/osoba");
        concept.setSlug("osoba");
        concept.setConceptName("Osoba");
        concept.setConceptType(ConceptType.TRIDA);
        concept.setGraphName("https://example.org/ontology/1");
        concept.setIsPublished(true);

        when(ontologyMetadataRepository.searchByText("osoba", "user1"))
                .thenReturn(List.of(ontology));
        when(conceptMetadataRepository.searchByText(eq("osoba"), eq("user1"), eq(false), anyList()))
                .thenReturn(List.of(concept));

        SearchProvider.SearchProviderResult result = ismdSearchProvider.search(
                "osoba", null, 20, 0, "cs", null, null, "user1");

        assertEquals(2, result.results().size());
        assertEquals(2, result.totalCount());
    }

    @Test
    void search_withOntologyIriFilter_passesGraphNamesToConceptQuery() {
        List<String> ontologyIris = List.of("https://example.org/ontology/1");

        when(conceptMetadataRepository.searchByText(eq("osoba"), eq("user1"), eq(true), eq(ontologyIris)))
                .thenReturn(List.of());

        ismdSearchProvider.search("osoba", SearchType.CONCEPT, 20, 0, "cs", ontologyIris, null, "user1");

        verify(conceptMetadataRepository).searchByText("osoba", "user1", true, ontologyIris);
    }

    @Test
    void search_duplicateIris_deduplicates() {
        OntologyMetadataEntity ontology = new OntologyMetadataEntity();
        ontology.setGraphName("https://example.org/shared-iri");
        ontology.setSlug("shared");
        ontology.setIsPublished(true);

        ConceptMetadataEntity concept = new ConceptMetadataEntity();
        concept.setConceptIri("https://example.org/shared-iri");
        concept.setSlug("shared-concept");
        concept.setConceptName("Shared");
        concept.setConceptType(ConceptType.TRIDA);
        concept.setGraphName("https://example.org/ontology/1");
        concept.setIsPublished(true);

        when(ontologyMetadataRepository.searchByText("shared", "user1"))
                .thenReturn(List.of(ontology));
        when(conceptMetadataRepository.searchByText(eq("shared"), eq("user1"), eq(false), anyList()))
                .thenReturn(List.of(concept));

        SearchProvider.SearchProviderResult result = ismdSearchProvider.search(
                "shared", null, 20, 0, "cs", null, null, "user1");

        assertEquals(1, result.results().size());
    }

    @Test
    void search_emptyResults_returnsEmpty() {
        when(ontologyMetadataRepository.searchByText("xyz", "user1"))
                .thenReturn(List.of());
        when(conceptMetadataRepository.searchByText(eq("xyz"), eq("user1"), eq(false), anyList()))
                .thenReturn(List.of());

        SearchProvider.SearchProviderResult result = ismdSearchProvider.search(
                "xyz", null, 20, 0, "cs", null, null, "user1");

        assertEquals(0, result.results().size());
        assertEquals(0, result.totalCount());
    }

    @Test
    void search_respectsPagination() {
        ConceptMetadataEntity concept1 = new ConceptMetadataEntity();
        concept1.setConceptIri("https://example.org/concept/1");
        concept1.setSlug("concept-1");
        concept1.setConceptName("Concept 1");
        concept1.setIsPublished(true);

        ConceptMetadataEntity concept2 = new ConceptMetadataEntity();
        concept2.setConceptIri("https://example.org/concept/2");
        concept2.setSlug("concept-2");
        concept2.setConceptName("Concept 2");
        concept2.setIsPublished(true);

        ConceptMetadataEntity concept3 = new ConceptMetadataEntity();
        concept3.setConceptIri("https://example.org/concept/3");
        concept3.setSlug("concept-3");
        concept3.setConceptName("Concept 3");
        concept3.setIsPublished(true);

        when(conceptMetadataRepository.searchByText(eq("concept"), eq("user1"), eq(false), anyList()))
                .thenReturn(List.of(concept1, concept2, concept3));

        SearchProvider.SearchProviderResult result = ismdSearchProvider.search(
                "concept", SearchType.CONCEPT, 2, 1, "cs", null, null, "user1");

        assertEquals(2, result.results().size());
        assertEquals("https://example.org/concept/2", result.results().get(0).getIri());
        assertEquals("https://example.org/concept/3", result.results().get(1).getIri());
        assertEquals(3, result.totalCount());
    }
}
