package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.enums.SearchSource;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.Map;

/**
 * Resolved metadata for a single concept IRI. Returned as a map value keyed by
 * the input IRI; unresolved IRIs are simply absent from the response map (the
 * FE falls back to rendering the plain IRI).
 *
 * <p>{@code conceptSlug} is populated only for ISMD concepts (used by the FE
 * to build {@code /api/concept/{slug}/detail} navigation); for NKD concepts it
 * is {@code null} and the FE navigates by {@code iri}.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResolvedConceptDto(
        String iri,
        Map<String, String> conceptName,
        String conceptSlug,
        String ontologyIri,
        Map<String, String> ontologyName,
        SearchSource source
) {
}