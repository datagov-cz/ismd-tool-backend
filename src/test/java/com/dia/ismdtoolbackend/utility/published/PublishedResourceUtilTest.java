package com.dia.ismdtoolbackend.utility.published;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.SnapshotOrigin;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.models.PublishedOntologyDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.DeviationStatus;
import com.dia.ismdtoolbackend.service.WorkingCopyDeviationService;
import com.dia.ismdtoolbackend.service.impl.OntologyDeviationComparator;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublishedResourceUtilTest {

    private static final String GRAPH = "https://slovník.gov.cz/agendový/123/slovník";
    private static final String OTHER_GRAPH = "https://slovník.gov.cz/agendový/456/slovník";
    private static final String CONCEPT_A = GRAPH + "/pojem/a";
    private static final String CONCEPT_B = GRAPH + "/pojem/b";
    private static final String POJEM =
            "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/pojem";
    private static final List<String> TYPES = List.of("Slovník", "Tezaurus");

    @Mock
    private NkdSparqlClient nkd;
    @Mock
    private WorkingCopyDeviationService workingCopyDeviation;

    private PublishedResourceUtil util;

    @BeforeEach
    void setUp() {
        util = new PublishedResourceUtil(nkd, new OntologyDeviationComparator(), workingCopyDeviation);
    }

    @Nested
    class OntologyDeviation {

        @Test
        void unpublishedOntologyIsNotCompared() {
            assertThat(util.checkOntologyDeviation(detail(Map.of("cs", "Slovník")), metadata(false))).isNull();
            verifyNoInteractions(nkd);
        }

        @Test
        void identicalOntologyHasNoDeviation() {
            when(nkd.fetchPublishedOntology(GRAPH)).thenReturn(Optional.of(detail(Map.of("cs", "Slovník"))));

            PublishedOntologyDeviationModel result =
                    util.checkOntologyDeviation(detail(Map.of("cs", "Slovník")), metadata(true));

            assertThat(result.getStatus()).isEqualTo(DeviationStatus.NO_DEVIATION);
            assertThat(result.getTypes()).isNull();
            assertThat(result.getName()).isNull();
            assertThat(result.getDescription()).isNull();
        }

        @Test
        void changedNameIsReportedWithBothValues() {
            when(nkd.fetchPublishedOntology(GRAPH)).thenReturn(Optional.of(detail(Map.of("cs", "Publikovaný"))));

            PublishedOntologyDeviationModel result =
                    util.checkOntologyDeviation(detail(Map.of("cs", "Pracovní")), metadata(true));

            assertThat(result.getStatus()).isEqualTo(DeviationStatus.HAS_DEVIATIONS);
            assertThat(result.getName().getLocalValue()).isEqualTo(Map.of("cs", "Pracovní"));
            assertThat(result.getName().getPublishedValue()).isEqualTo(Map.of("cs", "Publikovaný"));
            assertThat(result.getName().isDifferent()).isTrue();
            assertThat(result.getTypes()).isNull();
            assertThat(result.getDescription()).isNull();
        }

        @Test
        void changedTypesAndDescriptionAreReported() {
            OntologyDetailModel published = detail(Map.of("cs", "Slovník"));
            published.setTypes(List.of("Slovník"));
            published.setDescription(Map.of("cs", "Starý popis"));
            when(nkd.fetchPublishedOntology(GRAPH)).thenReturn(Optional.of(published));

            PublishedOntologyDeviationModel result =
                    util.checkOntologyDeviation(detail(Map.of("cs", "Slovník")), metadata(true));

            assertThat(result.getStatus()).isEqualTo(DeviationStatus.HAS_DEVIATIONS);
            assertThat(result.getTypes().getLocalValue()).isEqualTo(TYPES);
            assertThat(result.getTypes().getPublishedValue()).containsExactly("Slovník");
            assertThat(result.getDescription().getLocalValue()).isEqualTo(Map.of("cs", "Popis"));
            assertThat(result.getDescription().getPublishedValue()).isEqualTo(Map.of("cs", "Starý popis"));
            assertThat(result.getName()).isNull();
        }

        @Test
        void missingLocalDetailIsAQueryError() {
            PublishedOntologyDeviationModel result = util.checkOntologyDeviation(null, metadata(true));

            assertThat(result.getStatus()).isEqualTo(DeviationStatus.QUERY_ERROR);
            assertThat(result.getErrorMessage()).isEqualTo("Local ontology detail not available");
            verifyNoInteractions(nkd);
        }

        @Test
        void ontologyAbsentFromNkdIsReportedAsNotFound() {
            when(nkd.fetchPublishedOntology(GRAPH)).thenReturn(Optional.empty());

            PublishedOntologyDeviationModel result =
                    util.checkOntologyDeviation(detail(Map.of("cs", "Slovník")), metadata(true));

            assertThat(result.getStatus()).isEqualTo(DeviationStatus.CONCEPT_NOT_FOUND_IN_NKD);
        }

        @Test
        void failingNkdIsReportedAsUnavailable() {
            when(nkd.fetchPublishedOntology(GRAPH)).thenThrow(new IllegalStateException("timeout"));

            PublishedOntologyDeviationModel result =
                    util.checkOntologyDeviation(detail(Map.of("cs", "Slovník")), metadata(true));

            assertThat(result.getStatus()).isEqualTo(DeviationStatus.ENDPOINT_UNAVAILABLE);
            assertThat(result.getErrorMessage()).isEqualTo("NKD SPARQL endpoint unavailable: timeout");
        }
    }

    @Nested
    class ConceptsDeviation {

        private final Model processed = ModelFactory.createDefaultModel();

        @Test
        void noPublishedConceptsSkipsTheCheck() {
            Map<String, PublishedConceptDeviationModel> result =
                    util.checkConceptsDeviation(processed, List.of(concept(CONCEPT_A, GRAPH, false)));

            assertThat(result).isEmpty();
            verifyNoInteractions(workingCopyDeviation);
        }

        @Test
        void onlyPublishedConceptsAreComparedWithinTheirOntology() {
            PublishedConceptDeviationModel deviation =
                    PublishedConceptDeviationModel.builder().status(DeviationStatus.NO_DEVIATION).build();
            when(workingCopyDeviation.deviationForAllInOntology(eq(processed), anyList(), eq(GRAPH)))
                    .thenReturn(Map.of(CONCEPT_A, deviation));

            Map<String, PublishedConceptDeviationModel> result = util.checkConceptsDeviation(processed, List.of(
                    concept(CONCEPT_A, GRAPH, true), concept(CONCEPT_B, GRAPH, false)));

            assertThat(result).containsExactly(Map.entry(CONCEPT_A, deviation));
            verify(workingCopyDeviation).deviationForAllInOntology(processed, List.of(CONCEPT_A), GRAPH);
        }

        @Test
        void conceptsFromSeveralGraphsAreComparedWithoutAnOntology() {
            when(workingCopyDeviation.deviationForAllInOntology(eq(processed), anyList(), isNull()))
                    .thenReturn(Map.of());

            util.checkConceptsDeviation(processed, List.of(
                    concept(CONCEPT_A, GRAPH, true), concept(CONCEPT_B, OTHER_GRAPH, true)));

            verify(workingCopyDeviation).deviationForAllInOntology(processed, List.of(CONCEPT_A, CONCEPT_B), null);
        }

        @Test
        void failedBulkCheckGivesEveryConceptAnErrorThatStillNamesItsTwin() {
            when(workingCopyDeviation.deviationForAllInOntology(any(), anyList(), any()))
                    .thenThrow(new IllegalStateException("graph unreadable"));

            Map<String, PublishedConceptDeviationModel> result = util.checkConceptsDeviation(processed, List.of(
                    concept(CONCEPT_A, GRAPH, true), concept(CONCEPT_B, GRAPH, true)));

            assertThat(result).containsOnlyKeys(CONCEPT_A, CONCEPT_B);
            assertThat(result.values()).allSatisfy(error -> {
                assertThat(error.getStatus()).isEqualTo(DeviationStatus.QUERY_ERROR);
                assertThat(error.getErrorMessage()).isEqualTo("Error checking concept: graph unreadable");
                assertThat(error.getOrigin()).isEqualTo(SnapshotOrigin.WORKING_COPY);
            });
            assertThat(result.get(CONCEPT_A).getSource().getIri()).isEqualTo(CONCEPT_A);
            assertThat(result.get(CONCEPT_B).getSource().getIri()).isEqualTo(CONCEPT_B);
        }
    }

    @Nested
    class PublishedResourcesInNkd {

        @Test
        void asksNkdAboutTheOntologyItsConceptsAndExtraIrisOnce() {
            OntModel model = ModelFactory.createOntologyModel();
            model.createResource(GRAPH).addProperty(RDF.type, OWL2.Ontology);
            model.createResource(CONCEPT_A).addProperty(RDF.type, model.createResource(POJEM));
            model.createResource(CONCEPT_B).addProperty(RDF.type, model.createResource(POJEM));
            model.createResource(GRAPH + "/pojem/not-a-concept").addProperty(RDF.type, OWL2.Class);
            model.createResource().addProperty(RDF.type, model.createResource(POJEM));
            when(nkd.getPublishedResourcesList(anyList())).thenReturn(List.of(CONCEPT_A));

            List<String> published = util.checkPublishedResourcesInNKD(model, List.of(CONCEPT_A, OTHER_GRAPH));

            assertThat(published).containsExactly(CONCEPT_A);
            ArgumentCaptor<List<String>> asked = ArgumentCaptor.captor();
            verify(nkd).getPublishedResourcesList(asked.capture());
            assertThat(asked.getValue()).first().isEqualTo(GRAPH);
            assertThat(asked.getValue()).containsExactlyInAnyOrder(GRAPH, CONCEPT_A, CONCEPT_B, OTHER_GRAPH);
        }

        @Test
        void emptyModelDoesNotQueryNkd() {
            assertThat(util.checkPublishedResourcesInNKD(ModelFactory.createOntologyModel())).isEmpty();
            verifyNoInteractions(nkd);
        }

        @Test
        void anonymousOntologyIsNotSent() {
            OntModel model = ModelFactory.createOntologyModel();
            model.createResource().addProperty(RDF.type, OWL2.Ontology);
            when(nkd.getPublishedResourcesList(List.of(OTHER_GRAPH))).thenReturn(List.of());

            assertThat(util.checkPublishedResourcesInNKD(model, List.of(OTHER_GRAPH))).isEmpty();
        }
    }

    private static OntologyMetadataModel metadata(boolean published) {
        OntologyMetadataModel metadata = new OntologyMetadataModel();
        metadata.setGraphName(GRAPH);
        metadata.setIsPublished(published);
        return metadata;
    }

    private static OntologyDetailModel detail(Map<String, String> name) {
        return OntologyDetailModel.builder()
                .iri(GRAPH)
                .types(TYPES)
                .name(name)
                .description(Map.of("cs", "Popis"))
                .build();
    }

    private static ConceptMetadataEntity concept(String iri, String graph, boolean published) {
        ConceptMetadataEntity concept = new ConceptMetadataEntity();
        concept.setConceptIri(iri);
        concept.setGraphName(graph);
        concept.setIsPublished(published);
        return concept;
    }
}
