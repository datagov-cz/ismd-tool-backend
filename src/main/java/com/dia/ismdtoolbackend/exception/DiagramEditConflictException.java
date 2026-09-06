package com.dia.ismdtoolbackend.exception;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramConflictDto;

/**
 * Thrown by Převzít when a sibling diagram of the same ontology stages an edit on one of the same
 * concepts, and the caller gave no resolution.
 *
 * <p>Staged edits are per-diagram, so two canvases can hold competing intent for one concept. Applying
 * one side moves the concept's {@code updatedAt}, which is the fingerprint the other side's staged edit
 * was stamped against — so the sibling would then fail {@code STALE_BASE}, one concept per attempt, with
 * no explanation of what moved underneath it. This exception reports the whole collision before anything
 * is written, so the user resolves it in one decision.
 *
 * <p>Nothing is written when it fires: detection runs before the per-change transactions begin, so both
 * sides' staged work is left exactly as it was.
 *
 * <p>See {@code docs/DIAGRAM_LAYER_API.md}.
 */
public class DiagramEditConflictException extends RuntimeException {

    /** Stable error code the FE branches on (not the localized message). */
    public static final String ERROR_CODE = "DIAGRAM_EDIT_CONFLICT";

    private final transient DiagramConflictDto report;

    public DiagramEditConflictException(DiagramConflictDto report) {
        super("Některé změny kolidují se změnami rozpracovanými v jiném diagramu.");
        this.report = report;
    }

    /** The conflicting concepts, with both sides' staged edits. */
    public DiagramConflictDto getReport() {
        return report;
    }
}
