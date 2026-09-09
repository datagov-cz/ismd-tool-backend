package com.dia.ismdtoolbackend.controller.dto.diagram;

import com.dia.ismdtoolbackend.controller.dto.DataTypeDto;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.DiagramEdgeKind;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Render-ready diagram read model: layout rows joined to live concept content, each node's overlay applied
 * and edges projected from {@code live ⊕ overlay}. The response of
 * {@code GET /api/diagram/{slug}/{diagramId}/detail} and of every write endpoint. See
 * {@code docs/DIAGRAM_LAYER_API.md}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagramDto(
        Long diagramId,
        String name,
        String ontologySlug,
        Long version,
        ViewportDto viewport,
        List<Node> nodes,
        List<Edge> edges,
        /* Every staged edit, canvas-rendered or not. Empty rather than null. */
        @JsonInclude List<PendingEditEntry> pendingEdits
) {

    /**
     * One staged overlay, keyed by concept IRI and listed whether or not the canvas renders its concept.
     * The {@code pendingEdit} on a node, edge or property row is a copy for the element that draws it.
     */
    @Schema(name = "DiagramPendingEditEntry")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PendingEditEntry(
            String iri,
            ConceptType conceptType,
            String slug,
            Map<String, String> label,
            boolean stale,
            boolean unavailable,
            DiagramPendingEdit pendingEdit
    ) {
    }

    /**
     * A canvas node: layout from PG, {@code data} joined from live RDF ⊕ overlay. {@code collapsed}
     * completes the layout round-trip so a collapsed group survives a reload. Versioning belongs to the
     * enclosing {@link DiagramDto}, never to a node.
     */
    @Schema(name = "DiagramNode")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Node(
            String id,
            String type,
            PositionDto position,
            String parentId,
            boolean collapsed,
            NodeData data
    ) {
    }

    /**
     * The merged live-plus-overlay payload the FE renders directly. {@code properties} are the class's
     * VLASTNOSTi as rows inside the node, empty rather than null and ordered by label so they do not
     * reshuffle between reads.
     *
     * <p>{@code stale} and {@code unavailable} are different absences and never both true. {@code stale}
     * means the graph was read and the concept is not in it — deleted underneath the node, so offer remove
     * or recreate. {@code unavailable} means that concept's graph could not be read at all, which only
     * happens for a foreign ontology, since an unreadable own graph fails the whole request; the concept is
     * presumed intact, so render it as temporarily unresolved and let a later reload settle it.
     */
    @Schema(name = "DiagramNodeData")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NodeData(
            ConceptType conceptType,
            String iri,
            String slug,
            Map<String, String> label,
            boolean stale,
            boolean unavailable,
            boolean hasPendingEdits,
            DiagramPendingEdit pendingEdit,
            @JsonInclude List<PropertyRow> properties,
            /* The concept belongs to another ontology or to NKD: drawn for context, never editable from
             * here, and no overlay may target it. */
            boolean readOnly
    ) {
    }

    /**
     * One VLASTNOST, rendered as a row inside its {@code rdfs:domain} class rather than a node or an edge,
     * since its range is a literal datatype. A domainless property is absent from the canvas until it is
     * dragged in from the ontology detail, which supplies the domain.
     */
    @Schema(name = "DiagramPropertyRow")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PropertyRow(
            String iri,
            String slug,
            Map<String, String> label,
            DataTypeDto rangeResolved,
            boolean stale,
            boolean hasPendingEdits,
            DiagramPendingEdit pendingEdit
    ) {
    }

    /**
     * A projected edge: existence, kind and endpoints are re-derived on read from {@code live ⊕ overlay},
     * while {@code segments} is joined on from the persisted row, being the one thing RDF cannot express.
     * It is null for an edge never saved, or whose endpoint has moved since.
     *
     * <p>{@code id} is the backing concept's IRI for a VZTAH, and the deterministic
     * {@code edge|KIND|source|target} for a hierarchy or equivalence link, which has no concept behind it.
     */
    @Schema(name = "DiagramEdge")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Edge(
            String id,
            String source,
            String target,
            String type,
            List<EdgeWaypoint> segments,
            EdgeData data
    ) {
    }

    /**
     * Edge metadata; {@code pending} is true when an endpoint comes from an unmaterialized overlay. The
     * concept fields are populated only for a {@code VZTAH}, where the edge is a concept —
     * {@code SUBCLASS_OF} and {@code EXACT_MATCH} are bare triples and leave them null, so a non-null
     * {@code iri} is the FE's signal that the edge can be selected, staged and deep-linked.
     */
    @Schema(name = "DiagramEdgeData")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EdgeData(
            DiagramEdgeKind edgeKind,
            boolean pending,
            ConceptType conceptType,
            String iri,
            String slug,
            Map<String, String> label,
            Boolean stale,
            Boolean hasPendingEdits,
            DiagramPendingEdit pendingEdit
    ) {

        /** A bare triple, hierarchy or equivalence, with no backing concept. */
        public EdgeData(DiagramEdgeKind edgeKind, boolean pending) {
            this(edgeKind, pending, null, null, null, null, null, null, null);
        }
    }
}
