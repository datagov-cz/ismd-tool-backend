package com.dia.ismdtoolbackend.mapper;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.NodeOverlayDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.PositionDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.ViewportDto;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Pure translation for the diagram layer: node-id ↔ IRI, entity ↔ DTO, the overlay merge, and the
 * {@code ConceptType} → ReactFlow node-type mapping. No persistence or fan-out — that lives in the
 * service. See {@code docs/DIAGRAM_LAYER.md}.
 */
@Component
public class DiagramMapper {

    /** Every node's wire id is {@code iri:<full-iri>} (stable across reloads; all nodes reference a concept). */
    public static final String NODE_ID_PREFIX = "iri:";

    /** Build the wire node id for a concept IRI. */
    public String nodeId(String conceptIri) {
        return NODE_ID_PREFIX + conceptIri;
    }

    /** Extract the concept IRI from a wire node id; returns the input unchanged if unprefixed. */
    public String conceptIriFromNodeId(String nodeId) {
        if (nodeId != null && nodeId.startsWith(NODE_ID_PREFIX)) {
            return nodeId.substring(NODE_ID_PREFIX.length());
        }
        return nodeId;
    }

    /** ReactFlow node-type string for a concept type. */
    public String nodeType(ConceptType type) {
        if (type == null) {
            return "conceptNode";
        }
        return switch (type) {
            case TRIDA -> "classNode";
            case VLASTNOST -> "propertyNode";
            case VZTAH -> "relationNode";
        };
    }

    /** Saved pan/zoom, or null when the canvas has never been saved. */
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

    /** Convert the wire overlay body to the persisted overlay model (no stale-base fingerprint yet). */
    public DiagramPendingEdit toPendingEdit(NodeOverlayDto dto) {
        if (dto == null || dto.isEmpty()) {
            return null;
        }
        DiagramPendingEdit edit = new DiagramPendingEdit();
        edit.setDomain(dto.domain());
        edit.setRange(dto.range());
        edit.setBroaderConcept(dto.broaderConcept());
        edit.setSuperProperty(dto.superProperty());
        edit.setSuperRelation(dto.superRelation());
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
     * Merge live concept content with a node's overlay into the render-ready {@code NodeData}. The overlay
     * fields (domain/range/hierarchy/exactMatch) override live values; label is always live-only.
     * {@code detail} is null when the concept is stale (deleted underneath the node).
     */
    public DiagramDto.NodeData toNodeData(DiagramNodeEntity node,
                                          ConceptType conceptType,
                                          String slug,
                                          Map<String, String> label,
                                          ConceptDetailModel detail) {
        DiagramPendingEdit overlay = node.getPendingEdit();
        boolean hasPendingEdits = overlay != null;
        boolean stale = detail == null;
        return new DiagramDto.NodeData(
                conceptType,
                node.getConceptIri(),
                slug,
                label,
                stale,
                hasPendingEdits,
                overlay);
    }
}
