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
    private final DiagramPendingEditRepository pendingEditRepository;

    /** Apply every staged edit on the ontology, each in its own transaction; aggregate the outcomes. */
    public MaterializeResultDto materialize(Long ontologyId) {
        List<MaterializeResultDto.Materialized> materialized = new ArrayList<>();
        List<MaterializeResultDto.Failed> failed = new ArrayList<>();
        List<MaterializeResultDto.SkippedStale> skippedStale = new ArrayList<>();

        for (String conceptIri : orderedWorkList(ontologyId)) {
            try {
                Outcome outcome = changeApplier.applyChange(ontologyId, conceptIri);
                if (outcome.kind() == Outcome.Kind.SKIPPED_STALE) {
                    skippedStale.add(new MaterializeResultDto.SkippedStale(conceptIri));
                } else {
                    materialized.add(new MaterializeResultDto.Materialized(conceptIri, outcome.op()));
                }
            } catch (StaleBaseException e) {
                failed.add(fail(ontologyId, conceptIri, "STALE_BASE", e.getMessage(), 409));
            } catch (CascadeConflictException e) {
                failed.add(fail(ontologyId, conceptIri, "CASCADE_CONFLICT", e.getMessage(), 409));
            } catch (ForeignConceptException e) {
                failed.add(fail(ontologyId, conceptIri, "FOREIGN_CONCEPT", e.getMessage(), 400));
            } catch (ConceptValidationException | OntologyValidationException e) {
                failed.add(fail(ontologyId, conceptIri, "VALIDATION", e.getMessage(), 400));
            } catch (RuntimeException e) {
                log.error("Materialize failed for concept {}", conceptIri, e);
                failed.add(fail(ontologyId, conceptIri, "ERROR", "Nastala neočekávaná chyba.", 500));
            }
        }

        return new MaterializeResultDto(materialized, failed, skippedStale);
    }

    /**
     * The staged work-list, ordered so every {@link DiagramOp#CONVERT_TO_HIERARCHY} applies LAST — it bumps
     * its target class's {@code updatedAt}, which would otherwise falsely stale that class's own edit.
     */
    private List<String> orderedWorkList(Long ontologyId) {
        List<String> iris = new ArrayList<>(pendingEditRepository.findStagedConceptIris(ontologyId));
        iris.sort(java.util.Comparator.comparingInt(
                iri -> isConvertToHierarchy(ontologyId, iri) ? 1 : 0));
        return iris;
    }

    /** Classify a staged op without applying it (own read; the edit may have raced away → false). */
    private boolean isConvertToHierarchy(Long ontologyId, String conceptIri) {
        DiagramOp op = classifyStaged(ontologyId, conceptIri);
        return op == DiagramOp.CONVERT_TO_HIERARCHY;
    }

    /** Build a failure entry; the change rolled back, so the still-staged edit is re-read for its op. */
    private MaterializeResultDto.Failed fail(Long ontologyId, String conceptIri, String error,
                                             String message, int status) {
        return new MaterializeResultDto.Failed(
                conceptIri, classifyStaged(ontologyId, conceptIri), error, message, status);
    }

    /** The op a concept's staged edit would apply, or null when nothing is staged for it. */
    private DiagramOp classifyStaged(Long ontologyId, String conceptIri) {
        return pendingEditRepository.findByOntologyMetadataIdAndConceptIri(ontologyId, conceptIri)
                .map(DiagramPendingEditEntity::getPendingEdit)
                .map(changeApplier::classify)
                .orElse(null);
    }
}
