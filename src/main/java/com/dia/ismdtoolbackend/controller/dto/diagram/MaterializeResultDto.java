package com.dia.ismdtoolbackend.controller.dto.diagram;

import com.dia.ismdtoolbackend.enums.DiagramOp;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Result of {@code POST /api/diagram/{slug}/materialize}. One entry per staged change
 * (a change may span two concepts). Per-change partial-ok; a two-concept change is all-or-nothing —
 * a failure keeps the whole change staged. See {@code docs/DIAGRAM_LAYER_API.md}.
 *
 * <p>Every entry is keyed by {@code conceptIri} — the same identity as {@code pendingEdits[]}.
 */
public record MaterializeResultDto(
        List<Materialized> materialized,
        List<Failed> failed,
        List<SkippedStale> skippedStale
) {

    /** A change that was applied to RDF and had its staged edit cleared. */
    public record Materialized(String conceptIri, DiagramOp op) {
    }

    /**
     * A change that failed; the staged edit is retained. {@code error} ∈ {@code VALIDATION} (400),
     * {@code STALE_BASE} (409), {@code CASCADE_CONFLICT}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Failed(
            String conceptIri,
            DiagramOp op,
            String error,
            String message,
            Integer status
    ) {
    }

    /** A change whose referenced concept no longer exists — un-applyable. */
    public record SkippedStale(String conceptIri) {
    }
}
