package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.GetOntologyDto;
import com.dia.ismdtoolbackend.controller.dto.MinimalConceptDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.PublishedOntologyDeviationModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.DeviationStatus;
import com.dia.ismdtoolbackend.repository.CommentRepository;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.ForeignMemberCount;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.NkdDetailService;
import com.dia.ismdtoolbackend.service.snapshot.NkdSnapshotWarmer;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.published.PublishedResourceUtil;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.dia.constants.VocabularyConstants.POJEM_JSON_LD;
import static com.dia.constants.VocabularyConstants.TRIDA_JSON_LD;
import static com.dia.constants.VocabularyConstants.VZTAH_JSON_LD;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.GRAPH;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.ONTOLOGY_ID;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.SLUG;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.concept;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.conceptWithSlug;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.detailWithConcepts;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.nonEmptyModel;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.ontologyEntity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Vocabulary detail: the core read, the full DTO with deviations, foreign member counts and concept listings. */
@ExtendWith(MockitoExtension.class)
class OntologyDetailReaderTest {

    private OntologyMetadataRepository ontologyMetadataRepository;
    private ConceptMetadataRepository conceptMetadataRepository;
    private JenaTDB2Repository jenaTDB2Repository;
    private CommentRepository commentRepository;
    private OntologyDetailExtractor detailExtractor;
    private PublishedResourceUtil deviationChecker;
    private NkdSnapshotWarmer nkdSnapshotWarmer;
    private NkdDetailService nkdDetailService;

    private OntologyDetailReader reader;

    @Captor private ArgumentCaptor<List<String>> irisCaptor;

    @BeforeEach
    void setUp() {
        OntologyServiceFixture fixture = new OntologyServiceFixture();
        ontologyMetadataRepository = fixture.ontologyMetadataRepository();
        conceptMetadataRepository = fixture.conceptMetadataRepository();
        jenaTDB2Repository = fixture.jenaTDB2Repository();
        commentRepository = fixture.commentRepository();
        detailExtractor = fixture.detailExtractor();
        deviationChecker = fixture.deviationChecker();
        nkdSnapshotWarmer = fixture.nkdSnapshotWarmer();
        nkdDetailService = fixture.nkdDetailService();
        reader = fixture.detailReader();
    }

    /** Stubs the stored vocabulary {@link OntologyTestData#SLUG} whose graph extracts to {@code detail}. */
    private Model givenStoredDetail(OntologyDetailModel detail) {
        Model graph = nonEmptyModel();
        when(ontologyMetadataRepository.findBySlug(SLUG)).thenReturn(Optional.of(ontologyEntity()));
        when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(graph);
        when(detailExtractor.applyOFNTransformations(graph)).thenReturn(graph);
        when(detailExtractor.extractOntologyDetail(graph)).thenReturn(detail);
        return graph;
    }

