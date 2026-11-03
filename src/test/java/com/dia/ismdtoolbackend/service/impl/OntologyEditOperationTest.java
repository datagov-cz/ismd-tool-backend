package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.OfnConformanceException;
import com.dia.ismdtoolbackend.models.OntologyEditModel;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.ofn.ConformanceIssue;
import com.dia.ismdtoolbackend.ofn.OfnIris;
import com.dia.ismdtoolbackend.ofn.OfnWriteGate;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.utility.editor.OntologyEditor;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.GRAPH;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.ONTOLOGY_ID;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.concept;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.nonEmptyModel;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.ontologyEntity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Editing a vocabulary in place, and moving its graph and concept IRIs when its IRI changes. */
@ExtendWith(MockitoExtension.class)
class OntologyEditOperationTest {

    private static final String NEW_GRAPH = "http://example.org/new-ontology";

    private OntologyMetadataRepository ontologyMetadataRepository;
    private ConceptMetadataRepository conceptMetadataRepository;
    private JenaTDB2Repository jenaTDB2Repository;
    private OntologyEditor ontologyEditor;
    private MetadataTouchService metadataTouchService;

    private OfnWriteGate ofnWriteGate;
    private OntologyEditOperation operation;

    @Captor private ArgumentCaptor<OntologyMetadataEntity> ontologyCaptor;
    @Captor private ArgumentCaptor<Iterable<ConceptMetadataEntity>> conceptsCaptor;

    private final OntologyEditModel editModel = new OntologyEditModel();
    private Model graph;

    @BeforeEach
    void setUp() {
        // The editor is a mock, so the gate is too; OfnSafetyNet.RealGate runs the real one.
        ofnWriteGate = mock(OfnWriteGate.class);
        OntologyServiceFixture fixture = new OntologyServiceFixture().ofnWriteGate(ofnWriteGate);
        ontologyMetadataRepository = fixture.ontologyMetadataRepository();
        conceptMetadataRepository = fixture.conceptMetadataRepository();
        jenaTDB2Repository = fixture.jenaTDB2Repository();
        ontologyEditor = fixture.ontologyEditor();
        metadataTouchService = fixture.metadataTouchService();
        operation = fixture.editOperation();
        graph = nonEmptyModel();
    }

