package com.dia.ismdtoolbackend.exception;

public class ConceptStorageException extends RuntimeException {
    public ConceptStorageException(String message) {
        super(message);
    }

    public ConceptStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
