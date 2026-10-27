package com.dia.ismdtoolbackend.exception;

/** The uploaded data declares no {@code owl:Ontology} or {@code skos:ConceptScheme} IRI. Nothing is persisted. */
public class OntologyUploadMissingIriException extends OntologyUploadException {

    public OntologyUploadMissingIriException(String message) {
        super(message);
    }

    public OntologyUploadMissingIriException(String message, Throwable cause) {
        super(message, cause);
    }
}
