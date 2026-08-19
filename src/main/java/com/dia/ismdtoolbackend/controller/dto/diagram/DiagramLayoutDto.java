package com.dia.ismdtoolbackend.controller.dto.diagram;

import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;
import io.swagger.v3.oas.annotations.media.Schema;
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
        /* Optional: null and [] both mean "no hand-routed edges" — the state of a freshly auto-laid-out
         * canvas, where ReactFlow has positioned everything and the user has not dragged a waypoint yet.
         * Full-replace of the saved waypoints: an omitted edge still renders (it is re-projected), it just
         * reverts to default routing. */
        @Valid List<Edge> edges
) {

    /**
     * A node's persisted layout. {@code id} is {@code iri:<full-iri>}; a new IRI adds the node.
     * {@code collapsed} is optional on the wire — omitted or null means not collapsed.
     */
    @Schema(name = "DiagramLayoutNode")
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
     * A projected edge's persisted waypoints, and nothing else. {@code id} is the projected edge id from
     * the last read — a VZTAH's concept IRI, or the composite {@code edge|KIND|source|target} of a
     * hierarchy/equivalence link. {@code segments} is optional; omitted or null means default routing.
     *
     * <p>Endpoints and kind are deliberately absent: they are derived from {@code rdfs:domain}/
     * {@code rdfs:range} ⊕ overlay on every read, so accepting them here would let a client persist a
     * value that contradicts the projection. Structural changes go through the overlay endpoint.
     */
    @Schema(name = "DiagramLayoutEdge")
    public record Edge(
            @NotBlank String id,
            List<EdgeWaypoint> segments
    ) {
    }
}
