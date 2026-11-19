package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.CodeListDto;
import com.dia.ismdtoolbackend.controller.dto.GetConceptDto;
import com.dia.ismdtoolbackend.controller.dto.NkodCodelistCheckDto;
import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.NkodCodelistStatus;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.DeviationStatus;
import com.dia.ismdtoolbackend.models.rpp.RppAgenda;
import com.dia.ismdtoolbackend.models.rpp.RppIsvs;
import com.dia.ismdtoolbackend.repository.CommentRepository;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.service.nkod.NkodCodelistService;
import com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static com.dia.ismdtoolbackend.service.impl.ConceptServiceTestData.CONCEPT_ID;
import static com.dia.ismdtoolbackend.service.impl.ConceptServiceTestData.CONCEPT_IRI;
import static com.dia.ismdtoolbackend.service.impl.ConceptServiceTestData.GRAPH_NAME;
import static com.dia.ismdtoolbackend.service.impl.ConceptServiceTestData.SLUG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConceptDetailReaderTest {

    private static final String AGENDA_IRI = "https://rpp.example/agenda/A1";
    private static final String ISVS_IRI = "https://rpp.example/isvs/I1";

    private ConceptMetadataRepository conceptMetadataRepository;
    private JenaTDB2Repository jenaTDB2Repository;
    private OntologyDetailExtractor detailExtractor;
    private CommentRepository commentRepository;
    private ReferencedConceptsEnricher referencedConceptsEnricher;
    private RppSnapshotHolder rppSnapshotHolder;
    private NkodCodelistService nkodCodelistService;
    private WorkingCopyDeviationServiceImpl workingCopyDeviationService;
    private ConceptDetailReader reader;

    private ConceptMetadataEntity concept;
    private Model graph;

    @BeforeEach
    void setUp() {
        ConceptServiceFixture fixture = new ConceptServiceFixture();
        conceptMetadataRepository = fixture.conceptMetadataRepository();
        jenaTDB2Repository = fixture.jenaTDB2Repository();
        detailExtractor = fixture.detailExtractor();
        commentRepository = fixture.commentRepository();
        referencedConceptsEnricher = fixture.referencedConceptsEnricher();
        rppSnapshotHolder = fixture.rppSnapshotHolder();
        nkodCodelistService = fixture.nkodCodelistService();
        workingCopyDeviationService = fixture.workingCopyDeviationService();
        reader = fixture.buildDetailReader();

        concept = ConceptServiceTestData.concept();
        graph = ConceptServiceTestData.graphWithConcept();
    }

    /** The concept and its graph load, with no cross-graph rdfs:domain members to merge in. */
    private void givenConceptGraph() {
        when(conceptMetadataRepository.findBySlug(SLUG)).thenReturn(Optional.of(concept));
        when(jenaTDB2Repository.fetchGraph(GRAPH_NAME)).thenReturn(graph);
        when(jenaTDB2Repository.fetchExternalDomainMembers(anyString())).thenReturn(ModelFactory.createDefaultModel());
    }

    /** A full read whose extractor answers {@code detail} and whose concept has no comments. */
    private void givenDetail(ConceptDetailModel detail) {
        givenConceptGraph();
        when(detailExtractor.extractConceptDetail(graph, CONCEPT_IRI)).thenReturn(detail);
        when(commentRepository.findByConceptMetadataId(CONCEPT_ID)).thenReturn(List.of());
    }

    private static ConceptDetailModel.ConceptDetailModelBuilder detail() {
        return ConceptDetailModel.builder().iri(CONCEPT_IRI);
    }

    @Nested
    class Lookup {

        @Test
        void rejectsAnUnknownSlug() {
            when(conceptMetadataRepository.findBySlug(SLUG)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> reader.getConceptDetail(SLUG))
                    .isInstanceOf(OntologyException.class)
                    .hasMessageContaining(SLUG);
        }

        @Test
        void rejectsAnEmptyGraph() {
            when(conceptMetadataRepository.findBySlug(SLUG)).thenReturn(Optional.of(concept));
            when(jenaTDB2Repository.fetchGraph(GRAPH_NAME)).thenReturn(ModelFactory.createDefaultModel());

            assertThatThrownBy(() -> reader.getConceptDetail(SLUG))
                    .isInstanceOf(OntologyException.class)
                    .hasMessageContaining("prázdný");
        }

        @Test
        void rejectsAConceptTheExtractorCannotFind() {
            givenConceptGraph();
            when(detailExtractor.extractConceptDetail(graph, CONCEPT_IRI)).thenReturn(null);

            assertThatThrownBy(() -> reader.getConceptDetail(SLUG))
                    .isInstanceOf(OntologyException.class)
                    .hasMessageContaining(CONCEPT_IRI);
        }

        @Test
        void attachesTheComments() {
            givenConceptGraph();
            when(detailExtractor.extractConceptDetail(graph, CONCEPT_IRI)).thenReturn(detail().build());
            CommentEntity comment = new CommentEntity();
            comment.setId(5L);
            comment.setUserId("commenter");
            comment.setComment("Upřesnit definici");
            comment.setConceptMetadata(concept);
            when(commentRepository.findByConceptMetadataId(CONCEPT_ID)).thenReturn(List.of(comment));

            GetConceptDto result = reader.getConceptDetail(SLUG);

            assertThat(result.getConceptMetadata().getComments())
                    .singleElement()
                    .satisfies(attached -> {
                        assertThat(attached.getId()).isEqualTo(5L);
                        assertThat(attached.getUserId()).isEqualTo("commenter");
                        assertThat(attached.getComment()).isEqualTo("Upřesnit definici");
                        assertThat(attached.getConceptIRI()).isEqualTo(CONCEPT_IRI);
                    });
        }
    }

    @Nested
    class Deviation {

        @Test
        void aDraftHasNoDeviation() {
            ConceptDetailModel detail = detail().build();
            givenDetail(detail);

            GetConceptDto result = reader.getConceptDetail(SLUG);

            assertThat(result.getConceptDetail()).isEqualTo(detail);
            assertThat(result.getPublishedConceptDeviationModel()).isNull();
            verify(referencedConceptsEnricher).enrich(detail);
            verifyNoInteractions(workingCopyDeviationService);
        }

        /** The deviation comes from the shared service, computed off the graph this read already fetched. */
        @Test
        void aWorkingCopyGetsItsDeviationFromTheSharedService() {
            concept.setIsPublished(true);
            givenDetail(detail().build());
            ConceptDetailModel canonicalLocal = detail().build();
            PublishedConceptDeviationModel deviation = PublishedConceptDeviationModel.builder()
                    .status(DeviationStatus.NO_DEVIATION)
                    .build();
            when(workingCopyDeviationService.canonicalLocalConcept(eq(CONCEPT_IRI), any(Model.class)))
                    .thenReturn(canonicalLocal);
            when(workingCopyDeviationService.deviationForWithLocal(CONCEPT_IRI, canonicalLocal)).thenReturn(deviation);

            GetConceptDto result = reader.getConceptDetail(SLUG);

            assertThat(result.getPublishedConceptDeviationModel()).isEqualTo(deviation);
            verify(workingCopyDeviationService, never()).deviationFor(any());
            verify(jenaTDB2Repository, times(1)).fetchGraph(GRAPH_NAME);
        }
    }

    @Nested
    class RppReferences {

        @Test
        void resolvesTheAgenda() {
            givenDetail(detail().agenda(AGENDA_IRI).build());
            RppAgenda agenda = mock(RppAgenda.class);
            when(rppSnapshotHolder.findAgendaByIri(AGENDA_IRI)).thenReturn(Optional.of(agenda));

            GetConceptDto result = reader.getConceptDetail(SLUG);

            assertThat(result.getConceptDetail().getAgendaResolved()).isSameAs(agenda);
            verify(rppSnapshotHolder, never()).findIsvsByIri(anyString());
        }

        @Test
        void resolvesTheIsvs() {
            givenDetail(detail().ais(ISVS_IRI).build());
            RppIsvs isvs = mock(RppIsvs.class);
            when(rppSnapshotHolder.findIsvsByIri(ISVS_IRI)).thenReturn(Optional.of(isvs));

            GetConceptDto result = reader.getConceptDetail(SLUG);

            assertThat(result.getConceptDetail().getAisResolved()).isSameAs(isvs);
            verify(rppSnapshotHolder, never()).findAgendaByIri(anyString());
        }

        @Test
        void skipsTheRegistryWithoutReferences() {
            givenDetail(detail().build());

            reader.getConceptDetail(SLUG);

            verifyNoInteractions(rppSnapshotHolder);
        }
    }

    @Nested
    class CodeList {

        private final CodeListDto stored = CodeListDto.builder()
                .iri("https://rpp.example/číselníky/151/2024-01-01")
                .datovaSadaVNkod("https://data.gov.cz/zdroj/datové-sady/17651921/5ccc4289")
                .build();

        @Test
        void setsTheCheckResult() {
            givenDetail(detail().codeList(stored).build());
            NkodCodelistCheckDto check = NkodCodelistCheckDto.builder().status(NkodCodelistStatus.NEW_VERSION).build();
            when(nkodCodelistService.check(stored)).thenReturn(Optional.of(check));

            GetConceptDto result = reader.getConceptDetail(SLUG);

            assertThat(result.getConceptDetail().getCodeListResolved()).isSameAs(check);
            assertThat(result.getConceptDetail().getCodeList()).isSameAs(stored);
        }

        @Test
        void leavesItUnsetWhenTheCheckReportsNothing() {
            givenDetail(detail().codeList(stored).build());
            when(nkodCodelistService.check(stored)).thenReturn(Optional.empty());

            GetConceptDto result = reader.getConceptDetail(SLUG);

            assertThat(result.getConceptDetail().getCodeListResolved()).isNull();
            assertThat(result.getConceptDetail().getCodeList()).isSameAs(stored);
        }
    }
}
