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
 * Fat, render-ready diagram read model: layout rows already joined to live concept content with each
 * node's overlay applied and edges projected from {@code live ⊕ overlay}. The response of
 * {@code GET /api/diagram/{slug}} and every write endpoint. See {@code docs/DIAGRAM_LAYER_API.md}.
 *
 * <p>No {@code coverage} field — the FE derives "concepts not on the canvas" as a client-side set-diff.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagramDto(
        String ontologySlug,
        Long version,
        ViewportDto viewport,
        List<Node> nodes,
        List<Edge> edges,
        int pendingChangeCount
) {

    /**
     * A canvas node: layout from PG, {@code data} joined from live RDF ⊕ overlay.
     *
     * <p>{@code collapsed} completes the layout round-trip — the FE sends it on {@code PUT …/layout} and
     * gets it back here, so a collapsed group survives a reload. A primitive, so it always serializes.
     *
     * <p>{@code version} is the diagram's version after the write that returned this node — set only on the
     * lean {@code PATCH …/nodes/overlay} response, which has no enclosing {@link DiagramDto} to carry it.
     * Inside {@code DiagramDto.nodes} it is null (and so omitted): the version there belongs to the
     * enclosing diagram, and repeating it per node would imply a per-node lock that does not exist.
     */
    @Schema(name = "DiagramNode")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Node(
            String id,
            String type,
            PositionDto position,
            String parentId,
            boolean collapsed,
            NodeData data,
            Long version
    ) {

        /** The in-diagram form: no version, because the enclosing {@link DiagramDto} carries it. */
        public Node(String id, String type, PositionDto position, String parentId, boolean collapsed,
                    NodeData data) {
            this(id, type, position, parentId, collapsed, data, null);
        }

        /** The lean stage-response form: same node, stamped with the post-write diagram version. */
        public Node withVersion(Long version) {
            return new Node(id, type, position, parentId, collapsed, data, version);
        }
    }

    /**
     * Merged live-content-plus-overlay payload the FE renders directly.
     *
     * <p>{@code properties} are the class's VLASTNOSTi, rendered as rows inside the node rather than as
     * canvas objects of their own. Always present (empty, never null) and ordered by label so the rows do
     * not reshuffle between reads.
     */
    @Schema(name = "DiagramNodeData")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NodeData(
            ConceptType conceptType,
            String iri,
            String slug,
            Map<String, String> label,
            boolean stale,
            boolean hasPendingEdits,
            DiagramPendingEdit pendingEdit,
            @JsonInclude List<PropertyRow> properties
    ) {
    }

    /**
     * One VLASTNOST, rendered as a row inside its {@code rdfs:domain} class. A property is never a node
     * and never an edge: its range is a literal datatype, so there is no second concept to connect to.
     *
     * <p>A domainless property has no class to sit in and is simply absent from the canvas — it is placed
     * by being dragged in from the ontology detail, which supplies the domain.
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
     * while {@code segments} is joined on from the persisted row — the one thing RDF cannot express. It is
     * null for an edge that has never been saved, or whose endpoint moved since it was.
     *
     * <p>{@code id} is the backing concept's IRI for a VZTAH, and the deterministic
     * {@code edge|KIND|source|target} for a hierarchy/equivalence link, which has no concept behind it.
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
     * Edge metadata. {@code pending} is true when an endpoint comes from an unmaterialized overlay.
     *
     * <p>The concept fields are populated only for a {@code VZTAH}, where the edge <em>is</em> a concept.
     * {@code SUBCLASS_OF} and {@code EXACT_MATCH} are bare triples and leave them null — an edge with a
     * non-null {@code iri} is the FE's signal that it can be selected, staged and deep-linked.
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

        /** A bare triple: hierarchy or equivalence, with no backing concept. */
        public EdgeData(DiagramEdgeKind edgeKind, boolean pending) {
            this(edgeKind, pending, null, null, null, null, null, null, null);
        }
    }
}
