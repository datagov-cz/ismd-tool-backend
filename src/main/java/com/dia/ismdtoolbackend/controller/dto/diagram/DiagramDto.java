package com.dia.ismdtoolbackend.controller.dto.diagram;

import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.DiagramEdgeKind;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Fat, render-ready diagram read model: layout rows already joined to live concept content with each
 * node's overlay applied and edges projected from {@code live ⊕ overlay}. The response of
 * {@code GET /api/diagram/{slug}} and every write endpoint. See {@code docs/DIAGRAM_LAYER_API.md}.
 *
 * <p>No {@code coverage} field — the FE derives "concepts not on the canvas" as a client-side set-diff.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagramDto(
        String ontologySlug,
        ViewportDto viewport,
        List<Node> nodes,
        List<Edge> edges,
        int pendingChangeCount
) {

    /** A canvas node: layout from PG, {@code data} joined from live RDF ⊕ overlay. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Node(
            String id,
            String type,
            PositionDto position,
            String parentId,
            NodeData data
    ) {
    }

    /** Merged live-content-plus-overlay payload the FE renders directly. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NodeData(
            ConceptType conceptType,
            String iri,
            String slug,
            Map<String, String> label,
            boolean stale,
            boolean hasPendingEdits,
            DiagramPendingEdit pendingEdit
    ) {
    }

    /** A projected edge — re-derived on read from the source node's {@code live ⊕ overlay}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Edge(
            String id,
            String source,
            String target,
            String type,
            String sourceHandle,
            String targetHandle,
            Map<String, String> markerEnd,
            EdgeData data
    ) {
    }

    /** Edge metadata; {@code pending} is true when the endpoint comes from an unmaterialized overlay. */
    public record EdgeData(DiagramEdgeKind edgeKind, boolean pending) {
    }
}
