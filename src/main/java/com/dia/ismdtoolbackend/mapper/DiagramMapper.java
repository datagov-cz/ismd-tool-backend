package com.dia.ismdtoolbackend.mapper;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.PositionDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.ViewportDto;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.DiagramEdgeKind;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Pure translation for the diagram layer: node-id ↔ IRI, entity ↔ DTO, the overlay merge, and both
 * ReactFlow wire-type mappings ({@code ConceptType} → node type, {@link DiagramEdgeKind} → edge type). No
 * persistence or fan-out. Every wire-name translation belongs here, so there is one place to look when a
 * name differs across layers. See {@code docs/DIAGRAM_LAYER.md}.
 */
@Component
public class DiagramMapper {

    /** Every node's wire id is {@code iri:<full-iri>}, stable across reloads. */
    public static final String NODE_ID_PREFIX = "iri:";

    /** The wire node id for a concept IRI. */
    public String nodeId(String conceptIri) {
        return NODE_ID_PREFIX + conceptIri;
    }

    /** The concept IRI behind a wire node id; an unprefixed input is returned unchanged. */
    public String conceptIriFromNodeId(String nodeId) {
        if (nodeId != null && nodeId.startsWith(NODE_ID_PREFIX)) {
            return nodeId.substring(NODE_ID_PREFIX.length());
        }
        return nodeId;
    }

    /** ReactFlow node-type string for a concept type; a null type falls back to the generic node. */
    public String nodeType(ConceptType type) {
        if (type == null) {
            return "conceptNode";
        }
        return switch (type) {
            case TRIDA -> "classNode";
            case VLASTNOST -> "propertyNode";
            case VZTAH -> "relationNode";
            // A roleless concept has no specific diagram shape.
            case KONCEPT -> "conceptNode";
        };
    }

    /**
     * ReactFlow edge-type string for an edge kind. Not 1:1 with the enum: {@code SUBCLASS_OF} and
     * {@code EXACT_MATCH} are both bare triples between two classes and share one renderer, while a VZTAH
     * carries its own concept identity and gets its own. That collapse is why the mapping lives here rather
     * than being derived from the enum name.
     */
    public String edgeType(DiagramEdgeKind kind) {
        return kind == DiagramEdgeKind.VZTAH ? "relationEdge" : "hierarchyEdge";
    }

    /** Saved pan and zoom, or null when the canvas has never been saved. */
    public ViewportDto toViewport(DiagramEntity diagram) {
        if (diagram.getViewportX() == null && diagram.getViewportY() == null && diagram.getViewportZoom() == null) {
            return null;
        }
        return new ViewportDto(diagram.getViewportX(), diagram.getViewportY(), diagram.getViewportZoom());
    }

    /** A node's canvas position. */
    public PositionDto toPosition(DiagramNodeEntity node) {
        return new PositionDto(node.getPosX(), node.getPosY());
    }

    /** The wire overlay entry as the persisted overlay model, without a stale-base fingerprint yet. */
    public DiagramPendingEdit toPendingEdit(DiagramLayoutDto.Overlay dto) {
        if (dto == null || dto.isEmpty()) {
            return null;
        }
        DiagramPendingEdit edit = new DiagramPendingEdit();
        edit.setDomain(dto.domain());
        edit.setRange(dto.range());
        edit.setBroaderConcept(dto.broaderConcept());
        edit.setExactMatch(dto.exactMatch());
        if (dto.convertToHierarchy() != null) {
            DiagramPendingEdit.ConvertToHierarchy c = new DiagramPendingEdit.ConvertToHierarchy();
            c.setAddBroaderOn(dto.convertToHierarchy().addBroaderOn());
            c.setBroader(dto.convertToHierarchy().broader());
            edit.setConvertToHierarchy(c);
        }
        return edit;
    }

    /**
     * Merges live concept content with a node's overlay into the render-ready {@code NodeData}. The overlay
     * fields override live values; the label is always live-only. {@code detail} is null when the concept
     * was deleted underneath the node.
     *
     * <p><b>One flag, three names.</b> {@code diagram_nodes.is_foreign} → {@link DiagramNodeEntity#isForeign}
     * → {@code data.readOnly} on the wire. The rename happens here and nowhere else, so this is the only
     * place the chain is visible. The storage name states the fact — the concept belongs to another
     * ontology — and the wire name states the consequence the FE acts on: render it, never let it be edited.
     *
     * <p>The drift has already cost a bug: the read emitted {@code data.readOnly} while the write expected
     * {@code isForeign}, so the flag never round-tripped. It cannot recur — foreignness is now derived
     * server-side from the concept's own graph and is not accepted on write at all — but any new field
     * crossing these layers should either keep one name throughout or be called out here the same way.
     */
    public DiagramDto.NodeData toNodeData(DiagramNodeEntity node,
                                          ConceptType conceptType,
                                          String slug,
                                          Map<String, String> label,
                                          ConceptDetailModel detail,
                                          List<DiagramDto.PropertyRow> properties,
                                          DiagramPendingEdit overlay,
                                          boolean unavailable) {
        boolean hasPendingEdits = overlay != null;
        // Absent because its graph could not be read is `unavailable`, not deleted; the two never coincide.
        boolean stale = detail == null && !unavailable;
        return new DiagramDto.NodeData(
                conceptType,
                node.getConceptIri(),
                slug,
                label,
                stale,
                unavailable,
                hasPendingEdits,
                overlay,
                properties != null ? properties : List.of(),
                node.isForeign());
    }
}
