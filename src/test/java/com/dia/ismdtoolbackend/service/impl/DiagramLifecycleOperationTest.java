package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramSummaryDto;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.DiagramNameConflictException;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiagramLifecycleOperationTest {

    private static final String SLUG = "muj-slovnik";
    private static final long ONTOLOGY_ID = 5L;
    private static final long DIAGRAM_ID = 11L;

    @Mock
    private DiagramRepository diagrams;
    @Mock
    private DiagramScopeReader scopeReader;
    @Mock
    private DiagramSnapshotReader snapshotReader;

    private DiagramLifecycleOperation operation;
    private OntologyMetadataEntity ontology;

    @BeforeEach
    void setUp() {
        operation = new DiagramLifecycleOperation(diagrams, scopeReader, snapshotReader);
        ontology = new OntologyMetadataEntity();
        ontology.setId(ONTOLOGY_ID);
        ontology.setSlug(SLUG);
        ontology.setGraphName("https://slovník.gov.cz/agendový/1/slovník");
        when(scopeReader.requireOntology(SLUG)).thenReturn(ontology);
    }

    @Nested
    class Create {

        @Test
        void storesTheTrimmedNameUnderTheOntology() {
            DiagramSnapshot snapshot = mock(DiagramSnapshot.class);
            when(diagrams.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
            when(snapshotReader.snapshot(any(), eq(ontology))).thenReturn(snapshot);

            assertThat(operation.commitNewDiagram(SLUG, "  Pohled HR  ")).isSameAs(snapshot);

            DiagramEntity saved = savedDiagram();
            assertThat(saved.getName()).isEqualTo("Pohled HR");
            assertThat(saved.getOntologyMetadata()).isSameAs(ontology);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "   "})
        void missingNameGetsTheDefault(String name) {
            when(diagrams.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

            operation.commitNewDiagram(SLUG, name);

            assertThat(savedDiagram().getName()).isEqualTo("Nový diagram");
        }

        @Test
        void takenDefaultNameIsNumbered() {
            when(diagrams.existsByOntologyMetadataIdAndName(ONTOLOGY_ID, "Nový diagram")).thenReturn(true);
            when(diagrams.existsByOntologyMetadataIdAndName(ONTOLOGY_ID, "Nový diagram 2")).thenReturn(true);
            when(diagrams.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

            operation.commitNewDiagram(SLUG, null);

            assertThat(savedDiagram().getName()).isEqualTo("Nový diagram 3");
        }

        @Test
        void everyNumberTakenFallsBackToAUniqueSuffix() {
            when(diagrams.existsByOntologyMetadataIdAndName(eq(ONTOLOGY_ID), startsWith("Nový diagram")))
                    .thenAnswer(call -> !((String) call.getArgument(1)).matches("Nový diagram [0-9a-f-]{36}"));
            when(diagrams.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

            operation.commitNewDiagram(SLUG, "");

            assertThat(savedDiagram().getName()).matches("Nový diagram [0-9a-f-]{36}");
        }

        @Test
        void takenNameIsAConflictBeforeAnythingIsSaved() {
            when(diagrams.existsByOntologyMetadataIdAndName(ONTOLOGY_ID, "Pohled")).thenReturn(true);

            assertThatThrownBy(() -> operation.commitNewDiagram(SLUG, "Pohled"))
                    .isInstanceOf(DiagramNameConflictException.class)
                    .hasMessage("Diagram s názvem \"Pohled\" v tomto slovníku již existuje.");
            verify(diagrams, never()).saveAndFlush(any());
        }

        @Test
        void lostCreateRaceIsAConflict() {
            when(diagrams.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_diagrams_ontology_name"));

            assertThatThrownBy(() -> operation.commitNewDiagram(SLUG, "Pohled"))
                    .isInstanceOf(DiagramNameConflictException.class)
                    .hasMessageContaining("\"Pohled\"");
        }
    }

    @Nested
    class Rename {

        private DiagramEntity diagram;

        @BeforeEach
        void loadDiagram() {
            diagram = new DiagramEntity();
            diagram.setId(DIAGRAM_ID);
            diagram.setName("Starý");
            when(scopeReader.requireDiagramOf(ontology, SLUG, DIAGRAM_ID)).thenReturn(diagram);
        }

        @Test
        void storesTheTrimmedNameAndReturnsItsSummary() {
            LocalDateTime updated = LocalDateTime.of(2026, 10, 21, 12, 0);
            when(diagrams.saveAndFlush(diagram)).thenAnswer(call -> {
                diagram.setUpdatedAt(updated);
                return diagram;
            });

            DiagramSummaryDto summary = operation.renameDiagram(SLUG, DIAGRAM_ID, "  Nový  ");

            assertThat(summary).isEqualTo(new DiagramSummaryDto(
                    DIAGRAM_ID, "Nový", SLUG, ontology.getGraphName(), null, 0, updated.toString()));
            verify(diagrams).existsByOntologyMetadataIdAndNameAndIdNot(ONTOLOGY_ID, "Nový", DIAGRAM_ID);
        }

        @Test
        void sameNameIsANoOp() {
            DiagramSummaryDto summary = operation.renameDiagram(SLUG, DIAGRAM_ID, " Starý ");

            assertThat(summary.name()).isEqualTo("Starý");
            assertThat(summary.updatedAt()).isNull();
            verify(diagrams, never()).saveAndFlush(any());
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "  "})
        void blankNameIsRejected(String name) {
            assertThatThrownBy(() -> operation.renameDiagram(SLUG, DIAGRAM_ID, name))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Název diagramu nesmí být prázdný.");
            verify(diagrams, never()).saveAndFlush(any());
        }

        @Test
        void nameOfAnotherDiagramIsAConflict() {
            when(diagrams.existsByOntologyMetadataIdAndNameAndIdNot(ONTOLOGY_ID, "Obsazený", DIAGRAM_ID))
                    .thenReturn(true);

            assertThatThrownBy(() -> operation.renameDiagram(SLUG, DIAGRAM_ID, "Obsazený"))
                    .isInstanceOf(DiagramNameConflictException.class);
            assertThat(diagram.getName()).isEqualTo("Starý");
        }

        @Test
        void lostRenameRaceIsAConflict() {
            when(diagrams.saveAndFlush(diagram)).thenThrow(new DataIntegrityViolationException("uq"));

            assertThatThrownBy(() -> operation.renameDiagram(SLUG, DIAGRAM_ID, "Nový"))
                    .isInstanceOf(DiagramNameConflictException.class);
        }
    }

    @Test
    void deleteRemovesTheDiagramOfThatOntology() {
        DiagramEntity diagram = new DiagramEntity();
        when(scopeReader.requireDiagramOf(ontology, SLUG, DIAGRAM_ID)).thenReturn(diagram);

        operation.deleteDiagram(SLUG, DIAGRAM_ID);

        verify(diagrams).delete(diagram);
    }

    private DiagramEntity savedDiagram() {
        ArgumentCaptor<DiagramEntity> saved = ArgumentCaptor.forClass(DiagramEntity.class);
        verify(diagrams).saveAndFlush(saved.capture());
        verify(diagrams, never()).existsByOntologyMetadataIdAndNameAndIdNot(any(), anyString(), any());
        return saved.getValue();
    }
}
