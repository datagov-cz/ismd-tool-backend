package com.dia.ismdtoolbackend.exception;

/** Saving ontology or concept metadata to PostgreSQL fails after the graph is written. The TDB2 graph is rolled back best-effort. */
public class OntologyUploadMetadataException extends OntologyUploadException {

    public OntologyUploadMetadataException(String message) {
        super(message);
    }

    public OntologyUploadMetadataException(String message, Throwable cause) {
        super(message, cause);
    }
}
