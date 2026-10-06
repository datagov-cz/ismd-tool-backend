package com.dia.ismdtoolbackend.exception;

/** The uploaded file is not parseable RDF in the detected format. Nothing is persisted. */
public class OntologyUploadParseException extends OntologyUploadException {

    /** Stable error code the FE branches on, rather than the localized message. */
    public static final String ERROR_CODE = "UPLOAD_RDF_PARSE_FAILED";

    public OntologyUploadParseException(String message) {
        super(message);
    }

    public OntologyUploadParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
