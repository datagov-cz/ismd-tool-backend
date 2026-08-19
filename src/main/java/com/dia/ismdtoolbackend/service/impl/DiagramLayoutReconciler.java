package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.PositionDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEdgeEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Save-time full-replace: the incoming layout is authoritative for canvas membership. Splits into two
 * steps around the caller's flush — {@link #reconcileNodes} inserts/updates/removes node rows, and after the
 * caller flushes so new rows have identity, {@link #finalizeLayout} sets viewport, resolves parents, and
 * rebuilds the persisted edge rows. Operates on managed instances via {@link DiagramEntity}'s aggregate
 * helpers. See {@code docs/DIAGRAM_LAYER.md}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiagramLayoutReconciler {

    private final DiagramMapper mapper;
    private final ConceptMetadataRepository conceptMetadataRepository;

    /**
     * Reconcile the node set: update/keep matching rows, insert rows for new IRIs, remove any persisted node
     * absent from the payload. Returns the incoming nodes keyed by concept IRI, for parent resolution
     * after the caller flushes.
     */
    public Map<String, DiagramNodeEntity> reconcileNodes(DiagramEntity diagram, DiagramLayoutDto layout) {
        Map<String, DiagramNodeEntity> existing = new HashMap<>();
        for (DiagramNodeEntity n : diagram.getNodes()) {
            existing.put(n.getConceptIri(), n);
        }

        String diagramGraphName = diagram.getOntologyMetadata().getGraphName();
        Map<String, DiagramNodeEntity> incoming = new HashMap<>();
        for (DiagramLayoutDto.Node in : layout.nodes()) {
            String iri = mapper.conceptIriFromNodeId(in.id());
            requireSameGraph(diagramGraphName, iri);
            DiagramNodeEntity node = existing.get(iri);
            if (node == null) {
                node = new DiagramNodeEntity();
                node.setConceptIri(iri);
                diagram.addNode(node);
            }
            applyNodeLayout(node, in);
            incoming.put(iri, node);
        }

        // A row carrying a staged overlay survives an omission from nodes[]. Relationships and properties
        // are never sent as nodes (they render as edges and rows), so reaping on absence alone would delete
        // the row the overlay lives on — silently discarding the user's staged change and the work item
        // Převzít would have applied. Discarding an overlay is an explicit PATCH, never a side effect.
        List<DiagramNodeEntity> toRemove = diagram.getNodes().stream()
                .filter(n -> !incoming.containsKey(n.getConceptIri()))
                .filter(n -> n.getPendingEdit() == null)
                .toList();
        toRemove.forEach(diagram::removeNode);
        return incoming;
    }

    /**
     * A node may only reference a concept in the diagram's own ontology graph. Save authorizes the ontology
     * slug, so persisting a foreign IRI here would stage a write the caller was never authorized for — the
     * canvas is the ingress for every later materialize.
     *
     * <p>An IRI with no concept row is NOT rejected: a node whose concept was deleted out from under the
     * canvas is a legitimate state that Převzít reports as {@code skippedStale}, and failing the whole save
     * would strand the user with an unsaveable canvas. Only a row in a <em>different</em> graph is foreign.
     */
    private void requireSameGraph(String diagramGraphName, String conceptIri) {
        String graphName = conceptMetadataRepository.findByConceptIri(conceptIri)
                .map(ConceptMetadataEntity::getGraphName)
                .orElse(null);
        if (graphName != null && !java.util.Objects.equals(diagramGraphName, graphName)) {
            log.warn("Rejected diagram node {} (graph {}) on a diagram for graph {}",
                    conceptIri, graphName, diagramGraphName);
            throw new ConceptValidationException(
                    "Pojem " + conceptIri + " nepatří do slovníku tohoto diagramu.");
        }
    }

    /**
     * Finish the layout once every incoming node has an identity: set viewport, resolve each
     * node's {@code parentNodeId}, and rebuild the persisted edge rows.
     */
    public void finalizeLayout(DiagramEntity diagram, DiagramLayoutDto layout,
                               Map<String, DiagramNodeEntity> incoming) {
        applyViewport(diagram, layout);
        resolveParents(layout, incoming);
        reconcileEdges(diagram, layout);
    }

    private void applyViewport(DiagramEntity diagram, DiagramLayoutDto layout) {
        if (layout.viewport() != null) {
            diagram.setViewportX(layout.viewport().x());
            diagram.setViewportY(layout.viewport().y());
            diagram.setViewportZoom(layout.viewport().zoom());
        }
    }

    private void applyNodeLayout(DiagramNodeEntity node, DiagramLayoutDto.Node in) {
        PositionDto pos = in.position();
        node.setPosX(pos.x());
        node.setPosY(pos.y());
        node.setCollapsed(in.collapsed());
    }

    private void resolveParents(DiagramLayoutDto layout, Map<String, DiagramNodeEntity> incoming) {
        for (DiagramLayoutDto.Node in : layout.nodes()) {
            DiagramNodeEntity node = incoming.get(mapper.conceptIriFromNodeId(in.id()));
            node.setParentNodeId(resolveParentRowId(in.parentId(), incoming));
        }
    }

    private Long resolveParentRowId(String parentWireId, Map<String, DiagramNodeEntity> incoming) {
        if (parentWireId == null) {
            return null;
        }
        DiagramNodeEntity parent = incoming.get(mapper.conceptIriFromNodeId(parentWireId));
        return parent != null ? parent.getId() : null;
    }

    /**
     * Full-replace the persisted waypoint rows; read always re-projects the edges themselves. An edge
     * carrying no waypoints stores nothing — there is nothing to remember about default routing.
     *
     * <p>Reconciled <em>in place</em>, never cleared-and-reinserted: with {@code orphanRemoval} Hibernate
     * emits the INSERT before the DELETE in one flush, so re-saving a still-routed edge would collide with
     * the {@code (diagram_id, edge_key)} unique constraint — a 500 on the second save of any canvas that
     * has ever had a waypoint drawn. Matching rows are updated, absent ones removed.
     */
    private void reconcileEdges(DiagramEntity diagram, DiagramLayoutDto layout) {
        Map<String, DiagramEdgeEntity> existing = new HashMap<>();
        for (DiagramEdgeEntity e : diagram.getEdges()) {
            existing.put(e.getEdgeKey(), e);
        }

        Set<String> incoming = new HashSet<>();
        if (layout.edges() != null) {
            for (DiagramLayoutDto.Edge in : layout.edges()) {
                if (in.segments() == null || in.segments().isEmpty()) {
                    continue;
                }
                if (!incoming.add(in.id())) {
                    log.warn("Ignoring duplicate waypoints for diagram edge {}", in.id());
                    continue;
                }
                DiagramEdgeEntity edge = existing.get(in.id());
                if (edge == null) {
                    edge = new DiagramEdgeEntity();
                    edge.setEdgeKey(in.id());
                    diagram.addEdge(edge);
                }
                edge.setSegments(in.segments());
            }
        }

        diagram.getEdges().removeIf(e -> !incoming.contains(e.getEdgeKey()));
    }
}
