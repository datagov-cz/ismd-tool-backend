package com.dia.ismdtoolbackend.exception;

/** The uploaded file is not parseable RDF in the detected format. Nothing is persisted. */
public class OntologyUploadParseException extends OntologyUploadException {

    public OntologyUploadParseException(String message) {
        super(message);
    }

    public OntologyUploadParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
