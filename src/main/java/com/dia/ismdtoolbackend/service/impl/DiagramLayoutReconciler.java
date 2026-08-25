package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.PositionDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEdgeEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Save-time reconcile: the incoming layout is authoritative for canvas membership, while
 * {@code overlays[]} is additive over whatever is already staged. Splits into two steps around the caller's
 * flush — {@link #reconcileNodes} inserts/updates/removes node rows and applies the staged overlays, and
 * after the caller flushes so new rows have identity, {@link #finalizeLayout} sets viewport, resolves
 * parents, and rebuilds the persisted edge rows. Operates on managed instances via {@link DiagramEntity}'s
 * aggregate helpers. See {@code docs/DIAGRAM_LAYER.md}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiagramLayoutReconciler {

    private final DiagramMapper mapper;
    private final ConceptMetadataRepository conceptMetadataRepository;

    /**
     * Reconcile the node set: update/keep matching rows, insert rows for new IRIs, apply the staged
     * overlays, and remove any persisted node absent from the payload. Returns the incoming nodes keyed by
     * concept IRI, for parent resolution after the caller flushes.
     *
     * <p>Order is load-bearing: {@code nodes[]} first, then {@code overlays[]}, then the reap. An overlay
     * provisions a row only when none exists, so a concept in both arrays keeps its real position instead
     * of the origin anchor. The reap runs last so a row provisioned by an overlay is never a reap
     * candidate.
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
                existing.put(iri, node);
            }
            applyNodeLayout(node, in);
            incoming.put(iri, node);
        }

        Set<String> overlayTargets = applyOverlays(diagram, layout, existing, diagramGraphName);

        // A row carrying a staged overlay survives an omission from nodes[]. Relationships and properties
        // are never sent as nodes (they render as edges and rows), so reaping on absence alone would delete
        // the row the overlay lives on — silently discarding the user's staged change and the work item
        // Převzít would have applied. Discarding an overlay is an explicit entry, never a side effect.
        List<DiagramNodeEntity> toRemove = diagram.getNodes().stream()
                .filter(n -> !incoming.containsKey(n.getConceptIri()))
                .filter(n -> !overlayTargets.contains(n.getConceptIri()))
                .filter(n -> n.getPendingEdit() == null)
                .toList();
        toRemove.forEach(diagram::removeNode);
        return incoming;
    }

    /**
     * Apply {@code overlays[]} — additive, never a full replace. An entry stages or updates that concept's
     * overlay; a concept absent from the array is untouched, so a null or empty array is a no-op. This is
     * deliberately unlike {@code nodes}/{@code edges}: a staged overlay need not appear in a read at all
     * (an off-canvas endpoint, or a concept deleted underneath the diagram), so a client cannot be asked to
     * echo back what it was never shown. Treating omission as discard would destroy staged work on every
     * save a client builds from its own canvas state.
     *
     * <p>Returns the concept IRIs the payload addressed, so the caller can keep a row it just provisioned
     * out of the reap even when the entry discarded the overlay.
     */
    private Set<String> applyOverlays(DiagramEntity diagram, DiagramLayoutDto layout,
                                      Map<String, DiagramNodeEntity> existing, String diagramGraphName) {
        if (layout.overlays() == null) {
            return Set.of();
        }

        Set<String> targets = new HashSet<>();
        for (DiagramLayoutDto.Overlay in : layout.overlays()) {
            String iri = mapper.conceptIriFromNodeId(in.conceptIri());
            if (!targets.add(iri)) {
                log.warn("Ignoring duplicate overlay entry for concept {}", iri);
                continue;
            }
            // Every overlay target is graph-checked, not just a newly provisioned one: a row that already
            // exists is the case the staging path used to skip entirely.
            requireSameGraph(diagramGraphName, iri);

            DiagramPendingEdit edit = mapper.toPendingEdit(in);
            DiagramNodeEntity node = existing.get(iri);
            if (node == null) {
                // Discarding an overlay on a concept with no row is a no-op — provisioning one just to
                // clear it would leave an empty row behind.
                if (edit == null) {
                    continue;
                }
                // A relationship renders as an edge and a property as a row, so neither travels in nodes[]
                // and neither has a row until an overlay stages one. The row exists to carry the overlay,
                // not a box, and the position columns are NOT NULL — anchor at origin.
                node = new DiagramNodeEntity();
                node.setConceptIri(iri);
                node.setPosX(0.0);
                node.setPosY(0.0);
                diagram.addNode(node);
                existing.put(iri, node);
            }

            if (edit == null) {
                node.setPendingEdit(null);
                continue;
            }
            requireSameGraph(diagramGraphName, edit);
            // Stamp the stale-base fingerprint only when the overlay comes into existence on this row.
            // Refreshing it on every save would absorb a concurrent concept edit instead of reporting it,
            // and comparing content instead would never clear a conflict the user resolves by re-staging
            // the same value: discard then stage is the reset, so `prior == null` is the only fresh stamp.
            DiagramPendingEdit prior = node.getPendingEdit();
            edit.setBaseUpdatedAt(prior == null ? baseUpdatedAt(iri) : prior.getBaseUpdatedAt());
            node.setPendingEdit(edit);
        }
        return targets;
    }

    /** The referenced concept's {@code updatedAt} at stage time — null when it has no row. */
    private LocalDateTime baseUpdatedAt(String conceptIri) {
        return conceptMetadataRepository.findByConceptIri(conceptIri)
                .map(ConceptMetadataEntity::getUpdatedAt)
                .orElse(null);
    }

    /**
     * Op 6's {@code addBroaderOn} / {@code broader} name concepts that need never be on the canvas, so they
     * are the one overlay input that can reach {@code editConcept}/{@code deleteConcept} on its own. Reject
     * a foreign target at stage time rather than letting it sit staged until Převzít. The applier
     * re-asserts this — an overlay staged before this check existed is still refused there.
     */
    private void requireSameGraph(String diagramGraphName, DiagramPendingEdit edit) {
        DiagramPendingEdit.ConvertToHierarchy marker = edit.getConvertToHierarchy();
        if (marker == null) {
            return;
        }
        requireSameGraph(diagramGraphName, marker.getAddBroaderOn());
        requireSameGraph(diagramGraphName, marker.getBroader());
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
        // Op 6's marker endpoints are not guaranteed non-null at this layer; a null one is left to
        // materialize, which reports it as a per-change VALIDATION failure rather than an NPE here.
        if (conceptIri == null) {
            return;
        }
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
