package com.dia.ismdtoolbackend.exception;

/** A server-side failure while processing a vocabulary or concept. The message and cause are for the log only. */
public class OntologyProcessingException extends RuntimeException {
    public OntologyProcessingException(String message) {
        super(message);
    }

    public OntologyProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
