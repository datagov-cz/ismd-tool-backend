package com.dia.ismdtoolbackend.exception;

public class ConceptNotFoundException extends RuntimeException {
    public ConceptNotFoundException(String message) {
        super(message);
    }

    public ConceptNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
