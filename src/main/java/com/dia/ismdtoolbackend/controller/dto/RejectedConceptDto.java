package com.dia.ismdtoolbackend.controller.dto;

/**
 * One concept of an uploaded vocabulary that was not imported.
 *
 * @param iri    the concept's IRI
 * @param reason why it was left out
 */
public record RejectedConceptDto(
        String iri,
        String reason
) {
}
