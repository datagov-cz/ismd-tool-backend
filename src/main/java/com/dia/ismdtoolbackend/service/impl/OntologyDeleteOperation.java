package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.audit.DeleteAuditWriter;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.entity.ValidationReportEntity;
import com.dia.ismdtoolbackend.models.*;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.repository.*;
import com.dia.ismdtoolbackend.service.NkdSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Deletes a vocabulary: its validation report, NKD snapshot rows, metadata and graph, and records the delete. */
@Slf4j
@Component
@RequiredArgsConstructor
class OntologyDeleteOperation {

    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final ValidationReportRepository validationReportRepository;
    private final NkdSnapshotService nkdSnapshotService;
    private final OutboxWriter outboxWriter;
    private final OutboxRelayTrigger outboxRelayTrigger;
    private final InitialGraphGuard initialGraphGuard;
    private final DiagramRepository diagramRepository;
    private final CommentRepository commentRepository;
    private final DeleteAuditWriter deleteAuditWriter;

    void deleteOntology(Long ontologyId) {
        Optional<OntologyMetadataEntity> ontologyMetadataOpt = ontologyMetadataRepository.findById(ontologyId);
        if (ontologyMetadataOpt.isEmpty()) {
            log.error("ontologyId {} not found", ontologyId);
            throw new OntologyNotFoundException("Slovník s id " + ontologyId + " nebyl nalezen.");
        }

        OntologyMetadataEntity ontology = ontologyMetadataOpt.get();
        String graphName = ontology.getGraphName();
        initialGraphGuard.requireInitialGraphApplied(graphName);

        Optional<ValidationReportEntity> validationReport =
                validationReportRepository.findByOntologyMetadataId(ontologyId);
        validationReport.ifPresent(validationReportRepository::delete);

        // NKD local-copy cascade: drop the PG snapshot rows before the ontology (and its cascade-deleted
        // concepts) go. Copies live only in Postgres, so there is nothing of theirs in the graph for
        // DELETE_GRAPH to sweep. fk_nkd_snapshot_owning_concept is not db-cascade, so keying on the owned
        // concept ids (not just graph_name, which drifts on rename) is what keeps the concept DELETE legal.
        List<Long> ownedConceptIds = conceptMetadataRepository.findByGraphName(graphName).stream()
                .map(ConceptMetadataEntity::getId)
                .toList();
        nkdSnapshotService.cascadeGraphDeletion(ownedConceptIds, graphName);

        deleteAuditWriter.ontology(ontologyId, ontology.getSlug(), graphName, ownedConceptIds.size(),
                diagramRepository.countByOntologyMetadataId(ontologyId),
                commentRepository.countByOntologyMetadataId(ontologyId));

        // Enqueue the graph deletion, committed atomically with the PG metadata delete below. The graph
        // is not read first: the delete is idempotent, so a vocabulary whose graph is already gone is
        // still removable.
        outboxWriter.enqueueDeleteGraph(graphName);
        ontologyMetadataRepository.deleteById(ontologyId);
        outboxRelayTrigger.nudgeAfterCommit();
    }
}
