package com.dia.ismdtoolbackend.exception;

public class OntologyValidationException extends RuntimeException {
    public OntologyValidationException(String message) {
        super(message);
    }

    public OntologyValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
