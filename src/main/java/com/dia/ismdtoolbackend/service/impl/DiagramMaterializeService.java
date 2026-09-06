package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.MaterializeResultDto;
import com.dia.ismdtoolbackend.entity.DiagramPendingEditEntity;
import com.dia.ismdtoolbackend.enums.DiagramOp;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import com.dia.ismdtoolbackend.service.impl.DiagramChangeApplier.CascadeConflictException;
import com.dia.ismdtoolbackend.service.impl.DiagramChangeApplier.ForeignConceptException;
import com.dia.ismdtoolbackend.service.impl.DiagramChangeApplier.Outcome;
import com.dia.ismdtoolbackend.service.impl.DiagramChangeApplier.StaleBaseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Materialize (Převzít) orchestrator. Resolves the staged work-list up front, then applies each change
 * through {@link DiagramChangeApplier}, which runs each in its OWN transaction. Deliberately not
 * transactional itself, so a failing change rolls back only itself and earlier successes stay committed.
 * See {@code docs/DIAGRAM_LAYER.md}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagramMaterializeService {

    private final DiagramChangeApplier changeApplier;
    private final DiagramPendingEditRepository pendingEditRepository;

    /** Apply every staged edit on the diagram, each in its own transaction; aggregate the outcomes. */
    public MaterializeResultDto materialize(Long diagramId, Long ontologyId) {
        List<MaterializeResultDto.Materialized> materialized = new ArrayList<>();
        List<MaterializeResultDto.Failed> failed = new ArrayList<>();
        List<MaterializeResultDto.SkippedStale> skippedStale = new ArrayList<>();

        for (String conceptIri : orderedWorkList(diagramId)) {
            try {
                Outcome outcome = changeApplier.applyChange(diagramId, ontologyId, conceptIri);
                if (outcome.kind() == Outcome.Kind.SKIPPED_STALE) {
                    skippedStale.add(new MaterializeResultDto.SkippedStale(conceptIri));
                } else {
                    materialized.add(new MaterializeResultDto.Materialized(conceptIri, outcome.op()));
                }
            } catch (StaleBaseException e) {
                failed.add(fail(diagramId, conceptIri, "STALE_BASE", e.getMessage(), 409));
            } catch (CascadeConflictException e) {
                failed.add(fail(diagramId, conceptIri, "CASCADE_CONFLICT", e.getMessage(), 409));
            } catch (ForeignConceptException e) {
                failed.add(fail(diagramId, conceptIri, "FOREIGN_CONCEPT", e.getMessage(), 400));
            } catch (ConceptValidationException | OntologyValidationException e) {
                failed.add(fail(diagramId, conceptIri, "VALIDATION", e.getMessage(), 400));
            } catch (AccessDeniedException e) {
                // The caller owns the ontology but not this concept; a per-change 403, not a server error.
                failed.add(fail(diagramId, conceptIri, "FORBIDDEN",
                        "Nemáte oprávnění upravit tento pojem.", 403));
            } catch (RuntimeException e) {
                log.error("Materialize failed for concept {}", conceptIri, e);
                failed.add(fail(diagramId, conceptIri, "ERROR", "Nastala neočekávaná chyba.", 500));
            }
        }

        return new MaterializeResultDto(materialized, failed, skippedStale);
    }

    /**
     * The staged work-list, ordered so every {@link DiagramOp#CONVERT_TO_HIERARCHY} applies LAST — it bumps
     * its target class's {@code updatedAt}, which would otherwise falsely stale that class's own edit.
     */
    private List<String> orderedWorkList(Long diagramId) {
        List<String> iris = new ArrayList<>(pendingEditRepository.findStagedConceptIris(diagramId));
        iris.sort(java.util.Comparator.comparingInt(
                iri -> isConvertToHierarchy(diagramId, iri) ? 1 : 0));
        return iris;
    }

    /** Classify a staged op without applying it (own read; the edit may have raced away → false). */
    private boolean isConvertToHierarchy(Long diagramId, String conceptIri) {
        DiagramOp op = classifyStaged(diagramId, conceptIri);
        return op == DiagramOp.CONVERT_TO_HIERARCHY;
    }

    /** Build a failure entry; the change rolled back, so the still-staged edit is re-read for its op. */
    private MaterializeResultDto.Failed fail(Long diagramId, String conceptIri, String error,
                                             String message, int status) {
        return new MaterializeResultDto.Failed(
                conceptIri, classifyStaged(diagramId, conceptIri), error, message, status);
    }

    /** The op a concept's staged edit would apply, or null when nothing is staged for it. */
    private DiagramOp classifyStaged(Long diagramId, String conceptIri) {
        return pendingEditRepository.findByDiagramIdAndConceptIri(diagramId, conceptIri)
                .map(DiagramPendingEditEntity::getPendingEdit)
                .map(changeApplier::classify)
                .orElse(null);
    }
}
