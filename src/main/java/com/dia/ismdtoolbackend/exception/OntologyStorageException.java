package com.dia.ismdtoolbackend.exception;

public class OntologyStorageException extends RuntimeException {
    public OntologyStorageException(String message) {
        super(message);
    }

    public OntologyStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
