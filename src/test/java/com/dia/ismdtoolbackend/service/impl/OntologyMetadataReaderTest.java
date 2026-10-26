package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptSourceTag;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

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

/** Vocabulary metadata reads: single rows, filtered lists and slug batches. */
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
        void bySlug_mapsTheRowAsADraft() {
            when(ontologyMetadataRepository.findBySlug(SLUG)).thenReturn(Optional.of(ontologyEntity()));

            OntologyMetadataModel result = reader.getOntologyMetadataBySlug(SLUG);

            assertThat(result.getId()).isEqualTo(ONTOLOGY_ID);
            assertThat(result.getSlug()).isEqualTo(SLUG);
            assertThat(result.getGraphName()).isEqualTo(GRAPH);
            assertThat(result.getUser().getUserId()).isEqualTo(USER_ID);
            assertThat(result.getSourceTag()).isEqualTo(ConceptSourceTag.DRAFT);
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

            assertThat(reader.getOntologyMetadata(ONTOLOGY_ID).getSlug()).isEqualTo(SLUG);
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
    class All {

        static Stream<Arguments> filters() {
            return Stream.of(
                    Arguments.of(null, null, finder(OntologyMetadataRepository::findAll)),
                    Arguments.of(USER_ID, null, finder(repo -> repo.findAllByUserId(USER_ID))),
                    Arguments.of(null, true, finder(repo -> repo.findAllByIsPublished(true))),
                    Arguments.of(USER_ID, false, finder(repo -> repo.findAllByUserIdAndIsPublished(USER_ID, false))));
        }

        private static Function<OntologyMetadataRepository, List<OntologyMetadataEntity>> finder(
                Function<OntologyMetadataRepository, List<OntologyMetadataEntity>> call) {
            return call;
        }

        @ParameterizedTest(name = "userId={0}, isPublished={1}")
        @MethodSource("filters")
        void eachFilterCombination_usesOnlyItsFinder(String userId, Boolean isPublished,
                                                     Function<OntologyMetadataRepository, List<OntologyMetadataEntity>> finder) {
            when(finder.apply(ontologyMetadataRepository)).thenReturn(List.of());

            assertThat(reader.getAll(userId, isPublished)).isEmpty();

            finder.apply(verify(ontologyMetadataRepository));
            verifyNoMoreInteractions(ontologyMetadataRepository);
        }
    }

    @Nested
    class BySlugs {

        @ParameterizedTest
        @NullAndEmptySource
        void noSlugs_throws(List<String> slugs) {
            assertThatThrownBy(() -> reader.getBySlugs(slugs))
                    .isInstanceOf(OntologyException.class)
                    .hasMessageContaining("prázdný");
            verifyNoInteractions(ontologyMetadataRepository);
        }

        @Test
        void moreThanSix_throws() {
            assertThatThrownBy(() -> reader.getBySlugs(List.of("a", "b", "c", "d", "e", "f", "g")))
                    .isInstanceOf(OntologyException.class)
                    .hasMessageContaining("6");
            verifyNoInteractions(ontologyMetadataRepository);
        }

        @Test
        void foundRows_areEnrichedFromOneBatchFetch() {
            String graph = "http://example.org/o/1";
            when(ontologyMetadataRepository.findBySlugIn(List.of("slug-1")))
                    .thenReturn(List.of(ontologyEntity(1L, graph)));
            Model batch = ModelFactory.createDefaultModel();
            batch.add(batch.createResource(graph), SKOS.prefLabel, "Slovník 1");
            when(jenaTDB2Repository.fetchMetadataProperties(List.of(graph))).thenReturn(batch);

            List<OntologyMetadataModel> out = reader.getBySlugs(List.of("slug-1"));

            assertThat(out).singleElement().satisfies(model -> {
                assertThat(model.getGraphName()).isEqualTo(graph);
                assertThat(model.getName()).isEqualTo(Map.of("cs", "Slovník 1"));
            });
        }

        @Test
        void noRows_returnsEmptyWithoutFetchingRdf() {
            when(ontologyMetadataRepository.findBySlugIn(List.of("missing"))).thenReturn(List.of());

            assertThat(reader.getBySlugs(List.of("missing"))).isEmpty();
            verify(jenaTDB2Repository, never()).fetchMetadataProperties(anyList());
        }
    }
}
