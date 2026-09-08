package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.DiagramEdgeKind;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Re-derives diagram edges from {@code live ⊕ overlay}: an edge's existence, kind and endpoints are a pure
 * projection, never read from storage as truth. Only waypoints are joined on from the persisted
 * {@code diagram_edges} rows, keyed by the projected edge id.
 *
 * <p>Three kinds are projected:
 * <ul>
 *   <li>{@code VZTAH} — one edge per relationship concept, from its {@code rdfs:domain} class to its
 *       {@code rdfs:range} class, carrying the concept's own identity. The relationship is the edge, not a
 *       node with an edge to each endpoint.</li>
 *   <li>{@code SUBCLASS_OF} and {@code EXACT_MATCH} — bare triples between two classes, no backing
 *       concept.</li>
 * </ul>
 *
 * <p>VLASTNOSTi are not projected: a property renders as a row inside its domain class (see
 * {@link #propertyRows}). Sub-property and sub-relation hierarchy is not projected either, neither endpoint
 * being a node. Every edge is asserted from a concept this ontology owns — a foreign node may be an edge's
 * target, never its source. See {@code .planning/diagram-edge-model-REDESIGN.md}.
 */
class EdgeProjector {

    private final DiagramMapper mapper;
    private final Map<String, List<EdgeWaypoint>> waypoints;
    /** Staged edits by concept IRI, supplied because an edit need not have a node row. */
    private final Map<String, DiagramPendingEdit> overlays;
    /** Foreign node IRIs: valid edge targets, never edge sources. */
    private final Set<String> foreignIris;
    /**
     * The projected edge ids the user has placed on this canvas. Projection decides an edge's endpoints,
     * kind and validity; this decides whether it is drawn at all.
     */
    private final Set<String> onCanvasEdges;

    EdgeProjector(DiagramMapper mapper, Map<String, List<EdgeWaypoint>> waypoints,
                  Map<String, DiagramPendingEdit> overlays, Set<String> foreignIris,
                  Set<String> onCanvasEdges) {
        this.mapper = mapper;
        this.waypoints = waypoints != null ? waypoints : Map.of();
        this.overlays = overlays != null ? overlays : Map.of();
        this.foreignIris = foreignIris != null ? foreignIris : Set.of();
        this.onCanvasEdges = onCanvasEdges != null ? onCanvasEdges : Set.of();
    }

    /** True when the user has placed this edge on the canvas. */
    private boolean placed(String edgeId) {
        return onCanvasEdges.contains(edgeId);
    }

    /**
     * Projects every edge the canvas renders. {@code nodes} is canvas membership (classes) and {@code live}
     * is the whole graph, so a relationship not itself on the canvas still projects while both its endpoint
     * classes are.
     */
    List<DiagramDto.Edge> project(List<DiagramNodeEntity> nodes,
                                  Map<String, ConceptDetailModel> live,
                                  Map<String, ConceptType> types,
                                  Map<String, String> slugs) {
        Set<String> onCanvas = onCanvas(nodes, types);

        List<DiagramDto.Edge> edges = new ArrayList<>();
        for (Map.Entry<String, ConceptDetailModel> entry : live.entrySet()) {
            String iri = entry.getKey();
            ConceptDetailModel detail = entry.getValue();
            DiagramPendingEdit overlay = overlays.get(iri);

            if (isRelationship(iri, detail, types)) {
                projectRelationship(edges, iri, detail, overlay, onCanvas, slugs);
            }
            // Foreign concepts are targets only; their own triples belong to the owning graph.
            if (onCanvas.contains(iri) && !foreignIris.contains(iri)) {
                projectHierarchy(edges, iri, detail, overlay, onCanvas);
                projectExactMatch(edges, iri, detail, overlay, onCanvas);
            }
        }
        return edges;
    }

    /**
     * Canvas membership: the class nodes, the only things an edge may attach to. An unknown type is kept,
     * since a stale row whose concept was deleted is still on the canvas.
     */
    private Set<String> onCanvas(List<DiagramNodeEntity> nodes, Map<String, ConceptType> types) {
        Set<String> onCanvas = new HashSet<>();
        for (DiagramNodeEntity n : nodes) {
            ConceptType type = types.get(n.getConceptIri());
            if (type == null || type == ConceptType.TRIDA || type == ConceptType.KONCEPT) {
                onCanvas.add(n.getConceptIri());
            }
        }
        return onCanvas;
    }

    /** The property IRIs each class node has been curated to render, by class IRI. */
    private Map<String, Set<String>> curatedProperties(List<DiagramNodeEntity> nodes) {
        Map<String, Set<String>> curated = new HashMap<>();
        for (DiagramNodeEntity n : nodes) {
            List<String> visible = n.getVisibleProperties();
            if (!visible.isEmpty()) {
                curated.put(n.getConceptIri(), new HashSet<>(visible));
            }
        }
        return curated;
    }

    /**
     * A concept is a relationship when PG says so, or, when PG has no type for it, when its RDF carries both
     * a domain and a range. {@code concept_metadata.concept_type} is null for uploads whose OFN tag carries
     * no matching OWL type, so gating on it alone would drop a real VZTAH from the canvas.
     */
    private boolean isRelationship(String iri, ConceptDetailModel detail, Map<String, ConceptType> types) {
        ConceptType type = types.get(iri);
        if (type != null) {
            return type == ConceptType.VZTAH;
        }
        return detail.getDomain() != null && detail.getRange() != null;
    }

    /**
     * A VZTAH is one edge from its domain class to its range class, carrying its own concept identity. One
     * missing either endpoint, or pointing off-canvas, is not drawn.
     */
    private void projectRelationship(List<DiagramDto.Edge> edges, String iri, ConceptDetailModel detail,
                                     DiagramPendingEdit overlay, Set<String> onCanvas,
                                     Map<String, String> slugs) {
        boolean domainPending = overlay != null && overlay.getDomain() != null;
        boolean rangePending = overlay != null && overlay.getRange() != null;
        String domain = domainPending ? overlay.getDomain() : detail.getDomain();
        String range = rangePending ? overlay.getRange() : detail.getRange();

        if (drawable(domain, onCanvas) || drawable(range, onCanvas)) {
            return;
        }
        // A VZTAH edge is keyed by its own concept IRI.
        if (!placed(iri)) {
            return;
        }
        edges.add(new DiagramDto.Edge(
                iri,
                mapper.nodeId(domain),
                mapper.nodeId(range),
                "relationEdge",
                waypoints.get(iri),
                new DiagramDto.EdgeData(
                        DiagramEdgeKind.VZTAH,
                        domainPending || rangePending,
                        ConceptType.VZTAH,
                        iri,
                        slugs.get(iri),
                        detail.getName(),
                        false,
                        overlay != null,
                        overlay)));
    }

    /**
     * Groups the graph's VLASTNOSTi by their {@code rdfs:domain} (live ⊕ overlay) into the rows each class
     * node renders. A property is never a canvas object of its own — its range is a literal datatype, so
     * there is no second concept to draw an edge to.
     *
     * <p>Rows are ordered by label, falling back to the IRI so a label-less concept sorts stably. Membership
     * is curated, not derived: a property renders only when its domain class lists it in
     * {@code visibleProperties}.
     */
    Map<String, List<DiagramDto.PropertyRow>> propertyRows(List<DiagramNodeEntity> nodes,
                                                           Map<String, ConceptDetailModel> live,
                                                           Map<String, ConceptType> types,
                                                           Map<String, String> slugs) {
        Set<String> onCanvas = onCanvas(nodes, types);
        Map<String, Set<String>> curated = curatedProperties(nodes);

        Map<String, List<DiagramDto.PropertyRow>> byClass = new HashMap<>();
        for (Map.Entry<String, ConceptDetailModel> entry : live.entrySet()) {
            String iri = entry.getKey();
            if (types.get(iri) != ConceptType.VLASTNOST) {
                continue;
            }
            ConceptDetailModel detail = entry.getValue();
            DiagramPendingEdit overlay = overlays.get(iri);
            String domain = overlay != null && overlay.getDomain() != null
                    ? overlay.getDomain()
                    : detail.getDomain();
            if (drawable(domain, onCanvas)) {
                continue;
            }
            if (!curated.getOrDefault(domain, Set.of()).contains(iri)) {
                continue;
            }
            byClass.computeIfAbsent(domain, k -> new ArrayList<>())
                    .add(new DiagramDto.PropertyRow(
                            iri,
                            slugs.get(iri),
                            detail.getName(),
                            detail.getRangeResolved(),
                            false,
                            overlay != null,
                            overlay));
        }
        byClass.values().forEach(rows -> rows.sort(
                Comparator.comparing(EdgeProjector::rowSortKey)));
        return byClass;
    }

    /** Ordering key: the label if there is one, else the IRI. */
    private static String rowSortKey(DiagramDto.PropertyRow row) {
        Map<String, String> label = row.label();
        if (label != null && !label.isEmpty()) {
            String cs = label.get("cs");
            String value = cs != null ? cs : label.values().iterator().next();
            if (value != null && !value.isBlank()) {
                return value.toLowerCase(Locale.ROOT);
            }
        }
        return row.iri() != null ? row.iri() : "";
    }

    /** TRIDA: SUBCLASS_OF, child → broader. A bare triple with no backing concept. */
    private void projectHierarchy(List<DiagramDto.Edge> edges, String iri, ConceptDetailModel detail,
                                  DiagramPendingEdit overlay, Set<String> onCanvas) {
        boolean pending = overlay != null && overlay.getBroaderConcept() != null;
        List<String> broader = pending ? overlay.getBroaderConcept() : detail.getBroaderClasses();
        addTripleEdges(edges, iri, broader, detail.getBroaderClasses(),
                DiagramEdgeKind.SUBCLASS_OF, pending, onCanvas);
    }

    private void projectExactMatch(List<DiagramDto.Edge> edges, String iri, ConceptDetailModel detail,
                                   DiagramPendingEdit overlay, Set<String> onCanvas) {
        boolean pending = overlay != null && overlay.getExactMatch() != null;
        List<String> matches = pending ? overlay.getExactMatch() : detail.getExactMatches();
        addTripleEdges(edges, iri, matches, detail.getExactMatches(),
                DiagramEdgeKind.EXACT_MATCH, pending, onCanvas);
    }

    /**
     * @param targets    what to draw — overlay targets when this predicate is staged, else the live ones
     * @param liveTargets the pre-overlay targets, which is what any membership row is still keyed by
     */
    private void addTripleEdges(List<DiagramDto.Edge> edges, String source, List<String> targets,
                                List<String> liveTargets, DiagramEdgeKind kind, boolean pending,
                                Set<String> onCanvas) {
        if (targets == null) {
            return;
        }
        boolean repointed = pending && placedUnderLiveId(source, liveTargets, kind);
        for (String target : targets) {
            if (drawable(target, onCanvas)) {
                continue;
            }
            String id = projectedEdgeId(kind, source, target);
            // A repoint moves the endpoints the id is built from, so the row still carries the old id;
            // membership is honoured under either, or staging an overlay would drop the edge.
            if (!placed(id) && !repointed) {
                continue;
            }
            edges.add(new DiagramDto.Edge(
                    id,
                    mapper.nodeId(source),
                    mapper.nodeId(target),
                    "hierarchyEdge",
                    waypoints.get(id),
                    new DiagramDto.EdgeData(kind, pending)));
        }
    }

    /**
     * True when this (kind, source) had a placed edge before the overlay repointed it. Membership means the
     * link from this class is on the canvas, so it survives a change of target.
     */
    private boolean placedUnderLiveId(String source, List<String> liveTargets, DiagramEdgeKind kind) {
        if (liveTargets == null) {
            return false;
        }
        for (String liveTarget : liveTargets) {
            if (liveTarget != null && placed(projectedEdgeId(kind, source, liveTarget))) {
                return true;
            }
        }
        return false;
    }

    private boolean drawable(String iri, Set<String> onCanvas) {
        return iri == null || iri.isBlank() || !onCanvas.contains(iri);
    }

    /**
     * Deterministic id for an edge with no backing concept, unique per (kind, source, target), so ReactFlow
     * keeps the edge's identity across reloads. It doubles as the join key onto the persisted waypoints, so
     * repointing an endpoint drops them. A VZTAH edge uses its concept IRI instead.
     */
    static String projectedEdgeId(DiagramEdgeKind kind, String source, String target) {
        return String.join("|", "edge", kind.name(), source, target);
    }
}