    /** Stubs a stored vocabulary whose editor reports {@code newIri}. */
    private void givenEditResult(String newIri, boolean iriChanged) {
        when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.of(ontologyEntity()));
        when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(graph);
        when(ontologyEditor.editOntology(eq(editModel), any(Model.class), anyString(), anyString()))
                .thenReturn(new OntologyEditor.EditResult(newIri, iriChanged));
    }

    @Test
    void sameIri_savesTheGraphInPlace_andTouchesUpdatedAt() {
        givenEditResult(GRAPH, false);

        OntologyMetadataModel result = operation.editOntology(ONTOLOGY_ID, editModel);

        assertThat(result.getGraphName()).isEqualTo(GRAPH);
        verify(jenaTDB2Repository).saveOntologyModel(GRAPH, graph);
        verify(jenaTDB2Repository, never()).deleteGraph(anyString());
        verify(ontologyMetadataRepository, never()).save(any());
        verify(metadataTouchService).touchOntology(any(OntologyMetadataEntity.class));
    }

    @Nested
    class IriChange {

        @Test
        void movesTheGraphAndRewritesTheMetadataRow() {
            givenEditResult(NEW_GRAPH, true);
            when(conceptMetadataRepository.findByOntologyMetadataId(ONTOLOGY_ID)).thenReturn(List.of());
            when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).then(returnsFirstArg());

            OntologyMetadataModel result = operation.editOntology(ONTOLOGY_ID, editModel);

            assertThat(result.getGraphName()).isEqualTo(NEW_GRAPH);
            verify(jenaTDB2Repository).saveOntologyModel(NEW_GRAPH, graph);
            verify(jenaTDB2Repository).deleteGraph(GRAPH);
            verify(ontologyMetadataRepository).save(ontologyCaptor.capture());
            assertThat(ontologyCaptor.getValue().getGraphName()).isEqualTo(NEW_GRAPH);
            verify(conceptMetadataRepository, never()).saveAll(any());
        }

        @Test
        void rewritesEveryConceptIriIntoTheNewNamespace() {
            givenEditResult(NEW_GRAPH, true);
            when(conceptMetadataRepository.findByOntologyMetadataId(ONTOLOGY_ID)).thenReturn(List.of(
                    concept(GRAPH + "/pojem/concept1", "concept1"),
                    concept(GRAPH + "/pojem/concept2", "concept2")));
            when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).then(returnsFirstArg());

            OntologyMetadataModel result = operation.editOntology(ONTOLOGY_ID, editModel);

            assertThat(result.getGraphName()).isEqualTo(NEW_GRAPH);
            verify(conceptMetadataRepository).saveAll(conceptsCaptor.capture());
            assertThat(conceptsCaptor.getValue()).hasSize(2).allSatisfy(concept -> {
                assertThat(concept.getGraphName()).isEqualTo(NEW_GRAPH);
                assertThat(concept.getConceptIri()).startsWith(NEW_GRAPH);
            });
        }

        @Test
        void failedSaveOfTheNewGraph_throws() {
            givenEditResult(NEW_GRAPH, true);
            doThrow(new RuntimeException("Save error"))
                    .when(jenaTDB2Repository).saveOntologyModel(eq(NEW_GRAPH), any(Model.class));

            assertThatThrownBy(() -> operation.editOntology(ONTOLOGY_ID, editModel))
                    .isInstanceOf(OntologyException.class)
                    .hasMessageContaining("Nepodařilo se uložit změny");
            verify(ontologyMetadataRepository, never()).save(any());
        }
    }

    @Nested
    class OfnSafetyNet {

        @Test
        void checksTheEditedVocabularyBeforeSavingIt() {
            givenEditResult(GRAPH, false);

            operation.editOntology(ONTOLOGY_ID, editModel);

            var order = org.mockito.Mockito.inOrder(ofnWriteGate, jenaTDB2Repository);
            order.verify(ofnWriteGate).requireWritableVocabulary(graph, GRAPH);
            order.verify(jenaTDB2Repository).saveOntologyModel(GRAPH, graph);
        }

        @Test
        void checksARenamedVocabularyUnderItsNewIri() {
            givenEditResult(NEW_GRAPH, true);
            when(conceptMetadataRepository.findByOntologyMetadataId(ONTOLOGY_ID)).thenReturn(List.of());
            when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).then(returnsFirstArg());

            operation.editOntology(ONTOLOGY_ID, editModel);

            verify(ofnWriteGate).requireWritableVocabulary(graph, NEW_GRAPH);
        }

        @Test
        void aRefusedEditSavesAndMovesNothing() {
            givenEditResult(NEW_GRAPH, true);
            OfnConformanceException refusal = new OfnConformanceException(OfnConformanceException.Context.WRITE,
                    NEW_GRAPH, List.of(new ConformanceIssue(ConformanceIssue.Kind.VOCABULARY_INVALID, NEW_GRAPH,
                    "", "typ")));
            doThrow(refusal).when(ofnWriteGate).requireWritableVocabulary(any(Model.class), anyString());

            assertThatThrownBy(() -> operation.editOntology(ONTOLOGY_ID, editModel)).isSameAs(refusal);
            verify(jenaTDB2Repository, never()).saveOntologyModel(anyString(), any(Model.class));
            verify(jenaTDB2Repository, never()).deleteGraph(anyString());
            verify(ontologyMetadataRepository, never()).save(any());
            verify(metadataTouchService, never()).touchOntology(any());
        }

        /** The real gate on an OFN-shaped vocabulary: what the edit leaves in the model decides. */
        @Nested
        class RealGate {

            private Resource vocabulary;

            @BeforeEach
            void useTheRealGateOnAnOfnVocabulary() {
                operation = new OntologyServiceFixture()
                        .ontologyMetadataRepository(ontologyMetadataRepository)
                        .conceptMetadataRepository(conceptMetadataRepository)
                        .jenaTDB2Repository(jenaTDB2Repository)
                        .ontologyEditor(ontologyEditor)
                        .metadataTouchService(metadataTouchService)
                        .editOperation();
                graph = ModelFactory.createDefaultModel();
                vocabulary = graph.createResource(GRAPH)
                        .addProperty(RDF.type, graph.createResource(OfnIris.SLOVNIK))
                        .addProperty(RDF.type, graph.createResource(OfnIris.CONCEPT_SCHEME))
                        .addProperty(RDF.type, graph.createResource(OfnIris.ONTOLOGY))
                        .addProperty(SKOS.prefLabel, "Slovník", "cs");
            }

            @Test
            void savesAnOfnVocabulary() {
                givenEditResult(GRAPH, false);

                operation.editOntology(ONTOLOGY_ID, editModel);

                verify(jenaTDB2Repository).saveOntologyModel(GRAPH, graph);
            }

            @Test
            void refusesAVocabularyWithATripleOutsideOfn() {
                vocabulary.addProperty(RDFS.label, "x");
                givenEditResult(GRAPH, false);

                assertThatThrownBy(() -> operation.editOntology(ONTOLOGY_ID, editModel))
                        .isInstanceOf(OfnConformanceException.class);
                verify(jenaTDB2Repository, never()).saveOntologyModel(anyString(), any(Model.class));
            }
        }
    }

    @Nested
    class Rejected {

        @Test
        void nullRequest() {
            assertThatThrownBy(() -> operation.editOntology(ONTOLOGY_ID, null))
                    .isInstanceOf(OntologyException.class)
                    .hasMessageContaining("prázdná");
        }

        @Test
        void unknownOntology() {
            when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> operation.editOntology(ONTOLOGY_ID, editModel))
                    .isInstanceOf(OntologyException.class)
                    .hasMessageContaining("nebyl nalezen");
        }

        @Test
        void emptyGraph() {
            when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.of(ontologyEntity()));
            when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(ModelFactory.createDefaultModel());

            assertThatThrownBy(() -> operation.editOntology(ONTOLOGY_ID, editModel))
                    .isInstanceOf(OntologyException.class);
            verify(jenaTDB2Repository, never()).saveOntologyModel(anyString(), any(Model.class));
        }
    }
}
