package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.MaterializeResultDto;
import com.dia.ismdtoolbackend.entity.DiagramPendingEditEntity;
import com.dia.ismdtoolbackend.enums.DiagramFailureCode;
import com.dia.ismdtoolbackend.enums.DiagramOp;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.exception.DiagramCascadeConflictException;
import com.dia.ismdtoolbackend.exception.DiagramForeignConceptException;
import com.dia.ismdtoolbackend.exception.DiagramStaleBaseException;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import com.dia.ismdtoolbackend.service.impl.DiagramChangeApplier.Outcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Materialize (Převzít) orchestrator. Resolves the staged work-list up front, then applies each change
 * through {@link DiagramChangeApplier}, which runs each in its own transaction. Not transactional itself,
 * so a failing change rolls back only itself and earlier successes stay committed.
 * See {@code docs/DIAGRAM_LAYER.md}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagramMaterializeService {

    private final DiagramChangeApplier changeApplier;
    private final DiagramPendingEditRepository pendingEditRepository;

    /**
     * Applies every staged edit on the diagram, each in its own transaction, and aggregates the outcomes.
     * {@code ontologyId} records the scope the caller resolved the winner under; the applier re-derives the
     * graph from each staged row, so nothing here reads it.
     */
    public MaterializeResultDto materialize(Long diagramId, Long ontologyId) {
        List<MaterializeResultDto.Materialized> materialized = new ArrayList<>();
        List<MaterializeResultDto.Failed> failed = new ArrayList<>();
        List<MaterializeResultDto.SkippedStale> skippedStale = new ArrayList<>();

        List<StagedChange> workList = orderedWorkList(diagramId);
        log.debug("Materializing diagram {}: {} staged change(s), ops {}",
                diagramId, workList.size(), workList.stream().map(StagedChange::op).toList());

        for (StagedChange change : workList) {
            String conceptIri = change.conceptIri();
            log.debug("Applying {} on concept {} (diagram {})", change.op(), conceptIri, diagramId);
            try {
                Outcome outcome = changeApplier.applyChange(diagramId, conceptIri);
                switch (outcome.kind()) {
                    case SKIPPED_STALE ->
                            skippedStale.add(new MaterializeResultDto.SkippedStale(conceptIri));
                    // Nothing was staged by the time the change ran, so nothing is reported: claiming a
                    // materialization here would report a change that never happened.
                    case NOTHING_STAGED ->
                            log.debug("Nothing staged for concept {} on diagram {} by the time it ran; "
                                    + "omitted from the result", conceptIri, diagramId);
                    case MATERIALIZED ->
                            materialized.add(new MaterializeResultDto.Materialized(conceptIri, outcome.op()));
                }
            } catch (DiagramStaleBaseException e) {
                failed.add(fail(change, DiagramFailureCode.STALE_BASE, e.getMessage()));
            } catch (DiagramCascadeConflictException e) {
                failed.add(fail(change, DiagramFailureCode.CASCADE_CONFLICT, e.getMessage()));
            } catch (DiagramForeignConceptException e) {
                failed.add(fail(change, DiagramFailureCode.FOREIGN_CONCEPT, e.getMessage()));
            } catch (ConceptValidationException | OntologyValidationException e) {
                failed.add(fail(change, DiagramFailureCode.VALIDATION, e.getMessage()));
            } catch (AccessDeniedException e) {
                // The caller owns the ontology but not this concept: a per-change 403, not a 500.
                failed.add(fail(change, DiagramFailureCode.FORBIDDEN,
                        "Nemáte oprávnění upravit tento pojem."));
            } catch (RuntimeException e) {
                log.error("Materialize failed for concept {}", conceptIri, e);
                failed.add(fail(change, DiagramFailureCode.ERROR, "Nastala neočekávaná chyba."));
            }
        }

        return new MaterializeResultDto(materialized, failed, skippedStale);
    }

    /**
     * The staged work-list, ordered so every {@link DiagramOp#CONVERT_TO_HIERARCHY} applies last: it bumps
     * its target class's {@code updatedAt}, which would otherwise falsely stale that class's own edit.
     *
     * <p>Classified in ONE query, then partitioned. Sorting on a comparator that classifies per comparison
     * would re-read each row O(n log n) times, and a row racing away mid-sort would flip its own key and
     * break the comparator's transitivity contract — which TimSort answers by throwing.
     */
    private List<StagedChange> orderedWorkList(Long diagramId) {
        List<StagedChange> convertLast = new ArrayList<>();
        List<StagedChange> rest = new ArrayList<>();
        for (DiagramPendingEditEntity row : pendingEditRepository.findByDiagramId(diagramId)) {
            DiagramPendingEdit edit = row.getPendingEdit();
            DiagramOp op = edit != null ? changeApplier.classify(edit) : null;
            StagedChange change = new StagedChange(row.getConceptIri(), op);
            (op == DiagramOp.CONVERT_TO_HIERARCHY ? convertLast : rest).add(change);
        }
        rest.addAll(convertLast);
        return rest;
    }

    /** One unit of work: the concept to apply, and the op its staged edit was classified as. */
    private record StagedChange(String conceptIri, DiagramOp op) {
    }

    /**
     * Builds a failure entry from the op classified when the work-list was read, not from a fresh lookup.
     * A failing change rolls its own transaction back, but a re-read still races: the row can be gone by
     * then (a concurrent discard, or an ambiguous failure at commit), which reported {@code op: null} on
     * exactly the failures a client most needs to identify.
     */
    private MaterializeResultDto.Failed fail(StagedChange change, DiagramFailureCode error,
                                             String message) {
        return new MaterializeResultDto.Failed(change.conceptIri(), change.op(), error, message);
    }
}
