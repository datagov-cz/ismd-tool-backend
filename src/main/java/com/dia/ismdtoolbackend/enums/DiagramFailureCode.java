package com.dia.ismdtoolbackend.enums;

/**
 * Why one staged change failed to materialize, and the HTTP status that classifies it. A closed set shared
 * by the materialize loop, the result DTO and the FE — the sibling of {@link DiagramOp} in the same record.
 *
 * <p>The status travels with the code rather than beside it because the pairing is fixed: every
 * {@code STALE_BASE} is a 409, every {@code FOREIGN_CONCEPT} a 400. Passing them separately let the two
 * drift at each of the six throw sites.
 *
 * <p>These are per-change outcomes inside a 200 response, not response statuses — one failed concept does
 * not fail the whole Převzít. See {@code docs/DIAGRAM_LAYER_API.md}.
 */
public enum DiagramFailureCode {

    /** The concept was edited after the overlay was staged; recover with discard-then-restage. */
    STALE_BASE(409),

    /** Op 6 blocked: another concept points at the VZTAH, so deleting it would cascade. */
    CASCADE_CONFLICT(409),

    /** A concept the change would write lies outside the diagram's own ontology graph. */
    FOREIGN_CONCEPT(400),

    /** The resulting concept edit is invalid — the ordinary concept-validation rules apply here too. */
    VALIDATION(400),

    /** The caller owns the ontology but not this concept. */
    FORBIDDEN(403),

    /** Anything unanticipated; the message is deliberately generic. */
    ERROR(500);

    private final int status;

    DiagramFailureCode(int status) {
        this.status = status;
    }

    /** The HTTP status this failure would carry on its own, echoed per change in the result. */
    public int getStatus() {
        return status;
    }
}