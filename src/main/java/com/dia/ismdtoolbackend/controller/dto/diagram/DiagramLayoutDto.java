package com.dia.ismdtoolbackend.controller.dto.diagram;

import com.dia.ismdtoolbackend.enums.DiagramEdgeKind;
import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Thin write body for {@code PUT /api/diagram/{slug}/layout} (Save). Layout only — never RDF. The
 * {@code nodes} array is authoritative for canvas membership (idempotent full-replace): a node present
 * is kept or added (a new IRI is hydrated in the response), a node omitted is removed from the canvas.
 * See {@code docs/DIAGRAM_LAYER_API.md}.
 */
public record DiagramLayoutDto(
        /* Required on every save, including the first — a fresh canvas sends 0. */
        @NotNull Long version,
        ViewportDto viewport,
        @NotNull @Valid List<Node> nodes,
        /* Full-replace: the set sent here becomes the persisted edge set, so an omission is a wipe. */
        @NotNull @Valid List<Edge> edges
) {

    /**
     * A node's persisted layout. {@code id} is {@code iri:<full-iri>}; a new IRI adds the node.
     * {@code collapsed} is optional on the wire — omitted or null means not collapsed.
     */
    public record Node(
            @NotBlank String id,
            @NotNull @Valid PositionDto position,
            String parentId,
            Boolean collapsed
    ) {

        public Node {
            collapsed = collapsed != null && collapsed;
        }
    }

    /**
     * A projected edge's persisted presentation state. Endpoints are node ids ({@code iri:...}).
     * {@code segments} is optional — omitted or null means default routing.
     */
    public record Edge(
            @NotBlank String id,
            @NotBlank String source,
            @NotBlank String target,
            @NotNull DiagramEdgeKind edgeKind,
            String sourceHandle,
            String targetHandle,
            List<EdgeWaypoint> segments
    ) {
    }
}
