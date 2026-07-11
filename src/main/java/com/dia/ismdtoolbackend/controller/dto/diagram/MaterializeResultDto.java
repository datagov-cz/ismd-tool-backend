package com.dia.ismdtoolbackend.controller.dto.diagram;

import com.dia.ismdtoolbackend.enums.DiagramOp;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Result of {@code POST /api/diagram/{slug}/materialize}. One entry per staged change
 * (a change may span two concepts). Per-change partial-ok; a two-concept change is all-or-nothing —
 * a failure keeps the whole change staged. See {@code docs/DIAGRAM_LAYER_API.md}.
 */
public record MaterializeResultDto(
        List<Materialized> materialized,
        List<Failed> failed,
        List<SkippedStale> skippedStale
) {

    /** A change that was applied to RDF and had its overlay cleared. */
    public record Materialized(Long nodeId, String conceptIri, DiagramOp op) {
    }

    /**
     * A change that failed; the overlay is retained. {@code error} ∈ {@code VALIDATION} (400),
     * {@code STALE_BASE} (409), {@code CASCADE_CONFLICT}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Failed(
            Long nodeId,
            String conceptIri,
            DiagramOp op,
            String error,
            String message,
            Integer status
    ) {
    }

    /** A change whose referenced concept no longer exists — un-applyable. */
    public record SkippedStale(Long nodeId, String conceptIri) {
    }
}
