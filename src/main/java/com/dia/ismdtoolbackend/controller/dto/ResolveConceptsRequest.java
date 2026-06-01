package com.dia.ismdtoolbackend.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Request body for {@code POST /api/ontology/concepts/resolve}: the FE sends every
 * concept IRI referenced by a concept-detail response so the backend can return
 * scheme + ontology description + source in a single round-trip.
 */
public record ResolveConceptsRequest(
        @NotEmpty List<@NotBlank String> iris
) {
}
