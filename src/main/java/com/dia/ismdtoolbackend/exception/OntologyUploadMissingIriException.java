package com.dia.ismdtoolbackend.exception;

/** The uploaded data declares no {@code owl:Ontology} or {@code skos:ConceptScheme} IRI. Nothing is persisted. */
public class OntologyUploadMissingIriException extends OntologyUploadException {

    /** Stable error code the FE branches on, rather than the localized message. */
    public static final String ERROR_CODE = "UPLOAD_ONTOLOGY_IRI_MISSING";

    public OntologyUploadMissingIriException(String message) {
        super(message);
    }

    public OntologyUploadMissingIriException(String message, Throwable cause) {
        super(message, cause);
    }
}
