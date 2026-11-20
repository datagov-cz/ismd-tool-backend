package com.dia.ismdtoolbackend.exception;

import org.apache.jena.ontology.OntologyException;

/**
 * Jena's {@link OntologyException} has no cause constructor. The message stays the client-facing text and
 * the cause carries the detail for the log.
 */
public final class OntologyExceptions {

    private OntologyExceptions() {
    }

    public static OntologyException withCause(String message, Throwable cause) {
        OntologyException e = new OntologyException(message);
        e.initCause(cause);
        return e;
    }
}
