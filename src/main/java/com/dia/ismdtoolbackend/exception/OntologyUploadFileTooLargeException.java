package com.dia.ismdtoolbackend.exception;

/** The uploaded file exceeds the configured size limit. Nothing is persisted. */
public class OntologyUploadFileTooLargeException extends OntologyUploadException {

    public OntologyUploadFileTooLargeException(String message) {
        super(message);
    }

    public OntologyUploadFileTooLargeException(String message, Throwable cause) {
        super(message, cause);
    }
}
