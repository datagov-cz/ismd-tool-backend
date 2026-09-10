package com.dia.ismdtoolbackend.controller.dto.diagram;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * A diagram list entry: identity plus a node count, without {@link DiagramDto}'s join to live content. An
 * ontology may have many diagrams, so {@code diagramId} is the routing key. {@code name} is the diagram's
 * own; the ontology is identified by its slug, which is also the routing segment. See
 * {@code docs/DIAGRAM_LAYER_API.md}.
 *
 * @param ontologyLabel the owning ontology's {@code skos:prefLabel} as a language→value map, so a client
 *                      can show the slovník's name rather than its slug. Sourced from RDF (Postgres holds
 *                      no ontology name) and null when the ontology carries no label or Fuseki is
 *                      unreachable — a display nicety must not fail the list.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagramSummaryDto(
        Long diagramId,
        String name,
        String ontologySlug,
        String graphName,
        Map<String, String> ontologyLabel,
        int nodeCount,
        String updatedAt
) {
}
