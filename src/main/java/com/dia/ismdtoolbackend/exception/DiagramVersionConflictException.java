package com.dia.ismdtoolbackend.exception;

/**
 * Thrown when a layout save carries a version other than the stored one — another editor saved first (409).
 *
 * <p>Enforced in the service rather than by JPA's {@code @Version}: the save re-reads the diagram in its own
 * transaction, so JPA would only compare that value against itself. Canvas membership is a full replace, so
 * a stale save would silently delete nodes the other editor added.
 */
public class DiagramVersionConflictException extends RuntimeException {

    /** Stable error code the FE branches on, rather than the localized message. */
    public static final String ERROR_CODE = "DIAGRAM_VERSION_CONFLICT";

    public DiagramVersionConflictException(String message) {
        super(message);
    }
}
