package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.exception.OfnConformanceException;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.ofn.ConformanceIssue;
import com.dia.ismdtoolbackend.ofn.OfnIris;
import com.dia.ismdtoolbackend.ofn.OfnWriteGate;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.service.NkdSnapshotService;
import com.dia.ismdtoolbackend.service.snapshot.OwnerChangeSet;
import com.dia.ismdtoolbackend.testsupport.fixtures.Texts;
import com.dia.ismdtoolbackend.utility.editor.ConceptEditor;
import com.dia.ismdtoolbackend.utility.editor.ConceptEditor.EditResult;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import static com.dia.ismdtoolbackend.service.impl.ConceptServiceTestData.CONCEPT_ID;
import static com.dia.ismdtoolbackend.service.impl.ConceptServiceTestData.CONCEPT_IRI;
import static com.dia.ismdtoolbackend.service.impl.ConceptServiceTestData.GRAPH_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConceptEditOperationTest {

    private static final String NKD_SUPERCLASS = "https://slovník.gov.cz/agendový/104/pojem/nadrazena";

    private ConceptMetadataRepository conceptMetadataRepository;
    private JenaTDB2Repository jenaTDB2Repository;
    private MetadataTouchService metadataTouchService;
    private ConceptEditor conceptEditor;
    private NkdSparqlClient nkdSparqlClient;
    private NkdSnapshotService nkdSnapshotService;
    private OutboxWriter outboxWriter;
    private OutboxRelayTrigger outboxRelayTrigger;
    private OfnWriteGate ofnWriteGate;
    private ConceptEditOperation operation;

    @Captor private ArgumentCaptor<Predicate<String>> iriTakenCaptor;
    @Captor private ArgumentCaptor<Set<Statement>> addedCaptor;
    @Captor private ArgumentCaptor<ConceptMetadataEntity> savedCaptor;

    private ConceptMetadataEntity concept;
    private Model graph;
    private Resource conceptResource;
    private ConceptEditModel editModel;

    @BeforeEach
    void setUp() {
        // The editor is a mock, so the gate is too; OfnSafetyNet.RealGate runs the real one.
        ofnWriteGate = mock(OfnWriteGate.class);
        ConceptServiceFixture fixture = new ConceptServiceFixture().ofnWriteGate(ofnWriteGate);
        conceptMetadataRepository = fixture.conceptMetadataRepository();
        jenaTDB2Repository = fixture.jenaTDB2Repository();
        metadataTouchService = fixture.metadataTouchService();
        conceptEditor = fixture.conceptEditor();
        nkdSparqlClient = fixture.nkdSparqlClient();
        nkdSnapshotService = fixture.nkdSnapshotService();
        outboxWriter = fixture.outboxWriter();
        outboxRelayTrigger = fixture.outboxRelayTrigger();
        // The link reconciler is real; a graph without NKD link triples makes it a no-op.
        operation = fixture.buildEditOperation();

        concept = ConceptServiceTestData.concept();
        graph = ConceptServiceTestData.graphWithConcept();
        conceptResource = graph.getResource(CONCEPT_IRI);
        editModel = ConceptServiceTestData.editModel();
    }

    /** The concept row and its graph load, and the editor answers {@code result}. */
    private void givenEditorReturns(EditResult result) {
        givenEditableConcept();
        when(conceptEditor.editConcept(eq(CONCEPT_IRI), eq(editModel), any(Model.class), eq(GRAPH_NAME), any()))
                .thenReturn(result);
    }

    private void givenEditableConcept() {
        when(conceptMetadataRepository.findWithLockById(CONCEPT_ID)).thenReturn(Optional.of(concept));
        when(jenaTDB2Repository.fetchGraph(GRAPH_NAME)).thenReturn(graph);
    }

    /** Runs an edit the editor answers with {@code result} and whose save succeeds. */
    private ConceptMetadataModel editCommitting(EditResult result) {
        givenEditorReturns(result);
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(concept);
        return operation.editConcept(CONCEPT_ID, editModel);
    }

    private static EditResult unchanged() {
        return new EditResult(CONCEPT_IRI, false, Set.of(), Set.of());
    }

    private Statement prefLabel(String value) {
        return graph.createStatement(conceptResource, SKOS.prefLabel, value);
    }

    @Nested
    class Outbox {

        @Test
        void enqueuesTheEditorChangeSetsInsteadOfPuttingTheGraph() {
            Statement added = prefLabel("New");

            editCommitting(new EditResult(CONCEPT_IRI, false, Set.of(), Set.of(added)));

            verify(outboxWriter).enqueueUpsert(eq(GRAPH_NAME), eq(CONCEPT_IRI), eq(Set.of()), addedCaptor.capture());
            assertThat(addedCaptor.getValue()).containsExactly(added);
            verify(outboxRelayTrigger).nudgeAfterCommit();
            verify(jenaTDB2Repository, never()).putOntologyModel(anyString(), any());
        }

        /** The pre-edit IRI keys the row, so a rename shares an aggregate with the concept's create. */
        @Test
        void keysARenameOnThePreEditIri() {
            String newIri = "http://example.org/pojem/renamed-concept";

            editCommitting(new EditResult(newIri, true, Set.of(), Set.of()));

            verify(outboxWriter).enqueueUpsert(eq(GRAPH_NAME), eq(CONCEPT_IRI), anySet(), anySet());
            verify(outboxWriter, never()).enqueueUpsert(anyString(), eq(newIri), anySet(), anySet());
        }

        @Test
        void addsTheNkdSnapshotDeltaToTheEnqueuedChangeSet() {
            graph.add(conceptResource, RDFS.subClassOf, graph.createResource(NKD_SUPERCLASS));
            Statement copyTriple = graph.createStatement(
                    graph.createResource(NKD_SUPERCLASS), RDFS.label, graph.createLiteral("NKD copy"));
            doAnswer(inv -> {
                OwnerChangeSet changeSet = inv.getArgument(3);
                changeSet.toAdd.add(copyTriple);
                return null;
            }).when(nkdSnapshotService).createOrRefreshSnapshot(any(), eq(NKD_SUPERCLASS), anyString(), any());

            editCommitting(unchanged());

            verify(outboxWriter).enqueueUpsert(eq(GRAPH_NAME), eq(CONCEPT_IRI), anySet(), addedCaptor.capture());
            assertThat(addedCaptor.getValue()).contains(copyTriple);
        }

        @Test
        void enqueuesAndSavesNothingWhenTheLinkReconcileRejects() {
            graph.add(conceptResource, RDFS.domain, graph.createResource(NKD_SUPERCLASS));
            givenEditorReturns(unchanged());
            when(nkdSparqlClient.getPublishedResourcesList(anyList())).thenReturn(List.of(NKD_SUPERCLASS));

            assertThatThrownBy(() -> operation.editConcept(CONCEPT_ID, editModel))
                    .isInstanceOf(OntologyValidationException.class);

            verify(outboxWriter, never()).enqueueUpsert(anyString(), anyString(), anySet(), anySet());
            verify(conceptMetadataRepository, never()).save(any());
        }
    }

    /** {@code updatedAt} means "the concept last changed"; the diagram stale-base guard reads it. */
    @Nested
    class UpdatedAt {

        @Test
        void anRdfOnlyChangeTouchesTheConceptAndOntology() {
            concept.setUpdatedAt(LocalDateTime.now().minusDays(1));

            editCommitting(new EditResult(CONCEPT_IRI, false, Set.of(), Set.of(prefLabel("Nová definice"))));

            verify(metadataTouchService).touchConceptAndOntology(concept);
        }

        @Test
        void aNoOpEditLeavesItUntouched() {
            LocalDateTime before = LocalDateTime.now().minusDays(1);
            concept.setUpdatedAt(before);

            editCommitting(unchanged());

            verify(metadataTouchService, never()).touchConceptAndOntology(any());
            assertThat(concept.getUpdatedAt()).isEqualTo(before);
        }

        @Test
        void aFailedEditNeverSavesOrTouches() {
            LocalDateTime before = LocalDateTime.now().minusDays(1);
            concept.setUpdatedAt(before);
            givenEditableConcept();
            when(conceptEditor.editConcept(eq(CONCEPT_IRI), eq(editModel), any(Model.class), eq(GRAPH_NAME), any()))
                    .thenThrow(new ConceptValidationException("neplatný vstup"));

            assertThatThrownBy(() -> operation.editConcept(CONCEPT_ID, editModel))
                    .isInstanceOf(ConceptValidationException.class);

            verify(conceptMetadataRepository, never()).save(any(ConceptMetadataEntity.class));
            verify(metadataTouchService, never()).touchConceptAndOntology(any());
            assertThat(concept.getUpdatedAt()).isEqualTo(before);
        }
    }

    @Nested
    class Metadata {

        @Test
        void keepsTheIriWhenTheEditDoesNotRenameIt() {
            ConceptMetadataModel result = editCommitting(unchanged());

            assertThat(result.getConceptIri()).isEqualTo(CONCEPT_IRI);
            verify(outboxWriter).enqueueUpsert(eq(GRAPH_NAME), eq(CONCEPT_IRI), anySet(), anySet());
        }

        @Test
        void savesTheNewIriOfARename() {
            String newIri = "http://example.org/pojem/new-concept";

            ConceptMetadataModel result = editCommitting(new EditResult(newIri, true, Set.of(), Set.of()));

            assertThat(result.getConceptIri()).isEqualTo(newIri);
            verify(conceptMetadataRepository).save(savedCaptor.capture());
            assertThat(savedCaptor.getValue().getConceptIri()).isEqualTo(newIri);
        }

        @Test
        void savesTheEditedName() {
            editModel.setNameModel(Texts.name("cs", "Updated Concept Name"));

            editCommitting(unchanged());

            verify(conceptMetadataRepository).save(savedCaptor.capture());
            assertThat(savedCaptor.getValue().getConceptName()).isEqualTo("Updated Concept Name");
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void savesTheEditedInTezaurus(boolean inTezaurus) {
            concept.setInTezaurus(!inTezaurus);
            editModel.setInTezaurus(inTezaurus);

            editCommitting(unchanged());

            verify(conceptMetadataRepository).save(savedCaptor.capture());
            assertThat(savedCaptor.getValue().getInTezaurus()).isEqualTo(inTezaurus);
        }

        @Test
        void stripsTrailingSlashFromIrisBeforeEditing() {
            editModel.setExactMatch(List.of("https://example.org/pojem/osoba/"));
            List<List<String>> seenByEditor = new ArrayList<>();
            givenEditableConcept();
            when(conceptEditor.editConcept(eq(CONCEPT_IRI), eq(editModel), any(Model.class), eq(GRAPH_NAME), any()))
                    .thenAnswer(inv -> {
                        seenByEditor.add(List.copyOf(inv.<ConceptEditModel>getArgument(1).getExactMatch()));
                        return unchanged();
                    });
            when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(concept);

            operation.editConcept(CONCEPT_ID, editModel);

            assertThat(seenByEditor).containsExactly(List.of("https://example.org/pojem/osoba"));
        }

        /** Re-saving a concept under its own IRI is not a collision; another concept's IRI is. */
        @Test
        void iriUniquenessExcludesTheEditedConcept() {
            editCommitting(unchanged());
            verify(conceptEditor).editConcept(anyString(), any(), any(Model.class), anyString(), iriTakenCaptor.capture());
            Predicate<String> iriTaken = iriTakenCaptor.getValue();

            when(conceptMetadataRepository.findByConceptIri("http://example.org/pojem/x"))
                    .thenReturn(Optional.of(concept));
            ConceptMetadataEntity other = new ConceptMetadataEntity();
            other.setId(CONCEPT_ID + 1);
            when(conceptMetadataRepository.findByConceptIri("http://example.org/pojem/y"))
                    .thenReturn(Optional.of(other));
            when(conceptMetadataRepository.findByConceptIri("http://example.org/pojem/z"))
                    .thenReturn(Optional.empty());

            assertThat(iriTaken.test("http://example.org/pojem/x")).isFalse();
            assertThat(iriTaken.test("http://example.org/pojem/y")).isTrue();
            assertThat(iriTaken.test("http://example.org/pojem/z")).isFalse();
        }
    }

    @Nested
    class Loading {

        /** The row lock serializes concurrent edits, so their outbox rows cannot interleave. */
        @Test
        void takesThePessimisticRowLock() {
            editCommitting(unchanged());

            verify(conceptMetadataRepository).findWithLockById(CONCEPT_ID);
            verify(conceptMetadataRepository, never()).findById(CONCEPT_ID);
        }

        @Test
        void rejectsAnUnknownConcept() {
            when(conceptMetadataRepository.findWithLockById(CONCEPT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> operation.editConcept(CONCEPT_ID, editModel))
                    .isInstanceOf(OntologyException.class)
                    .hasMessageContaining("nebyla nalezena");
            verify(jenaTDB2Repository, never()).fetchGraph(anyString());
        }

        @Test
        void rejectsAnEmptyGraph() {
            when(conceptMetadataRepository.findWithLockById(CONCEPT_ID)).thenReturn(Optional.of(concept));
            when(jenaTDB2Repository.fetchGraph(GRAPH_NAME)).thenReturn(ModelFactory.createDefaultModel());

            assertThatThrownBy(() -> operation.editConcept(CONCEPT_ID, editModel))
                    .isInstanceOf(OntologyException.class)
                    .hasMessageContaining("prázdný");
            verify(conceptEditor, never()).editConcept(anyString(), any(), any(), anyString(), any());
        }

        @Test
        void rejectsAGraphWithoutTheConcept() {
            Model otherGraph = ModelFactory.createDefaultModel();
            otherGraph.add(otherGraph.createResource("http://other.org/resource"), RDFS.label, "value");
            when(conceptMetadataRepository.findWithLockById(CONCEPT_ID)).thenReturn(Optional.of(concept));
            when(jenaTDB2Repository.fetchGraph(GRAPH_NAME)).thenReturn(otherGraph);

            assertThatThrownBy(() -> operation.editConcept(CONCEPT_ID, editModel))
                    .isInstanceOf(OntologyException.class)
                    .hasMessageContaining("nebyl nalezen");
            verify(conceptEditor, never()).editConcept(anyString(), any(), any(), anyString(), any());
        }
    }

    @Nested
    class OfnSafetyNet {

        /** The editor has applied its own delta to the fetched model; the gate sees the NKD delta too. */
        @Test
        void checksTheConceptAsItWillBeStoredBeforeQueueing() {
            graph.add(conceptResource, RDFS.subClassOf, graph.createResource(NKD_SUPERCLASS));
            Statement copyTriple = graph.createStatement(
                    graph.createResource(NKD_SUPERCLASS), RDFS.label, graph.createLiteral("NKD copy"));
            doAnswer(inv -> {
                OwnerChangeSet changeSet = inv.getArgument(3);
                changeSet.toAdd.add(copyTriple);
                return null;
            }).when(nkdSnapshotService).createOrRefreshSnapshot(any(), eq(NKD_SUPERCLASS), anyString(), any());
            AtomicBoolean gateSawTheNkdDelta = new AtomicBoolean();
            doAnswer(inv -> {
                Model checked = inv.getArgument(0);
                gateSawTheNkdDelta.set(checked.contains(copyTriple));
                verifyNoInteractions(outboxWriter);
                return null;
            }).when(ofnWriteGate).requireWritableConcepts(any(Model.class), eq(GRAPH_NAME), eq(List.of(CONCEPT_IRI)));

            editCommitting(unchanged());

            assertThat(gateSawTheNkdDelta).isTrue();
            assertThat(graph.contains(copyTriple)).as("the fetched model is left as the editor made it").isFalse();
            verify(outboxWriter).enqueueUpsert(eq(GRAPH_NAME), eq(CONCEPT_IRI), anySet(), anySet());
        }

        @Test
        void checksTheRenamedIri() {
            String newIri = "http://example.org/pojem/renamed-concept";

            editCommitting(new EditResult(newIri, true, Set.of(), Set.of()));

            verify(ofnWriteGate).requireWritableConcepts(any(Model.class), eq(GRAPH_NAME), eq(List.of(newIri)));
        }

        @Test
        void aRefusedEditIsNeitherQueuedNorSaved() {
            givenEditorReturns(unchanged());
            OfnConformanceException refusal = new OfnConformanceException(OfnConformanceException.Context.WRITE,
                    GRAPH_NAME, List.of(new ConformanceIssue(ConformanceIssue.Kind.CONCEPT_EXCLUDED, CONCEPT_IRI,
                    "pojmy[0]", "no OWL kind")));
            doThrow(refusal).when(ofnWriteGate).requireWritableConcepts(any(Model.class), anyString(), anyCollection());

            assertThatThrownBy(() -> operation.editConcept(CONCEPT_ID, editModel)).isSameAs(refusal);
            verifyNoInteractions(outboxWriter, outboxRelayTrigger);
            verify(conceptMetadataRepository, never()).save(any());
        }

        /** The real gate on an OFN-shaped concept: the delta decides. */
        @Nested
        class RealGate {

            private static final String OWNED_GRAPH = "https://slovník.gov.cz/test-brana-uprav";
            private static final String OWNED_IRI = OWNED_GRAPH + "/pojem/osoba";

            private Resource owned;

            @BeforeEach
            void useTheRealGateOnAnOfnConcept() {
                ConceptServiceFixture fixture = new ConceptServiceFixture()
                        .conceptMetadataRepository(conceptMetadataRepository)
                        .jenaTDB2Repository(jenaTDB2Repository)
                        .conceptEditor(conceptEditor)
                        .outboxWriter(outboxWriter)
                        .outboxRelayTrigger(outboxRelayTrigger);
                operation = fixture.buildEditOperation();
                concept.setConceptIri(OWNED_IRI);
                concept.setGraphName(OWNED_GRAPH);
                graph = ModelFactory.createDefaultModel();
                owned = graph.createResource(OWNED_IRI)
                        .addProperty(RDF.type, SKOS.Concept)
                        .addProperty(RDF.type, graph.createResource(OfnIris.POJEM))
                        .addProperty(RDF.type, OWL2.Class)
                        .addProperty(RDF.type, graph.createResource(OfnIris.MARKER_TRIDA))
                        .addProperty(SKOS.inScheme, graph.createResource(OWNED_GRAPH))
                        .addProperty(SKOS.prefLabel, "Osoba", "cs");
                when(conceptMetadataRepository.findWithLockById(CONCEPT_ID)).thenReturn(Optional.of(concept));
                when(jenaTDB2Repository.fetchGraph(OWNED_GRAPH)).thenReturn(graph);
            }

            private void givenTheEditorAdds(Statement added) {
                when(conceptEditor.editConcept(eq(OWNED_IRI), eq(editModel), any(Model.class), eq(OWNED_GRAPH), any()))
                        .thenReturn(new EditResult(OWNED_IRI, false, Set.of(), Set.of(added)));
            }

            @Test
            void passesAnOfnDelta() {
                givenTheEditorAdds(graph.createStatement(owned, SKOS.definition, graph.createLiteral("Definice", "cs")));
                when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(concept);

                operation.editConcept(CONCEPT_ID, editModel);

                verify(outboxWriter).enqueueUpsert(eq(OWNED_GRAPH), eq(OWNED_IRI), anySet(), anySet());
            }

            @Test
            void refusesADeltaThatWouldStoreATextWithoutALanguage() {
                givenTheEditorAdds(graph.createStatement(owned, SKOS.definition, "bez jazyka"));

                assertThatThrownBy(() -> operation.editConcept(CONCEPT_ID, editModel))
                        .isInstanceOf(OfnConformanceException.class);
                verifyNoInteractions(outboxWriter);
                verify(conceptMetadataRepository, never()).save(any());
            }

            @Test
            void refusesADeltaThatWouldGiveTheConceptASecondOwlKind() {
                givenTheEditorAdds(graph.createStatement(owned, RDF.type, OWL2.ObjectProperty));

                assertThatThrownBy(() -> operation.editConcept(CONCEPT_ID, editModel))
                        .isInstanceOf(OfnConformanceException.class);
                verifyNoInteractions(outboxWriter);
            }
        }
    }

    @Nested
    class Failures {

        @Test
        void wrapsAnEditorFailure() {
            givenEditableConcept();
            when(conceptEditor.editConcept(eq(CONCEPT_IRI), eq(editModel), any(Model.class), eq(GRAPH_NAME), any()))
                    .thenThrow(new RuntimeException("Editor error"));

            assertThatThrownBy(() -> operation.editConcept(CONCEPT_ID, editModel))
                    .isInstanceOf(OntologyException.class)
                    .hasMessageContaining("Nepodařilo se upravit pojem");
            verifyNoInteractions(outboxWriter);
        }

        /** Invalid input stays a ConceptValidationException, which maps to 400 rather than 500. */
        @Test
        void passesAValidationFailureThroughUnwrapped() {
            givenEditableConcept();
            when(conceptEditor.editConcept(eq(CONCEPT_IRI), eq(editModel), any(Model.class), eq(GRAPH_NAME), any()))
                    .thenThrow(new ConceptValidationException("Neplatné hodnoty v úpravě pojmu: exactMatch"));

            assertThatThrownBy(() -> operation.editConcept(CONCEPT_ID, editModel))
                    .isExactlyInstanceOf(ConceptValidationException.class)
                    .hasMessageContaining("Neplatné hodnoty");
            verifyNoInteractions(outboxWriter);
            verify(conceptMetadataRepository, never()).save(any());
        }

        @Test
        void wrapsASaveFailure() {
            givenEditorReturns(unchanged());
            when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class)))
                    .thenThrow(new RuntimeException("DB error"));

            assertThatThrownBy(() -> operation.editConcept(CONCEPT_ID, editModel))
                    .isInstanceOf(OntologyException.class)
                    .hasMessageContaining("Nepodařilo se aktualizovat metadata pojmu");
        }
    }
}
