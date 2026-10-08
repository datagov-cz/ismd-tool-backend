package com.dia.ismdtoolbackend.exception;

/** Writing the uploaded graph to TDB2 fails. No metadata is persisted. */
public class OntologyUploadRdfStoreException extends OntologyUploadException {

    public OntologyUploadRdfStoreException(String message) {
        super(message);
    }

    public OntologyUploadRdfStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
