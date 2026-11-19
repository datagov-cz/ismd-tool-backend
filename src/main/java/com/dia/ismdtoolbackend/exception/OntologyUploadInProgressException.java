package com.dia.ismdtoolbackend.exception;

/** Another upload of the same vocabulary is still running. Nothing is persisted. */
public class OntologyUploadInProgressException extends OntologyUploadException {
    public OntologyUploadInProgressException(String message) {
        super(message);
    }
}
