package com.dia.ismdtoolbackend.controller.dto.diagram;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A diagram list entry: identity plus a node count, without {@link DiagramDto}'s join to live content. An
 * ontology may have many diagrams, so {@code diagramId} is the routing key. See
 * {@code docs/DIAGRAM_LAYER_API.md}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagramSummaryDto(
        Long diagramId,
        String name,
        String ontologySlug,
        String ontologyName,
        String graphName,
        int nodeCount,
        String updatedAt
) {
}
