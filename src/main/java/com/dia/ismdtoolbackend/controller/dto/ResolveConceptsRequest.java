package com.dia.ismdtoolbackend.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Request body for {@code POST /api/ontology/concepts/resolve}: a batch of concept IRIs to
 * resolve into scheme + ontology identity + source in a single round-trip.
 *
 * <p>The diagram layer is the caller this exists for. Ontology detail ships bare IRIs, so a
 * concept referenced from another vocabulary — a relationship's {@code rdfs:range}, a class's
 * {@code rdfs:subClassOf} — arrives with nothing to render: no label, no parent ontology. The
 * canvas needs both before it can draw such a node, and it knows the IRIs already (it subtracts
 * the ontology's own concepts from the referenced set), so a batch resolve is all that is missing.
 *
 * <p>Datatype IRIs are dropped rather than resolved — see
 * {@code OntologyController.resolveConceptReferences}.
 */
public record ResolveConceptsRequest(
        @NotEmpty List<@NotBlank String> iris
) {
}