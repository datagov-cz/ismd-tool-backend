package com.dia.ismdtoolbackend.exception;

/**
 * Thrown when the conflict resolution itself is unusable — {@code ACCEPT_THEIRS} with no
 * {@code winnerDiagramId}, one naming a diagram outside the conflict set, or a resolution sent when nothing
 * collides (400).
 *
 * <p>400 rather than 409: re-sending the same request cannot succeed, so the client must correct it rather
 * than let the user choose again.
 */
public class DiagramConflictResolutionException extends RuntimeException {

    /** Stable error code the FE branches on, rather than the localized message. */
    public static final String ERROR_CODE = "DIAGRAM_CONFLICT_RESOLUTION_INVALID";

    public DiagramConflictResolutionException(String message) {
        super(message);
    }
}
