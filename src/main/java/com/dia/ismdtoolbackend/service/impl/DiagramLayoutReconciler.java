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
import java.util.Set;

/**
 * The Save-time reconcile: the incoming layout is authoritative for canvas membership, while
 * {@code overlays[]} is additive over whatever is already staged. Splits into two steps around the caller's
 * flush: {@link #reconcileNodes} inserts/updates/removes node rows and applies the staged overlays, then
 * {@link #finalizeLayout} sets viewport, resolves parents, and rebuilds the persisted edge rows. Layout and
 * staged edits live in separate tables — a Save carries both, neither constrains the other. See
 * {@code docs/DIAGRAM_LAYER.md}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiagramLayoutReconciler {

    private final DiagramMapper mapper;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final DiagramPendingEditRepository pendingEditRepository;

    /**
     * Reconcile the node set: update/keep matching rows, insert rows for new IRIs, apply the staged
     * overlays, and remove any persisted node absent from the payload. Returns the incoming nodes keyed by
     * concept IRI, for parent resolution after the caller flushes.
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
            // Resolved BEFORE the row is attached: the lookup auto-flushes, and a node added but not yet
            // given its position would be flushed with a null pos_x.
            boolean foreign = isForeign(diagramGraphName, iri);
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

        applyOverlays(diagram, diagram.getOntologyMetadata(), layout, diagramGraphName);

        // Membership is a plain full replace: a row absent from nodes[] is off the canvas.
        List<DiagramNodeEntity> toRemove = diagram.getNodes().stream()
                .filter(n -> !incoming.containsKey(n.getConceptIri()))
                .toList();
        toRemove.forEach(diagram::removeNode);
        return incoming;
    }

    /**
     * Apply {@code overlays[]} — additive, never a full replace: an entry stages or updates that concept's
     * overlay, and a concept absent from the array is untouched, so a null or empty array is a no-op.
     * Discarding is an entry carrying only {@code conceptIri}, and deletes the row.
     *
     * <p>Writes {@code diagram_pending_edits} only, never layout.
     */
    private void applyOverlays(DiagramEntity diagram, OntologyMetadataEntity ontology,
                               DiagramLayoutDto layout, String diagramGraphName) {
        if (layout.overlays() == null) {
            return;
        }

        Set<String> seen = new HashSet<>();
        for (DiagramLayoutDto.Overlay in : layout.overlays()) {
            String iri = mapper.conceptIriFromNodeId(in.conceptIri());
            if (!seen.add(iri)) {
                log.warn("Ignoring duplicate overlay entry for concept {}", iri);
                continue;
            }
            // Every overlay subject is graph-checked, not just a newly staged one.
            requireSameGraph(diagramGraphName, iri);

            DiagramPendingEdit edit = mapper.toPendingEdit(in);
            DiagramPendingEditEntity existing = pendingEditRepository
                    .findByDiagramIdAndConceptIri(diagram.getId(), iri)
                    .orElse(null);

            if (edit == null) {
                // Discard: delete the row. Discarding what was never staged is a no-op.
                if (existing != null) {
                    pendingEditRepository.delete(existing);
                }
                continue;
            }
            requireSameGraph(diagramGraphName, edit);

            // Stamp the stale-base fingerprint only when the edit first comes into existence — refreshing
            // it on every save would absorb a concurrent concept edit instead of reporting it.
            DiagramPendingEditEntity row = existing;
            if (row == null) {
                row = new DiagramPendingEditEntity();
                row.setDiagram(diagram);
                row.setOntologyMetadata(ontology);
                row.setConceptIri(iri);
                row.setBaseUpdatedAt(baseUpdatedAt(iri));
            }
            edit.setBaseUpdatedAt(row.getBaseUpdatedAt());
            row.setPendingEdit(edit);
            pendingEditRepository.save(row);
        }
    }

    /** The referenced concept's {@code updatedAt} at stage time — null when it has no row. */
    private LocalDateTime baseUpdatedAt(String conceptIri) {
        return conceptMetadataRepository.findByConceptIri(conceptIri)
                .map(ConceptMetadataEntity::getUpdatedAt)
                .orElse(null);
    }

    /**
     * Graph-check an overlay's endpoint IRIs. They are asymmetric, because an endpoint is either a
     * concept this edit WRITES or merely one it POINTS AT:
     *
     * <ul>
     *   <li>{@code domain} is the class the VZTAH/VLASTNOST hangs off — the origin of the link, which
     *       must be ours. A foreign domain is the cross-tenant reach the guard exists to stop.</li>
     *   <li>{@code range} and the hierarchy targets ({@code broaderConcept}, {@code exactMatch}) are
     *       pure references: they become the OBJECT of a triple written into our own graph, so a foreign
     *       one is exactly the link the foreign-node feature exists to draw.</li>
     *   <li>Op 6's {@code addBroaderOn} is edited and must be ours; its {@code broader} is only
     *       referenced and may be foreign. They need never be on the canvas, which makes them the one
     *       overlay input reaching {@code editConcept}/{@code deleteConcept} on their own.</li>
     * </ul>
     *
     * <p>Rejected here at stage time; the applier re-asserts it at Převzít.
     */
    private void requireSameGraph(String diagramGraphName, DiagramPendingEdit edit) {
        requireSameGraph(diagramGraphName, edit.getDomain());

        DiagramPendingEdit.ConvertToHierarchy marker = edit.getConvertToHierarchy();
        if (marker == null) {
            return;
        }
        requireSameGraph(diagramGraphName, marker.getAddBroaderOn());
    }

    /**
     * Whether this IRI belongs to an ontology other than the diagram's — derived, never taken from the
     * client, so a node can be neither falsely marked read-only nor falsely made editable. A concept with
     * no PG row is an NKD or otherwise external IRI, foreign by construction.
     *
     * <p>Placement is unrestricted; it is the overlay targets that stay strictly own-graph
     * ({@link #requireSameGraph}), so a foreign concept can be referenced but never edited.
     */
    private boolean isForeign(String diagramGraphName, String conceptIri) {
        if (conceptIri == null) {
            return false;
        }
        String graphName = conceptMetadataRepository.findByConceptIri(conceptIri)
                .map(ConceptMetadataEntity::getGraphName)
                .orElse(null);
        return !java.util.Objects.equals(diagramGraphName, graphName);
    }

    /**
     * The IRI must reference a concept in the diagram's own ontology graph; Save authorizes the slug only,
     * so a foreign IRI here would stage a write the caller was never authorized for.
     *
     * <p>An IRI with no concept row passes: its concept was deleted out from under the canvas, which
     * Převzít reports as {@code skippedStale}. Only a row in a <em>different</em> graph is foreign.
     */
    private void requireSameGraph(String diagramGraphName, String conceptIri) {
        // A null op-6 marker endpoint is left to materialize, which reports it as a VALIDATION failure.
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

    private void applyNodeLayout(DiagramNodeEntity node, DiagramLayoutDto.Node in, boolean foreign) {
        node.setForeign(foreign);
        PositionDto pos = in.position();
        node.setPosX(pos.x());
        node.setPosY(pos.y());
        node.setCollapsed(in.collapsed());
        node.setVisibleProperties(in.properties().stream()
                .map(mapper::conceptIriFromNodeId)
                .distinct()
                .toList());
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
     * Reconcile edge membership: a row means "this edge is on the canvas", and {@code segments_json} is
     * that row's optional routing. Authoritative like {@code nodes[]} — an edge omitted from a present
     * {@code edges[]} leaves the canvas and its waypoints go with it. A null {@code edges[]} is a no-op,
     * so a client that never touches edges cannot clear them by omission.
     *
     * <p>{@code segments} is three-way per entry: null/omitted KEEPS what is stored (the entry is about
     * membership, not routing), {@code []} clears to default routing, a list sets it. Omitting it used to
     * delete the row, which silently discarded a user's hand-drawn geometry on every save that did not
     * echo it back.
     *
     * <p>Reconciled <em>in place</em>, never cleared-and-reinserted: Hibernate flushes the INSERT before
     * the orphan DELETE, so re-saving a surviving edge would collide with the
     * {@code (diagram_id, edge_key)} unique constraint.
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
            // Null means "not saying anything about routing" — keep whatever this row already holds.
            if (in.segments() != null) {
                edge.setSegments(in.segments());
            }
        }

        diagram.getEdges().removeIf(e -> !incoming.contains(e.getEdgeKey()));
    }
}
