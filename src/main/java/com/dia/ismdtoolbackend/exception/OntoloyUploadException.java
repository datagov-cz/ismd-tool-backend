package com.dia.ismdtoolbackend.exception;

public class OntoloyUploadException extends RuntimeException {
    public OntoloyUploadException(String message, Exception e) {
        super(message, e);
    }
}
