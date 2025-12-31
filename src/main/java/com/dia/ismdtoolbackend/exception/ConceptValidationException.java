package com.dia.ismdtoolbackend.exception;

public class ConceptValidationException extends RuntimeException {
    public ConceptValidationException(String message) {
        super(message);
    }

    public ConceptValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
