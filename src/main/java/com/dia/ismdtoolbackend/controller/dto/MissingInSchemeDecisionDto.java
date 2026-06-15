package com.dia.ismdtoolbackend.controller.dto;

import java.util.List;

/**
 * Body of the {@code MISSING_INSCHEME_DECISION_REQUIRED} (HTTP 400) response. Lists
 * the owned concepts that lack {@code skos:inScheme}; the user re-uploads the same file
 * with a {@code normalizeMode} (+ optional {@code conceptsToNormalize}) decision.
 *
 * @param graphName               the authoritative vocabulary IRI derived from the RDF
 * @param conceptsMissingInScheme owned concepts missing skos:inScheme, with proposed values
 */
public record MissingInSchemeDecisionDto(
        String graphName,
        List<MissingConceptDto> conceptsMissingInScheme
) {
}
