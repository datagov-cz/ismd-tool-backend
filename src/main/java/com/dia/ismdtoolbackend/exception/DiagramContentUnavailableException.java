package com.dia.ismdtoolbackend.exception;

/**
 * Thrown when a diagram read could not load live concept content from the ontology's own graph, so the
 * response could not be assembled. Nothing was written and nothing is lost — the client may simply retry.
 *
 * <p>502 rather than 500, matching {@link DiagramReadbackFailedException}: the same unreachable Fuseki must
 * not report one status on a read and another on a save. The two stay distinct because their advice
 * differs — this one says retry, that one says reload because your write is already durable.
 *
 * <p>Only the ontology's own graph fails closed. A foreign graph that cannot be read leaves the rest of the
 * canvas usable and marks its own nodes {@code unavailable}; see {@code docs/DIAGRAM_LAYER_API.md}.
 */
public class DiagramContentUnavailableException extends RuntimeException {

    /** Stable error code the FE branches on, rather than the localized message. */
    public static final String ERROR_CODE = "DIAGRAM_CONTENT_UNAVAILABLE";

    public DiagramContentUnavailableException(Throwable cause) {
        super("Obsah pojmů diagramu se nepodařilo načíst. Zkuste to prosím znovu.", cause);
    }
}