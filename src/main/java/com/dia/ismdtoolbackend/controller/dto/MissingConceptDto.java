package com.dia.ismdtoolbackend.controller.dto;

/**
 * One owned concept that lacks {@code skos:inScheme} on upload. Shown to the user so
 * they can verify the {@code proposedInScheme} (always the vocabulary IRI / graphName)
 * before choosing to normalize or exclude it.
 *
 * @param conceptIri      the concept's IRI (under graphName's namespace)
 * @param conceptName     the name extracted from the IRI, for display
 * @param proposedInScheme the {@code skos:inScheme} value that NORMALIZE would add (= graphName)
 */
public record MissingConceptDto(
        String conceptIri,
        String conceptName,
        String proposedInScheme
) {
}
