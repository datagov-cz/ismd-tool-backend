package com.dia.ismdtoolbackend.exception;

/** Parsing the uploaded file exceeds {@code rdf.parsing.timeout}. Nothing is persisted. */
public class OntologyUploadParseTimeoutException extends OntologyUploadException {

    public OntologyUploadParseTimeoutException(String message) {
        super(message);
    }

    public OntologyUploadParseTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
