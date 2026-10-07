package com.dia.ismdtoolbackend.exception;

/** Parsing the uploaded file exceeds {@code rdf.parsing.timeout}. Nothing is persisted. */
public class OntologyUploadParseTimeoutException extends OntologyUploadException {

    /** Stable error code the FE branches on, rather than the localized message. */
    public static final String ERROR_CODE = "UPLOAD_RDF_PARSE_TIMEOUT";

    public OntologyUploadParseTimeoutException(String message) {
        super(message);
    }

    public OntologyUploadParseTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
