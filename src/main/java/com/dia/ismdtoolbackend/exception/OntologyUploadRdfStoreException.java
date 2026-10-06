package com.dia.ismdtoolbackend.exception;

/** Writing the uploaded graph to TDB2 fails. No metadata is persisted. */
public class OntologyUploadRdfStoreException extends OntologyUploadException {

    /** Stable error code the FE branches on, rather than the localized message. */
    public static final String ERROR_CODE = "UPLOAD_RDF_STORE_FAILED";

    public OntologyUploadRdfStoreException(String message) {
        super(message);
    }

    public OntologyUploadRdfStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
