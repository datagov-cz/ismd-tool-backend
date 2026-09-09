package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.PositionDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEdgeEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.entity.DiagramPendingEditEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The Save-time reconcile. The incoming layout is authoritative for canvas membership; {@code overlays[]}
 * is additive over what is already staged. Runs in two steps around the caller's flush:
 * {@link #reconcileNodes} writes node rows and overlays, then {@link #finalizeLayout} sets the viewport,
 * resolves parents and rebuilds edge rows. See {@code docs/DIAGRAM_LAYER.md}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiagramLayoutReconciler {

    private final DiagramMapper mapper;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final DiagramPendingEditRepository pendingEditRepository;

    /**
     * Reconciles the node set — updates matching rows, inserts new IRIs, applies overlays and removes rows
     * absent from the payload. Returns the incoming nodes by IRI, for parent resolution after the flush.
     */
    public Map<String, DiagramNodeEntity> reconcileNodes(DiagramEntity diagram, DiagramLayoutDto layout) {
        Map<String, DiagramNodeEntity> existing = new HashMap<>();
        for (DiagramNodeEntity n : diagram.getNodes()) {
            existing.put(n.getConceptIri(), n);
        }

        String diagramGraphName = diagram.getOntologyMetadata().getGraphName();
        ConceptScope scope = prefetchScope(layout);

        Map<String, DiagramNodeEntity> incoming = new HashMap<>();
        for (DiagramLayoutDto.Node in : layout.nodes()) {
            String iri = mapper.conceptIriFromNodeId(in.id());
            boolean foreign = scope.isForeign(diagramGraphName, iri);
            DiagramNodeEntity node = existing.get(iri);
            if (node == null) {
                node = new DiagramNodeEntity();
                node.setConceptIri(iri);
                diagram.addNode(node);
                existing.put(iri, node);
            }
            applyNodeLayout(node, in, foreign);
            incoming.put(iri, node);
        }

        applyOverlays(diagram, diagram.getOntologyMetadata(), layout, diagramGraphName, scope);

        // Membership is a full replace: a row absent from nodes[] is off the canvas.
        List<DiagramNodeEntity> toRemove = diagram.getNodes().stream()
                .filter(n -> !incoming.containsKey(n.getConceptIri()))
                .toList();
        toRemove.forEach(diagram::removeNode);
        return incoming;
    }

    /**
     * Every concept row one save needs, read once. A concept absent from the map has no PG row at all —
     * an NKD or otherwise external IRI — which is foreign by construction and carries no fingerprint.
     */
    private record ConceptScope(Map<String, ConceptMetadataEntity> byIri) {

        /**
         * Whether this IRI belongs to an ontology other than the diagram's. Derived, never taken from the
         * client, so a node can be neither falsely marked read-only nor falsely made editable; a concept
         * with no PG row is external and foreign by construction. Placement is unrestricted — only the
         * overlay targets stay own-graph ({@code requireSameGraph}), so a foreign concept is referenced,
         * never edited.
         */
        boolean isForeign(String diagramGraphName, String conceptIri) {
            if (conceptIri == null) {
                return false;
            }
            return !Objects.equals(diagramGraphName, graphOf(conceptIri));
        }

        /** The concept's graph, or null when it has no PG row. */
        String graphOf(String conceptIri) {
            ConceptMetadataEntity concept = byIri.get(conceptIri);
            return concept != null ? concept.getGraphName() : null;
        }

        /** The concept's {@code updatedAt} at stage time; null when it has no row. */
        LocalDateTime updatedAtOf(String conceptIri) {
            ConceptMetadataEntity concept = byIri.get(conceptIri);
            return concept != null ? concept.getUpdatedAt() : null;
        }
    }

    /**
     * Resolves every concept IRI this save can ask about — the incoming nodes, each overlay's subject, and
     * the overlay endpoints {@link #requireSameGraph} checks — in a single {@code IN} query.
     */
    private ConceptScope prefetchScope(DiagramLayoutDto layout) {
        Set<String> iris = new HashSet<>();
        for (DiagramLayoutDto.Node in : layout.nodes()) {
            addIri(iris, in.id());
        }
        if (layout.overlays() != null) {
            for (DiagramLayoutDto.Overlay in : layout.overlays()) {
                addIri(iris, in.conceptIri());
                // Only the WRITTEN endpoints are graph-checked; a referenced one may legitimately be
                // foreign, so prefetching it would be a wasted lookup.
                addIri(iris, in.domain());
                if (in.convertToHierarchy() != null) {
                    addIri(iris, in.convertToHierarchy().addBroaderOn());
                }
            }
        }
        if (iris.isEmpty()) {
            return new ConceptScope(Map.of());
        }
        Map<String, ConceptMetadataEntity> byIri = new HashMap<>();
        for (ConceptMetadataEntity c : conceptMetadataRepository.findByConceptIriIn(List.copyOf(iris))) {
            byIri.put(c.getConceptIri(), c);
        }
        return new ConceptScope(byIri);
    }

    private void addIri(Set<String> iris, String rawIri) {
        if (rawIri != null) {
            iris.add(mapper.conceptIriFromNodeId(rawIri));
        }
    }

    /**
     * Applies {@code overlays[]}, additive rather than a full replace: an entry stages or updates that
     * concept's overlay, a concept absent from the array is untouched, and an entry carrying only
     * {@code conceptIri} discards it. Writes {@code diagram_pending_edits} only, never layout.
     */
    private void applyOverlays(DiagramEntity diagram, OntologyMetadataEntity ontology,
                               DiagramLayoutDto layout, String diagramGraphName, ConceptScope scope) {
        if (layout.overlays() == null) {
            return;
        }

        // The diagram's staged rows in one read, rather than a lookup per entry.
        Map<String, DiagramPendingEditEntity> staged = new HashMap<>();
        for (DiagramPendingEditEntity row : pendingEditRepository.findByDiagramId(diagram.getId())) {
            staged.put(row.getConceptIri(), row);
        }

        Set<String> seen = new HashSet<>();
        for (DiagramLayoutDto.Overlay in : layout.overlays()) {
            String iri = mapper.conceptIriFromNodeId(in.conceptIri());
            if (!seen.add(iri)) {
                log.warn("Ignoring duplicate overlay entry for concept {}", iri);
                continue;
            }
            // Every overlay subject is graph-checked, not only a newly staged one.
            requireSameGraph(diagramGraphName, iri, scope);

            DiagramPendingEdit edit = mapper.toPendingEdit(in);
            DiagramPendingEditEntity existing = staged.get(iri);

            if (edit == null) {
                // Discard; discarding what was never staged is a no-op.
                if (existing != null) {
                    pendingEditRepository.delete(existing);
                }
                continue;
            }
            requireSameGraph(diagramGraphName, edit, scope);

            // The stale-base fingerprint is stamped only at first stage; refreshing it on every save
            // would absorb a concurrent concept edit instead of reporting it.
            DiagramPendingEditEntity row = existing;
            if (row == null) {
                row = new DiagramPendingEditEntity();
                row.setDiagram(diagram);
                row.setOntologyMetadata(ontology);
                row.setConceptIri(iri);
                row.setBaseUpdatedAt(scope.updatedAtOf(iri));
            }
            edit.setBaseUpdatedAt(row.getBaseUpdatedAt());
            row.setPendingEdit(edit);
            pendingEditRepository.save(row);
        }
    }

    /**
     * Graph-checks an overlay's endpoint IRIs. Endpoints are asymmetric — a written concept must be ours,
     * a merely referenced one may be foreign:
     *
     * <ul>
     *   <li>{@code domain} is the class the VZTAH/VLASTNOST hangs off, so it must be ours.</li>
     *   <li>{@code range} and the hierarchy targets ({@code broaderConcept}, {@code exactMatch}) become
     *       the object of a triple in our own graph, so a foreign one is the intended cross-link.</li>
     *   <li>Op 6's {@code addBroaderOn} is edited and must be ours; its {@code broader} may be foreign.</li>
     * </ul>
     *
     * <p>Rejected here at stage time; the applier re-asserts it at Převzít.
     */
    private void requireSameGraph(String diagramGraphName, DiagramPendingEdit edit, ConceptScope scope) {
        requireSameGraph(diagramGraphName, edit.getDomain(), scope);

        DiagramPendingEdit.ConvertToHierarchy marker = edit.getConvertToHierarchy();
        if (marker == null) {
            return;
        }
        requireSameGraph(diagramGraphName, marker.getAddBroaderOn(), scope);
    }

    /**
     * Requires the IRI to reference a concept in the diagram's own graph; Save authorizes the slug only, so
     * a foreign IRI would stage an unauthorized write. An IRI with no concept row passes — it was deleted
     * out from under the canvas, which Převzít reports as {@code skippedStale}.
     */
    private void requireSameGraph(String diagramGraphName, String conceptIri, ConceptScope scope) {
        // A null op-6 marker endpoint is left to materialize, which reports it as a VALIDATION failure.
        if (conceptIri == null) {
            return;
        }
        String graphName = scope.graphOf(conceptIri);
        if (graphName != null && !Objects.equals(diagramGraphName, graphName)) {
            log.warn("Rejected diagram node {} (graph {}) on a diagram for graph {}",
                    conceptIri, graphName, diagramGraphName);
            throw new ConceptValidationException(
                    "Pojem " + conceptIri + " nepatří do slovníku tohoto diagramu.");
        }
    }

    /**
     * Finishes the layout once every incoming node has an identity: viewport, {@code parentNodeId}
     * resolution and the persisted edge rows.
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

    private void applyNodeLayout(DiagramNodeEntity node, DiagramLayoutDto.Node in, boolean foreign) {
        node.setForeign(foreign);
        PositionDto pos = in.position();
        node.setPosX(pos.x());
        node.setPosY(pos.y());
        node.setCollapsed(in.collapsed());
        // Null is a no-op, so a client that does not manage property visibility keeps what is stored;
        // an explicit [] clears the rows.
        if (in.visibleProperties() != null) {
            node.setVisibleProperties(in.visibleProperties().stream()
                    .map(mapper::conceptIriFromNodeId)
                    .distinct()
                    .toList());
        }
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
     * Reconciles edge membership: a row means the edge is on the canvas, and {@code segments_json} is its
     * optional routing. Authoritative like {@code nodes[]} — an edge omitted from a present {@code edges[]}
     * leaves the canvas with its waypoints. A null {@code edges[]} is a no-op, so a client that never
     * touches edges cannot clear them by omission.
     *
     * <p>{@code segments} is three-way per entry: null keeps what is stored, {@code []} clears to default
     * routing, a list sets it.
     *
     * <p>Reconciled in place, never cleared-and-reinserted: Hibernate flushes the INSERT before the orphan
     * DELETE, so re-saving a surviving edge would collide with the {@code (diagram_id, edge_key)} unique
     * constraint.
     */
    private void reconcileEdges(DiagramEntity diagram, DiagramLayoutDto layout) {
        if (layout.edges() == null) {
            return;
        }

        Map<String, DiagramEdgeEntity> existing = new HashMap<>();
        for (DiagramEdgeEntity e : diagram.getEdges()) {
            existing.put(e.getEdgeKey(), e);
        }

        Set<String> incoming = new HashSet<>();
        for (DiagramLayoutDto.Edge in : layout.edges()) {
            if (!incoming.add(in.id())) {
                log.warn("Ignoring duplicate entry for diagram edge {}", in.id());
                continue;
            }
            DiagramEdgeEntity edge = existing.get(in.id());
            if (edge == null) {
                edge = new DiagramEdgeEntity();
                edge.setEdgeKey(in.id());
                diagram.addEdge(edge);
            }
            // Null says nothing about routing, so keep what the row already holds.
            if (in.segments() != null) {
                edge.setSegments(in.segments());
            }
        }

        diagram.getEdges().removeIf(e -> !incoming.contains(e.getEdgeKey()));
    }
}
