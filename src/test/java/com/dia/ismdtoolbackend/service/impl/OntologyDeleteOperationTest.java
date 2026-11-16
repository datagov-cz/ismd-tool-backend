package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.ValidationReportEntity;
import com.dia.ismdtoolbackend.exception.OntologyCreationConflictException;
import com.dia.ismdtoolbackend.outbox.OutboxEntryRepository;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.repository.ValidationReportRepository;
import com.dia.ismdtoolbackend.service.NkdSnapshotService;
import org.apache.jena.ontology.OntologyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.GRAPH;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.ONTOLOGY_ID;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.concept;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.ontologyEntity;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Deleting a vocabulary: report, NKD snapshot rows, graph task and metadata, in one outbox commit. */
@ExtendWith(MockitoExtension.class)
class OntologyDeleteOperationTest {

    private OntologyMetadataRepository ontologyMetadataRepository;
    private ConceptMetadataRepository conceptMetadataRepository;
    private ValidationReportRepository validationReportRepository;
    private NkdSnapshotService nkdSnapshotService;
    private OutboxWriter outboxWriter;
    private OutboxRelayTrigger outboxRelayTrigger;
    private OutboxEntryRepository outboxRepository;

    private OntologyDeleteOperation operation;

    @BeforeEach
    void setUp() {
        OntologyServiceFixture fixture = new OntologyServiceFixture();
        ontologyMetadataRepository = fixture.ontologyMetadataRepository();
        conceptMetadataRepository = fixture.conceptMetadataRepository();
        validationReportRepository = fixture.validationReportRepository();
        nkdSnapshotService = fixture.nkdSnapshotService();
        outboxWriter = fixture.outboxWriter();
        outboxRelayTrigger = fixture.outboxRelayTrigger();
        outboxRepository = fixture.outboxRepository();
        operation = fixture.deleteOperation();
    }

    @Test
    void deletesReportSnapshotsGraphAndMetadata_thenNudgesTheRelay() {
        ValidationReportEntity report = new ValidationReportEntity();
        ConceptMetadataEntity owned = concept(GRAPH + "/pojem/a", "a");
        owned.setId(7L);
        when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.of(ontologyEntity()));
        when(validationReportRepository.findByOntologyMetadataId(ONTOLOGY_ID)).thenReturn(Optional.of(report));
        when(conceptMetadataRepository.findByGraphName(GRAPH)).thenReturn(List.of(owned));

        operation.deleteOntology(ONTOLOGY_ID);

        var order = inOrder(validationReportRepository, nkdSnapshotService, outboxWriter,
                ontologyMetadataRepository, outboxRelayTrigger);
        order.verify(validationReportRepository).delete(report);
        order.verify(nkdSnapshotService).cascadeGraphDeletion(List.of(7L), GRAPH);
        order.verify(outboxWriter).enqueueDeleteGraph(GRAPH);
        order.verify(ontologyMetadataRepository).deleteById(ONTOLOGY_ID);
        order.verify(outboxRelayTrigger).nudgeAfterCommit();
    }

    @Test
    void withoutValidationReport_stillDeletesGraphAndMetadata() {
        when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.of(ontologyEntity()));
        when(validationReportRepository.findByOntologyMetadataId(ONTOLOGY_ID)).thenReturn(Optional.empty());

        operation.deleteOntology(ONTOLOGY_ID);

        verify(validationReportRepository, never()).delete(any());
        verify(outboxWriter).enqueueDeleteGraph(GRAPH);
        verify(ontologyMetadataRepository).deleteById(ONTOLOGY_ID);
        verify(outboxRelayTrigger).nudgeAfterCommit();
    }

    @Test
    void unknownOntology_throwsAndDeletesNothing() {
        when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> operation.deleteOntology(ONTOLOGY_ID))
                .isInstanceOf(OntologyException.class)
                .hasMessageContaining("nebyl nalezen");
        verify(ontologyMetadataRepository, never()).deleteById(any());
        verifyNoInteractions(outboxWriter, nkdSnapshotService);
    }

    @Test
    void pendingInitialWrite_blocksTheDelete() {
        when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.of(ontologyEntity()));
        when(outboxRepository.existsEarlierUnappliedCreateGraph(GRAPH, Long.MAX_VALUE)).thenReturn(true);

        assertThatThrownBy(() -> operation.deleteOntology(ONTOLOGY_ID))
                .isInstanceOf(OntologyCreationConflictException.class);
        verify(ontologyMetadataRepository, never()).deleteById(any());
        verifyNoInteractions(outboxWriter, validationReportRepository);
    }
}
