package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.MaterializeResultDto;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.enums.DiagramOp;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.repository.DiagramNodeRepository;
import com.dia.ismdtoolbackend.service.impl.DiagramChangeApplier.CascadeConflictException;
import com.dia.ismdtoolbackend.service.impl.DiagramChangeApplier.Outcome;
import com.dia.ismdtoolbackend.service.impl.DiagramChangeApplier.StaleBaseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Materialize (Převzít) orchestrator. Resolves the staged work-list up front, then applies each change
 * through {@link DiagramChangeApplier}, which runs each in its OWN transaction. This method is intentionally
 * NOT transactional: a failing change rolls back only itself, so earlier successes (their RDF edits, outbox
 * rows, and overlay-clears) stay committed — the per-change partial-ok guarantee. See
 * {@code docs/DIAGRAM_LAYER.md}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagramMaterializeService {

    private final DiagramChangeApplier changeApplier;
    private final DiagramNodeRepository diagramNodeRepository;

    /** Apply every staged overlay on the diagram, each in its own transaction; aggregate the outcomes. */
    public MaterializeResultDto materialize(Long diagramId) {
        List<MaterializeResultDto.Materialized> materialized = new ArrayList<>();
        List<MaterializeResultDto.Failed> failed = new ArrayList<>();
        List<MaterializeResultDto.SkippedStale> skippedStale = new ArrayList<>();

        for (Long nodeId : orderedWorkList(diagramId)) {
            try {
                Outcome outcome = changeApplier.applyChange(nodeId);
                if (outcome.kind() == Outcome.Kind.SKIPPED_STALE) {
                    skippedStale.add(new MaterializeResultDto.SkippedStale(nodeId, iriOf(nodeId)));
                } else {
                    materialized.add(new MaterializeResultDto.Materialized(nodeId, iriOf(nodeId), outcome.op()));
                }
            } catch (StaleBaseException e) {
                failed.add(fail(nodeId, "STALE_BASE", e.getMessage(), 409));
            } catch (CascadeConflictException e) {
                failed.add(fail(nodeId, "CASCADE_CONFLICT", e.getMessage(), 409));
            } catch (ConceptValidationException | OntologyValidationException e) {
                failed.add(fail(nodeId, "VALIDATION", e.getMessage(), 400));
            } catch (RuntimeException e) {
                log.error("Materialize failed for node {}", nodeId, e);
                failed.add(fail(nodeId, "ERROR", "Nastala neočekávaná chyba.", 500));
            }
        }

        return new MaterializeResultDto(materialized, failed, skippedStale);
    }

    /**
     * The staged work-list, ordered so every {@link DiagramOp#CONVERT_TO_HIERARCHY} applies LAST.
     */
    private List<Long> orderedWorkList(Long diagramId) {
        List<Long> ids = new ArrayList<>(diagramNodeRepository.findStagedNodeIds(diagramId));
        ids.sort(java.util.Comparator.comparingInt(id -> isConvertToHierarchy(id) ? 1 : 0));
        return ids;
    }

    /** Classify a staged node's op without applying it (own read; overlay may have raced away → false). */
    private boolean isConvertToHierarchy(Long nodeId) {
        DiagramNodeEntity node = diagramNodeRepository.findById(nodeId).orElse(null);
        if (node == null || node.getPendingEdit() == null) {
            return false;
        }
        return changeApplier.classify(node.getPendingEdit()) == DiagramOp.CONVERT_TO_HIERARCHY;
    }

    /**
     * Build a failure entry. The change rolled back, so the node (with its still-staged overlay) is re-read
     * to recover the concept IRI and re-derive the reported op.
     */
    private MaterializeResultDto.Failed fail(Long nodeId, String error, String message, int status) {
        DiagramNodeEntity node = diagramNodeRepository.findById(nodeId).orElse(null);
        String iri = node != null ? node.getConceptIri() : null;
        DiagramOp op = (node != null && node.getPendingEdit() != null)
                ? changeApplier.classify(node.getPendingEdit())
                : null;
        return new MaterializeResultDto.Failed(nodeId, iri, op, error, message, status);
    }

    private String iriOf(Long nodeId) {
        return diagramNodeRepository.findById(nodeId)
                .map(DiagramNodeEntity::getConceptIri)
                .orElse(null);
    }
}
