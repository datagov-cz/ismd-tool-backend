package com.dia.ismdtoolbackend.exception;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramConflictDto;

/**
 * Thrown by Převzít when a sibling diagram of the same ontology stages an edit on one of the same concepts
 * and the caller gave no resolution.
 *
 * <p>Applying one side moves the concept's {@code updatedAt}, the fingerprint the other side's staged edit
 * was stamped against, so the sibling would afterwards fail {@code STALE_BASE} one concept at a time with
 * no explanation. Reporting the whole collision first lets the user resolve it in one decision.
 *
 * <p>Nothing is written when it fires: detection runs before the per-change transactions begin. See
 * {@code docs/DIAGRAM_LAYER_API.md}.
 */
public class DiagramEditConflictException extends RuntimeException {

    /** Stable error code the FE branches on, rather than the localized message. */
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
