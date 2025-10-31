package com.dia.ismdtoolbackend.exception;

public class OntologyUploadException extends RuntimeException {
    public OntologyUploadException(String message) {
        super(message);
    }

    public OntologyUploadException(String message, Throwable cause) {
        super(message, cause);
    }
}
