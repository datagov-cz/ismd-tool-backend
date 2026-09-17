package com.dia.ismdtoolbackend.exception;

/**
 * Thrown when a diagram name is already taken within its ontology. Names are unique per ontology because
 * they are how a user tells two canvases apart in the picker and in search results. Distinct from the DB's
 * own constraint violation, so the caller gets a 409 naming the clash rather than a generic 400.
 */
public class DiagramNameConflictException extends RuntimeException {

    /** Stable error code the FE branches on, rather than the localized message. */
    public static final String ERROR_CODE = "DIAGRAM_NAME_CONFLICT";

    public DiagramNameConflictException(String name) {
        super("Diagram s názvem \"" + name + "\" v tomto slovníku již existuje.");
    }
}
