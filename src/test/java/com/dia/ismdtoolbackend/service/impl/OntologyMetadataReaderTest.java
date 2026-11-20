package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.utility.paging.PageParams;
import com.dia.ismdtoolbackend.models.OntologyListQuery;
import com.dia.ismdtoolbackend.controller.dto.PageDto;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.controller.dto.OntologySummaryDto;
import com.dia.ismdtoolbackend.models.OntologyRef;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.GRAPH;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.ONTOLOGY_ID;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.SLUG;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.USER_ID;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.ontologyEntity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Vocabulary metadata reads: single rows and the paged, filtered list. */
@ExtendWith(MockitoExtension.class)
class OntologyMetadataReaderTest {

    private OntologyMetadataRepository ontologyMetadataRepository;
    private JenaTDB2Repository jenaTDB2Repository;

    private OntologyMetadataReader reader;

    @BeforeEach
    void setUp() {
        OntologyServiceFixture fixture = new OntologyServiceFixture();
        ontologyMetadataRepository = fixture.ontologyMetadataRepository();
        jenaTDB2Repository = fixture.jenaTDB2Repository();
        reader = fixture.metadataReader();
    }

    @Nested
    class SingleRow {

        @Test
        void bySlug_mapsTheRowToItsRef() {
            when(ontologyMetadataRepository.findBySlug(SLUG)).thenReturn(Optional.of(ontologyEntity()));

            OntologyRef result = reader.getOntologyMetadataBySlug(SLUG);

            assertThat(result).isEqualTo(new OntologyRef(ONTOLOGY_ID, SLUG, GRAPH, false));
        }

        @Test
        void bySlug_unknown_throwsNotFound() {
            when(ontologyMetadataRepository.findBySlug(SLUG)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> reader.getOntologyMetadataBySlug(SLUG))
                    .isInstanceOf(OntologyNotFoundException.class)
                    .hasMessageContaining(SLUG);
        }

        @Test
        void byId_mapsTheRowWithoutReadingRdf() {
            when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.of(ontologyEntity()));

            assertThat(reader.getOntologyMetadata(ONTOLOGY_ID).slug()).isEqualTo(SLUG);
            verifyNoInteractions(jenaTDB2Repository);
        }

        @Test
        void byId_unknown_throws() {
            when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> reader.getOntologyMetadata(ONTOLOGY_ID))
                    .isInstanceOf(NoSuchElementException.class);
        }
    }

    @Nested
    class ListPage {

        private static final PageParams FIRST_PAGE = new PageParams(20, 0);

        private OntologyListQuery query(String userId, Boolean isPublished, List<String> slugs, String q) {
            return new OntologyListQuery(userId, isPublished, slugs, null, q, null);
        }

        @Test
        void withoutSlugs_passesTheScalarFiltersAndAPlaceholderSlug() {
            when(ontologyMetadataRepository.findForList(USER_ID, true, null, true, List.of(""))).thenReturn(List.of());

            PageDto<OntologySummaryDto> page = reader.list(query(USER_ID, true, null, null), FIRST_PAGE);

            assertThat(page).isEqualTo(new PageDto<>(List.of(), 0, 20, 0));
            verify(ontologyMetadataRepository).findForList(USER_ID, true, null, true, List.of(""));
            verifyNoMoreInteractions(ontologyMetadataRepository);
            verify(jenaTDB2Repository, never()).fetchMetadataProperties(anyList());
        }

        @Test
        void withSlugs_restrictsToThem() {
            when(ontologyMetadataRepository.findForList(null, null, null, false, List.of("a", "b")))
                    .thenReturn(List.of());

            reader.list(query(null, null, List.of("a", "b"), null), FIRST_PAGE);

            verify(ontologyMetadataRepository).findForList(null, null, null, false, List.of("a", "b"));
        }

        @Test
        void moreThanSixSlugs_isABadArgument() {
            List<String> seven = List.of("a", "b", "c", "d", "e", "f", "g");

            assertThatThrownBy(() -> reader.list(query(null, null, seven, null), FIRST_PAGE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Maximální počet slugů je 6.");
            verifyNoInteractions(ontologyMetadataRepository);
        }

        @Test
        void filtersOnTheRdfName_thenSortsAndCountsBeforeCutting() {
            List<OntologyMetadataEntity> rows = List.of(
                    ontologyEntity(1L, "http://example.org/o/1"),
                    ontologyEntity(2L, "http://example.org/o/2"),
                    ontologyEntity(3L, "http://example.org/o/3"));
            when(ontologyMetadataRepository.findForList(null, null, null, true, List.of(""))).thenReturn(rows);
            Model batch = ModelFactory.createDefaultModel();
            batch.add(batch.createResource("http://example.org/o/1"), SKOS.prefLabel, "Životní prostředí");
            batch.add(batch.createResource("http://example.org/o/2"), SKOS.prefLabel, "Silniční provoz");
            batch.add(batch.createResource("http://example.org/o/3"), SKOS.prefLabel, "Školství");
            when(jenaTDB2Repository.fetchMetadataProperties(anyList())).thenReturn(batch);

            // "i" (no diacritics) matches "Životní", "Silniční" and "Školství"; the page holds the second of three.
            PageDto<OntologySummaryDto> page = reader.list(query(null, null, null, "I"), new PageParams(1, 1));

            assertThat(page.totalCount()).isEqualTo(3);
            assertThat(page.items()).singleElement()
                    .satisfies(row -> assertThat(row.getName()).isEqualTo(Map.of("cs", "Školství")));
        }
    }
}
