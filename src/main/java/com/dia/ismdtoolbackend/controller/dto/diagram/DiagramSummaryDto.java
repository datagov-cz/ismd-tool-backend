package com.dia.ismdtoolbackend.controller.dto.diagram;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Lightweight diagram list entry for {@code GET /api/diagram/all} — identity plus a node count, without
 * the fat {@link DiagramDto} join to live content. See {@code docs/DIAGRAM_LAYER_API.md}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagramSummaryDto(
        String ontologySlug,
        String ontologyName,
        String graphName,
        int nodeCount,
        String updatedAt
) {
}
