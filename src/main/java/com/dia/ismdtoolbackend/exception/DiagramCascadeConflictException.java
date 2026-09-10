package com.dia.ismdtoolbackend.exception;

/**
 * Thrown when op 6 (convert a VZTAH to a hierarchy link) is blocked because another concept still points at
 * the VZTAH, so deleting it would cascade (409).
 *
 * <p>Checked before either half runs: the convert is all-or-nothing, and a cascade would remove concepts the
 * user never named.
 */
public class DiagramCascadeConflictException extends RuntimeException {

    /** Stable code, echoed in the per-change failure entry. */
    public static final String ERROR_CODE = "CASCADE_CONFLICT";

    public DiagramCascadeConflictException(String message) {
        super(message);
    }
}
