package com.dia.ismdtoolbackend.exception;

public class OntologyNotFoundException extends RuntimeException {
    public OntologyNotFoundException(String message) {
        super(message);
    }

    public OntologyNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
