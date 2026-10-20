package com.dia.ismdtoolbackend.exception;

/** A concept carries types of both a property (vlastnost) and a relationship (vztah). */
public class ConflictingConceptTypeException extends ModelProcessingException {
    public ConflictingConceptTypeException(String conceptIri) {
        super("Concept is typed as both a property (vlastnost) and a relationship (vztah): " + conceptIri);
    }
}
