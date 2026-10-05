package com.dia.ismdtoolbackend.exception;

/** Saving ontology or concept metadata to PostgreSQL fails after the graph is written. The TDB2 graph is rolled back best-effort. */
public class OntologyUploadMetadataException extends OntologyUploadException {

    /** Stable error code the FE branches on, rather than the localized message. */
    public static final String ERROR_CODE = "UPLOAD_METADATA_SAVE_FAILED";

    public OntologyUploadMetadataException(String message) {
        super(message);
    }

    public OntologyUploadMetadataException(String message, Throwable cause) {
        super(message, cause);
    }
}
