package com.dia.ismdtoolbackend.controller.dto.diagram;

import com.dia.ismdtoolbackend.enums.DiagramOp;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Result of {@code POST /api/diagram/{slug}/materialize}, one entry per staged change, each keyed by
 * {@code conceptIri} like {@code pendingEdits[]}. Partial-ok per change, while a change spanning two
 * concepts is all-or-nothing and a failure keeps the whole change staged. See
 * {@code docs/DIAGRAM_LAYER_API.md}.
 */
public record MaterializeResultDto(
        List<Materialized> materialized,
        List<Failed> failed,
        List<SkippedStale> skippedStale
) {

    /** A change applied to RDF and had its staged edit cleared. */
    public record Materialized(String conceptIri, DiagramOp op) {
    }

    /**
     * A change that failed, whose staged edit is retained. {@code error} is one of {@code VALIDATION} (400),
     * {@code FOREIGN_CONCEPT} (400), {@code FORBIDDEN} (403), {@code STALE_BASE} (409),
     * {@code CASCADE_CONFLICT} (409) or {@code ERROR} (500).
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

    /** A change whose referenced concept no longer exists, so it cannot be applied. */
    public record SkippedStale(String conceptIri) {
    }
}
