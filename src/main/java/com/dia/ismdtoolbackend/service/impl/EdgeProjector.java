package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.enums.DiagramEdgeKind;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Re-derives diagram edges from each node's {@code live ⊕ overlay} — edges are a pure projection, never
 * read from storage as truth. An edge whose endpoint comes from an unmaterialized overlay is flagged
 * {@code pending}. Only edges whose both endpoints are nodes on the canvas are emitted. Hierarchy edges
 * project from the child side's overlay so a half-staged flip has one deterministic direction. See
 * {@code docs/DIAGRAM_LAYER.md}.
 */
class EdgeProjector {

    private final DiagramMapper mapper;

    EdgeProjector(DiagramMapper mapper) {
        this.mapper = mapper;
    }

    List<DiagramDto.Edge> project(List<DiagramNodeEntity> nodes, Map<String, ConceptDetailModel> live) {
        Set<String> onCanvas = new java.util.HashSet<>();
        for (DiagramNodeEntity n : nodes) {
            onCanvas.add(n.getConceptIri());
        }

        List<DiagramDto.Edge> edges = new ArrayList<>();
        for (DiagramNodeEntity node : nodes) {
            String iri = node.getConceptIri();
            ConceptDetailModel detail = live.get(iri);
            if (detail == null) {
                continue;
            }
            DiagramPendingEdit overlay = node.getPendingEdit();

            projectDomainRange(edges, iri, detail, overlay, onCanvas);
            projectHierarchy(edges, iri, detail, overlay, onCanvas);
            projectExactMatch(edges, iri, detail, overlay, onCanvas);
        }
        return edges;
    }

    /** VZTAH/VLASTNOST: DOMAIN edge (node → subject class); VZTAH also RANGE (node → object). */
    private void projectDomainRange(List<DiagramDto.Edge> edges, String iri, ConceptDetailModel detail,
                                    DiagramPendingEdit overlay, Set<String> onCanvas) {
        boolean domainPending = overlay != null && overlay.getDomain() != null;
        String domain = domainPending ? overlay.getDomain() : detail.getDomain();
        addEdge(edges, iri, domain, DiagramEdgeKind.DOMAIN, domainPending, onCanvas);

        boolean rangePending = overlay != null && overlay.getRange() != null;
        String range = rangePending ? overlay.getRange() : detail.getRange();
        addEdge(edges, iri, range, DiagramEdgeKind.RANGE, rangePending, onCanvas);
    }

    /** TRIDA: SUBCLASS_OF; VLASTNOST: SUB_PROPERTY; VZTAH: SUB_RELATION — child → broader. */
    private void projectHierarchy(List<DiagramDto.Edge> edges, String iri, ConceptDetailModel detail,
                                  DiagramPendingEdit overlay, Set<String> onCanvas) {
        boolean subclassPending = overlay != null && overlay.getBroaderConcept() != null;
        List<String> broaderClasses = subclassPending ? overlay.getBroaderConcept() : detail.getBroaderClasses();
        addEdges(edges, iri, broaderClasses, DiagramEdgeKind.SUBCLASS_OF, subclassPending, onCanvas);

        boolean subPropPending = overlay != null && overlay.getSuperProperty() != null;
        List<String> broaderProps = subPropPending ? overlay.getSuperProperty() : detail.getBroaderProperties();
        addEdges(edges, iri, broaderProps, DiagramEdgeKind.SUB_PROPERTY, subPropPending, onCanvas);

        boolean subRelPending = overlay != null && overlay.getSuperRelation() != null;
        List<String> broaderRels = subRelPending ? overlay.getSuperRelation() : detail.getBroaderRelations();
        addEdges(edges, iri, broaderRels, DiagramEdgeKind.SUB_RELATION, subRelPending, onCanvas);
    }

    private void projectExactMatch(List<DiagramDto.Edge> edges, String iri, ConceptDetailModel detail,
                                   DiagramPendingEdit overlay, Set<String> onCanvas) {
        boolean pending = overlay != null && overlay.getExactMatch() != null;
        List<String> matches = pending ? overlay.getExactMatch() : detail.getExactMatches();
        addEdges(edges, iri, matches, DiagramEdgeKind.EXACT_MATCH, pending, onCanvas);
    }

    private void addEdges(List<DiagramDto.Edge> edges, String source, List<String> targets,
                          DiagramEdgeKind kind, boolean pending, Set<String> onCanvas) {
        if (targets == null) {
            return;
        }
        for (String target : targets) {
            addEdge(edges, source, target, kind, pending, onCanvas);
        }
    }

    private void addEdge(List<DiagramDto.Edge> edges, String source, String target,
                         DiagramEdgeKind kind, boolean pending, Set<String> onCanvas) {
        if (target == null || target.isBlank() || !onCanvas.contains(target)) {
            return;
        }
        edges.add(new DiagramDto.Edge(
                projectedEdgeId(kind, source, target),
                mapper.nodeId(source),
                mapper.nodeId(target),
                edgeType(kind),
                null,
                null,
                Map.of("type", "arrowclosed"),
                new DiagramDto.EdgeData(kind, pending)));
    }

    /**
     * Deterministic id for a projected edge, unique per (kind, source, target). Edges are re-derived on
     * every read, so a stable id lets ReactFlow keep an edge's identity (selection/animation) across reloads
     * instead of seeing a brand-new edge each time.
     */
    private String projectedEdgeId(DiagramEdgeKind kind, String source, String target) {
        return String.join("|", "edge", kind.name(), source, target);
    }

    private String edgeType(DiagramEdgeKind kind) {
        return switch (kind) {
            case DOMAIN, RANGE -> "relationEdge";
            case SUBCLASS_OF, SUB_PROPERTY, SUB_RELATION -> "hierarchyEdge";
            case EXACT_MATCH -> "exactMatchEdge";
        };
    }
}
