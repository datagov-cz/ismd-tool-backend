package com.dia.ismdtoolbackend.exception;

import com.dia.ismdtoolbackend.enums.ConceptType;

import java.util.Collection;
import java.util.stream.Collectors;

/** A concept carries types of more than one role, such as a property (vlastnost) and a relationship (vztah). */
public class ConflictingConceptTypeException extends ModelProcessingException {
    public ConflictingConceptTypeException(String conceptIri) {
        super("Concept is typed as both a property (vlastnost) and a relationship (vztah): " + conceptIri);
    }

    public ConflictingConceptTypeException(Collection<ConceptType> roles) {
        super("Concept is typed with more than one role: " + roles.stream().map(ConceptType::name).sorted()
                .collect(Collectors.joining(" + ")));
    }

    /** Adds the concept IRI to a conflict raised where only the types were known. */
    public ConflictingConceptTypeException(String conceptIri, ConflictingConceptTypeException cause) {
        super(cause.getMessage() + ": " + conceptIri, cause);
    }
}
