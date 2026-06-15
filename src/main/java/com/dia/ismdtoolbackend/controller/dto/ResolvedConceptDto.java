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
 *
 * <p>{@code resolvedDomain} and {@code resolvedRange} are populated only when the
 * resolved concept is a relationship (rdf:type {@code …/vztah}); they carry the
 * fully-resolved {@code rdfs:domain} / {@code rdfs:range} target concepts so the
 * FE can render and navigate to the related classes without a second resolve
 * round-trip. They are {@code null} for non-relationships and for relationships
 * whose domain/range is absent or unresolvable.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResolvedConceptDto(
        String iri,
        Map<String, String> conceptName,
        String conceptSlug,
        String ontologyIri,
        Map<String, String> ontologyName,
        SearchSource source,
        ResolvedConceptDto resolvedDomain,
        ResolvedConceptDto resolvedRange
) {
}