    private static ConceptDetailModel conceptByIri(OntologyDetailModel detail, String iri) {
        return detail.getConcepts().stream()
                .filter(c -> iri.equals(c.getIri()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("concept not in detail: " + iri));
    }

    @Nested
    class Detail {

        @Test
        void coreDetail_buildsNoMetadataDto() {
            OntologyDetailModel detail = detailWithConcepts();
            Model graph = givenStoredDetail(detail);

            assertThat(reader.getOntologyDetail(SLUG)).isSameAs(detail);
            verify(detailExtractor).applyOFNTransformations(graph);
            // Comments, concept rows and deviations belong to the full DTO only.
            verifyNoInteractions(commentRepository, conceptMetadataRepository, deviationChecker);
        }

        @Test
        void fullDto_carriesMetadataConceptsAndDeviations_fromOneGraphFetch() {
            OntologyDetailModel detail = detailWithConcepts();
            Model graph = givenStoredDetail(detail);
            ConceptMetadataEntity conceptRow = concept(GRAPH + "/pojem/test-concept", "test-concept");
            when(commentRepository.findByOntologyMetadataId(ONTOLOGY_ID)).thenReturn(List.of());
            when(conceptMetadataRepository.findByGraphName(GRAPH)).thenReturn(List.of(conceptRow));
            PublishedOntologyDeviationModel ontologyDeviation = PublishedOntologyDeviationModel.builder()
                    .status(DeviationStatus.HAS_DEVIATIONS)
                    .build();
            PublishedConceptDeviationModel conceptDeviation = PublishedConceptDeviationModel.builder()
                    .status(DeviationStatus.HAS_DEVIATIONS)
                    .build();
            when(deviationChecker.checkOntologyDeviation(same(detail),
                    argThat(m -> m != null && SLUG.equals(m.getSlug()))))
                    .thenReturn(ontologyDeviation);
            when(deviationChecker.checkConceptsDeviation(same(graph), eq(List.of(conceptRow))))
                    .thenReturn(Map.of(conceptRow.getConceptIri(), conceptDeviation));

            GetOntologyDto result = reader.getOntologyDetailModel(SLUG);

            assertThat(result.getPublishedOntologyDeviationModel()).isSameAs(ontologyDeviation);
            assertThat(result.getPublishedConceptDeviations())
                    .containsExactly(entry(conceptRow.getConceptIri(), conceptDeviation));
            assertThat(result.getOntologyDetail().getIri()).isEqualTo(GRAPH);
            assertThat(result.getOntologyMetadata().getSlug()).isEqualTo(SLUG);
            assertThat(result.getOntologyMetadata().getConcepts())
                    .extracting(ConceptMetadataModel::getConceptIri, ConceptMetadataModel::getConceptName)
                    .containsExactly(tuple(conceptRow.getConceptIri(), "test-concept"));
            assertThat(result.getOntologyMetadata().getConceptCount()).isEqualTo(1);
            assertThat(result.getOntologyDetail().getConceptCount()).isEqualTo(1);
            // The metadata enrichment reuses the fetched graph.
            verify(jenaTDB2Repository).fetchGraph(GRAPH);
            verify(detailExtractor).extractOntologyDetail(graph);
            // No snapshot rows yet: the warmer is started for a cold graph.
            verify(nkdSnapshotWarmer).warmGraph(GRAPH);
        }

        @Test
        void unknownSlug_throwsNotFound() {
            when(ontologyMetadataRepository.findBySlug(SLUG)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> reader.getOntologyDetailModel(SLUG))
                    .isInstanceOf(OntologyNotFoundException.class)
                    .hasMessageContaining("nebyla nalezena");
        }

        @Test
        void emptyGraph_throwsNotFound() {
            when(ontologyMetadataRepository.findBySlug(SLUG)).thenReturn(Optional.of(ontologyEntity()));
            when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(ModelFactory.createDefaultModel());

            assertThatThrownBy(() -> reader.getOntologyDetailModel(SLUG))
                    .isInstanceOf(OntologyNotFoundException.class)
                    .hasMessageContaining("prázdný");
        }
    }

    /**
     * Ontology detail scopes members to its own graph while concept detail merges cross-graph ones in;
     * the counts are what the two surfaces differ by, so they reach the DTO.
     */
    @Nested
    class ForeignMemberCounts {

        @Test
        void annotatesCountedConcepts_andLeavesOthersNull() {
            String classIri = GRAPH + "/pojem/osoba";
            String bareIri = GRAPH + "/pojem/bez-cizích";
            givenStoredDetail(detailWithConcepts(classIri, bareIri));
            when(jenaTDB2Repository.countExternalDomainMembers(eq(GRAPH), anyList()))
                    .thenReturn(Map.of(classIri, new ForeignMemberCount(3, 1)));

            OntologyDetailModel result = reader.getOntologyDetail(SLUG);

            ConceptDetailModel annotated = conceptByIri(result, classIri);
            assertThat(annotated.getForeignPropertyCount()).isEqualTo(3);
            assertThat(annotated.getForeignRelationshipCount()).isEqualTo(1);
            // Null, not 0, so the keys stay out of the JSON.
            ConceptDetailModel untouched = conceptByIri(result, bareIri);
            assertThat(untouched.getForeignPropertyCount()).isNull();
            assertThat(untouched.getForeignRelationshipCount()).isNull();
        }

        /** One batched call for the whole ontology; a per-concept call would be an N+1 fan-out. */
        @Test
        void countsAllConceptsInOneCall() {
            String a = GRAPH + "/pojem/a";
            String b = GRAPH + "/pojem/b";
            String c = GRAPH + "/pojem/c";
            givenStoredDetail(detailWithConcepts(a, b, c));
            when(jenaTDB2Repository.countExternalDomainMembers(anyString(), anyList())).thenReturn(Map.of());

            reader.getOntologyDetail(SLUG);

            verify(jenaTDB2Repository).countExternalDomainMembers(eq(GRAPH), irisCaptor.capture());
            assertThat(irisCaptor.getValue()).containsExactly(a, b, c);
        }

        /** Counting is supplementary: a Fuseki failure leaves the detail intact. */
        @Test
        void failingCount_isSwallowed() {
            String classIri = GRAPH + "/pojem/osoba";
            givenStoredDetail(detailWithConcepts(classIri));
            when(jenaTDB2Repository.countExternalDomainMembers(anyString(), anyList()))
                    .thenThrow(new RuntimeException("Fuseki down"));

            OntologyDetailModel result = reader.getOntologyDetail(SLUG);

            assertThat(conceptByIri(result, classIri).getForeignPropertyCount()).isNull();
        }

        @Test
        void noConcepts_noQuery() {
            givenStoredDetail(detailWithConcepts());

            reader.getOntologyDetail(SLUG);

            verify(jenaTDB2Repository, never()).countExternalDomainMembers(anyString(), anyList());
        }
    }

    @Nested
    class ConceptsByIri {

        /** Stubs the ISMD vocabulary at {@link OntologyTestData#GRAPH} whose graph extracts to {@code concepts}. */
        private void givenIsmdConcepts(List<ConceptDetailModel> concepts) {
            Model graph = nonEmptyModel();
            when(ontologyMetadataRepository.findByGraphName(GRAPH)).thenReturn(Optional.of(ontologyEntity()));
            when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(graph);
            when(detailExtractor.applyOFNTransformations(graph)).thenReturn(graph);
            when(detailExtractor.extractOntologyDetail(graph))
                    .thenReturn(OntologyDetailModel.builder().concepts(concepts).build());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void missingIri_throws(String iri) {
            assertThatThrownBy(() -> reader.getConceptsByIri(iri, SearchSource.ISMD))
                    .isInstanceOf(OntologyException.class)
                    .hasMessageContaining("IRI");
        }

        @Test
        void missingSource_throws() {
            assertThatThrownBy(() -> reader.getConceptsByIri(GRAPH, null))
                    .isInstanceOf(OntologyException.class)
                    .hasMessageContaining("source");
        }

        @Test
        void unsupportedSource_throws() {
            assertThatThrownBy(() -> reader.getConceptsByIri(GRAPH, SearchSource.UNPUBLISHED))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void unknownIsmdOntology_throwsNotFound() {
            when(ontologyMetadataRepository.findByGraphName(GRAPH)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> reader.getConceptsByIri(GRAPH, SearchSource.ISMD))
                    .isInstanceOf(OntologyNotFoundException.class)
                    .hasMessageContaining(GRAPH);
        }

        @Test
        void emptyIsmdGraph_throwsNotFound() {
            when(ontologyMetadataRepository.findByGraphName(GRAPH)).thenReturn(Optional.of(ontologyEntity()));
            when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(ModelFactory.createDefaultModel());

            assertThatThrownBy(() -> reader.getConceptsByIri(GRAPH, SearchSource.ISMD))
                    .isInstanceOf(OntologyNotFoundException.class)
                    .hasMessageContaining("prázdný");
        }

        @ParameterizedTest
        @NullAndEmptySource
        void noIsmdConcepts_returnsEmptyWithoutTheSlugLookup(List<ConceptDetailModel> concepts) {
            givenIsmdConcepts(concepts);

            assertThat(reader.getConceptsByIri(GRAPH, SearchSource.ISMD)).isEmpty();
            verify(conceptMetadataRepository, never()).findByGraphName(anyString());
        }

        @Test
        void ismdConcepts_joinSlugsFromPg_andResolveTheirType() {
            // Types carry the short OFN JSON-LD labels the extractor emits, not rdf:type IRIs.
            givenIsmdConcepts(List.of(
                    ConceptDetailModel.builder().iri("http://example.org/c/1").name(Map.of("cs", "Pojem 1"))
                            .types(List.of(POJEM_JSON_LD, "Koncept", TRIDA_JSON_LD)).build(),
                    ConceptDetailModel.builder().iri("http://example.org/c/2").name(Map.of("cs", "Pojem 2"))
                            .types(List.of(POJEM_JSON_LD, "Koncept", VZTAH_JSON_LD)).build(),
                    ConceptDetailModel.builder().iri("http://example.org/c/3").name(Map.of("cs", "Pojem 3"))
                            .build()));
            // c/3 has no row; the null row is filtered out of the join.
            when(conceptMetadataRepository.findByGraphName(GRAPH)).thenReturn(List.of(
                    conceptWithSlug("http://example.org/c/1", "pojem-1"),
                    conceptWithSlug("http://example.org/c/2", "pojem-2"),
                    conceptWithSlug(null, null)));

            List<MinimalConceptDto> out = reader.getConceptsByIri(GRAPH, SearchSource.ISMD);

            assertThat(out)
                    .extracting(MinimalConceptDto::getIri, MinimalConceptDto::getSlug, MinimalConceptDto::getConceptType)
                    .containsExactly(
                            tuple("http://example.org/c/1", "pojem-1", ConceptType.TRIDA),
                            tuple("http://example.org/c/2", "pojem-2", ConceptType.VZTAH),
                            // No PG row: no slug; no role type: KONCEPT.
                            tuple("http://example.org/c/3", null, ConceptType.KONCEPT));
        }

        /** NKD concepts come from the slim listing; the full detail CONSTRUCT would fetch every concept's graph. */
        @Test
        void nkdConcepts_comeFromTheSlimListing_withoutSlug() {
            String iri = "https://data.gov.cz/zdroj/slovnik/test";
            when(nkdDetailService.listOntologyConcepts(iri)).thenReturn(List.of(MinimalConceptDto.builder()
                    .iri(iri + "/pojem/bar")
                    .name(Map.of("cs", "Bar"))
                    .conceptType(ConceptType.TRIDA)
                    .build()));

            List<MinimalConceptDto> out = reader.getConceptsByIri(iri, SearchSource.NKD);

            assertThat(out).singleElement().satisfies(concept -> {
                assertThat(concept.getIri()).isEqualTo(iri + "/pojem/bar");
                assertThat(concept.getSlug()).isNull();
                assertThat(concept.getConceptType()).isEqualTo(ConceptType.TRIDA);
            });
            verify(ontologyMetadataRepository, never()).findByGraphName(anyString());
            verify(nkdDetailService, never()).getOntologyDetail(anyString());
        }

        @Test
        void nkdWithoutConcepts_returnsEmpty() {
            String iri = "https://data.gov.cz/zdroj/slovnik/test";
            when(nkdDetailService.listOntologyConcepts(iri)).thenReturn(List.of());

            assertThat(reader.getConceptsByIri(iri, SearchSource.NKD)).isEmpty();
        }
    }
}
