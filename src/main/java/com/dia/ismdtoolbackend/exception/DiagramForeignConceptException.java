package com.dia.ismdtoolbackend.exception;

/**
 * Thrown when a concept the request would WRITE lies outside the diagram's own ontology graph (400).
 *
 * <p>The endpoints authorize an ontology slug while concept IRIs travel in the body, so without this check
 * any authenticated user could edit — and via op 6 delete — another ontology's concepts. Applies to written
 * endpoints only ({@code domain}, op 6's {@code addBroaderOn}); a referenced one ({@code range},
 * {@code broaderConcept}, {@code exactMatch}) may legitimately be foreign, that being the cross-ontology
 * link the feature exists to draw.
 *
 * <p>A rejected request, not a stale reference: 400, and the overlay stays staged.
 */
public class DiagramForeignConceptException extends RuntimeException {

    /** Stable code, echoed in the per-change failure entry. */
    public static final String ERROR_CODE = "FOREIGN_CONCEPT";

    public DiagramForeignConceptException(String message) {
        super(message);
    }
}
