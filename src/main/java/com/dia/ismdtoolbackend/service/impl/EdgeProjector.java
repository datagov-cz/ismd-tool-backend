package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.DiagramEdgeKind;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.diagram.Backing;
import com.dia.ismdtoolbackend.models.diagram.BackingResolver;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Renders a canvas's edges and property rows, driven by <b>membership</b>: the traversal walks the placed
 * rows and asks {@code live ⊕ overlay} only for what a row cannot say about itself. Every element type — node,
 * VZTAH edge, hierarchy or equivalence edge, property row — answers the same three questions in the same
 * order: is it placed, are its endpoints on the canvas, and what is its {@link Backing}?
 *
 * <p><b>Membership decides existence; RDF never does.</b> A {@code diagram_edges} row means the user put that
 * edge on the canvas, and an edge the graph could support but no row names is deliberately off it, waiting in
 * the sidebar like an unplaced class. The traversal used to run the other way — iterate the graph, keep what
 * happened to be placed — which silently coupled the two: an element existed on the canvas only while its RDF
 * did, so a concept deleted elsewhere took the element with it, unannounced.
 *
 * <p><b>Divergence is shown, not resolved.</b> A placed element whose backing concept was deleted keeps
 * rendering with {@code stale} set, because that deletion came from outside and the user must not lose work
 * without being told. A change the user staged on this canvas is the opposite case and applies at once: an
 * overlay that drops a hierarchy target un-draws that edge immediately, since removing it is what they asked
 * for. Only materialization treats a divergence as a conflict.
 *
 * <p>Endpoints and kind are still derived rather than trusted from storage, so they cannot contradict RDF.
 * A triple edge's row key already encodes them; a VZTAH's does not, and falls back to the row's tombstone
 * only once its concept is gone — never while it is live.
 *
 * <p>Three kinds are rendered:
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
@Slf4j
class EdgeProjector {

    /** Marks a composite edge id, distinguishing it from a VZTAH's concept IRI. */
    static final String COMPOSITE_ID_PREFIX = "edge|";

    private final DiagramMapper mapper;
    /** Routing waypoints by projected edge id, joined on from the persisted row. */
    private final Map<String, List<EdgeWaypoint>> waypoints;
    /** Staged edits by concept IRI, supplied because an edit need not have a node row. */
    private final Map<String, DiagramPendingEdit> overlays;
    /** Foreign node IRIs: valid edge targets, never edge sources. */
    private final Set<String> foreignIris;
    /**
     * The projected edge ids the user has placed on this canvas. Projection decides an edge's endpoints and
     * kind; this decides whether it is drawn at all.
     */
    private final Set<String> onCanvasEdges;
    /** The one verdict source: is a placed element's backing concept live, deleted or unreadable? */
    private final BackingResolver backing;
    /** Last-projected endpoints by edge key, {@code [source, target]}; read only for a deleted concept. */
    private final Map<String, String[]> tombstones;
    /**
     * Memoized canvas membership. Both {@link #project} and {@link #propertyRows} need it and are called
     * once each per read with the same {@code nodes} and {@code types}, so computing it twice is pure
     * duplicated work on the hottest path. A projector serves one read, so one memo is safe.
     */
    private Set<String> onCanvasMemo;

    EdgeProjector(DiagramMapper mapper, Map<String, List<EdgeWaypoint>> waypoints,
                  Map<String, DiagramPendingEdit> overlays, Set<String> foreignIris,
                  Set<String> onCanvasEdges, BackingResolver backing,
                  Map<String, String[]> tombstones) {
        this.mapper = mapper;
        this.waypoints = waypoints != null ? waypoints : Map.of();
        this.overlays = overlays != null ? overlays : Map.of();
        this.foreignIris = foreignIris != null ? foreignIris : Set.of();
        this.onCanvasEdges = onCanvasEdges != null ? onCanvasEdges : Set.of();
        this.backing = backing != null ? backing : new BackingResolver(Map.of(), Set.of());
        this.tombstones = tombstones != null ? tombstones : Map.of();
    }

    /** True when the user has placed this edge on the canvas. */
    private boolean placed(String edgeId) {
        return onCanvasEdges.contains(edgeId);
    }

    /**
     * Every edge the canvas renders, driven by membership rather than by the graph: each placed row is
     * resolved in turn, and {@code live ⊕ overlay} supplies only what the row cannot say for itself.
     *
     * <p>The traversal used to run the other way — iterate the whole graph, keep what happened to be placed —
     * which meant an element only existed on the canvas while its RDF did. A concept deleted underneath the
     * diagram then vanished silently, because a row whose concept is gone is never reached by a loop over
     * live concepts. Driving from the rows is what lets a deleted element still render, flagged.
     */
    List<DiagramDto.Edge> project(List<DiagramNodeEntity> nodes,
                                  Map<String, ConceptType> types,
                                  Map<String, String> slugs) {
        Set<String> onCanvas = onCanvas(nodes, types);

        List<DiagramDto.Edge> edges = new ArrayList<>();
        for (String edgeKey : onCanvasEdges) {
            DiagramDto.Edge edge = edgeKey.startsWith(COMPOSITE_ID_PREFIX)
                    ? tripleEdge(edgeKey, onCanvas)
                    : relationshipEdge(edgeKey, onCanvas, types, slugs);
            if (edge != null) {
                edges.add(edge);
            }
        }
        return edges;
    }

    /**
     * One placed hierarchy or equivalence edge. Kind and both endpoints come from the row's own key, so the
     * edge survives its target's deletion; RDF is consulted only to say whether the triple is still asserted
     * ({@code pending}) and whether the target concept is still there ({@code stale}).
     *
     * <p>A staged removal is the one case where an overlay un-draws an edge, and legitimately so: the user
     * asked for it on this canvas, so the canvas reflects it at once. That is the opposite of an external
     * deletion, which the user did not ask for and must not lose silently.
     */
    private DiagramDto.Edge tripleEdge(String edgeKey, Set<String> onCanvas) {
        String[] parts = edgeKey.split("\\|", 4);
        if (parts.length != 4) {
            log.warn("Ignoring malformed diagram edge key {}", edgeKey);
            return null;
        }
        DiagramEdgeKind kind;
        try {
            kind = DiagramEdgeKind.valueOf(parts[1]);
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring diagram edge key {} with unknown kind {}", edgeKey, parts[1]);
            return null;
        }
        String source = parts[2];
        String target = parts[3];

        // Membership never overrides the canvas: an edge whose endpoint left is not drawn, row or no row.
        if (notOnCanvas(source, onCanvas) || notOnCanvas(target, onCanvas)) {
            return null;
        }
        // A foreign concept's own triples belong to its owning graph; it is an edge target, never a source.
        if (foreignIris.contains(source)) {
            return null;
        }

        List<String> live = liveTargets(source, kind);
        List<String> staged = stagedTargets(source, kind);
        if (staged != null && !staged.contains(target)) {
            // The user staged this link away on this canvas; drawing it would contradict their own intent.
            return null;
        }
        boolean asserted = live.contains(target);
        boolean pending = staged != null && staged.contains(target) && !asserted;
        // A row for a triple neither RDF nor an overlay asserts is orphaned — the link is simply not there,
        // and drawing it would invent an edge. Distinct from a STALE target, where the link is still
        // asserted and it is the concept at the far end that is gone.
        if (!asserted && !pending && !backing.of(source).stale() && !backing.of(target).stale()) {
            return null;
        }

        return new DiagramDto.Edge(
                edgeKey,
                mapper.nodeId(source),
                mapper.nodeId(target),
                mapper.edgeType(kind),
                waypoints.get(edgeKey),
                DiagramDto.EdgeData.triple(kind, pending, backing.of(target)));
    }

    /** The targets a concept's predicate currently asserts in RDF; empty when the concept is gone. */
    private List<String> liveTargets(String source, DiagramEdgeKind kind) {
        ConceptDetailModel detail = backing.get(source);
        if (detail == null) {
            return List.of();
        }
        List<String> targets = kind == DiagramEdgeKind.SUBCLASS_OF
                ? detail.getBroaderClasses()
                : detail.getExactMatches();
        return targets != null ? targets : List.of();
    }

    /** The targets the overlay stages for a predicate, or null when it stages none. */
    private List<String> stagedTargets(String source, DiagramEdgeKind kind) {
        DiagramPendingEdit overlay = overlays.get(source);
        if (overlay == null) {
            return null;
        }
        return kind == DiagramEdgeKind.SUBCLASS_OF ? overlay.getBroaderConcept() : overlay.getExactMatch();
    }

    /** The class nodes on this canvas — the only things an edge may attach to. */
    private Set<String> onCanvas(List<DiagramNodeEntity> nodes, Map<String, ConceptType> types) {
        if (onCanvasMemo != null) {
            return onCanvasMemo;
        }
        Set<String> onCanvas = new HashSet<>();
        for (DiagramNodeEntity n : nodes) {
            if (ConceptType.isCanvasMember(types.get(n.getConceptIri()))) {
                onCanvas.add(n.getConceptIri());
            }
        }
        onCanvasMemo = onCanvas;
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
     * One placed VZTAH: an edge from its {@code rdfs:domain} class to its {@code rdfs:range} class, carrying
     * the relationship concept's own identity. Unlike a triple edge, its row key is the concept IRI alone, so
     * its endpoints are genuinely derived — they live on the concept and move when an overlay repoints it.
     *
     * <p>A deleted VZTAH keeps its endpoints from the row's tombstone, so it renders stale rather than
     * disappearing; without that fallback there would be nothing to draw between.
     */
    private DiagramDto.Edge relationshipEdge(String conceptIri, Set<String> onCanvas,
                                             Map<String, ConceptType> types, Map<String, String> slugs) {
        Backing edgeBacking = backing.of(conceptIri);
        ConceptDetailModel detail = edgeBacking.detailOrNull();
        if (detail != null && !isRelationship(conceptIri, detail, types)) {
            return null;
        }
        DiagramPendingEdit overlay = overlays.get(conceptIri);
        boolean domainPending = overlay != null && overlay.getDomain() != null;
        boolean rangePending = overlay != null && overlay.getRange() != null;

        String domain = domainPending ? overlay.getDomain() : liveEndpoint(detail, true, conceptIri);
        String range = rangePending ? overlay.getRange() : liveEndpoint(detail, false, conceptIri);

        if (notOnCanvas(domain, onCanvas) || notOnCanvas(range, onCanvas)) {
            return null;
        }
        return new DiagramDto.Edge(
                conceptIri,
                mapper.nodeId(domain),
                mapper.nodeId(range),
                mapper.edgeType(DiagramEdgeKind.VZTAH),
                waypoints.get(conceptIri),
                DiagramDto.EdgeData.relationship(domainPending || rangePending, conceptIri,
                        slugs.get(conceptIri), edgeBacking, overlay));
    }

    /**
     * A VZTAH's live endpoint, falling back to what the row last saw when the concept is gone. The tombstone
     * is read only in that case, so it can never contradict a live projection — it exists purely so a deleted
     * relationship still has two ends to render between.
     */
    private String liveEndpoint(ConceptDetailModel detail, boolean wantDomain, String conceptIri) {
        if (detail != null) {
            return wantDomain ? detail.getDomain() : detail.getRange();
        }
        String[] lastKnown = tombstones.get(conceptIri);
        if (lastKnown == null) {
            return null;
        }
        return wantDomain ? lastKnown[0] : lastKnown[1];
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
                                                           Map<String, ConceptType> types,
                                                           Map<String, String> slugs) {
        Set<String> onCanvas = onCanvas(nodes, types);

        Map<String, List<DiagramDto.PropertyRow>> byClass = new HashMap<>();
        Set<String> emitted = new HashSet<>();
        for (DiagramNodeEntity node : nodes) {
            for (String iri : node.getVisibleProperties()) {
                Backing rowBacking = backing.of(iri);
                ConceptDetailModel detail = rowBacking.detailOrNull();
                // A deleted property has no PG row either, so its type is unknown; only a resolvable
                // concept that is demonstrably not a VLASTNOST is skipped.
                if (detail != null && types.get(iri) != ConceptType.VLASTNOST) {
                    continue;
                }
                DiagramPendingEdit overlay = overlays.get(iri);
                // The curating node is the fallback domain: a deleted property has no domain of its own,
                // and the row it was curated into is where the user put it.
                String domain = overlay != null && overlay.getDomain() != null
                        ? overlay.getDomain()
                        : detail != null ? detail.getDomain() : node.getConceptIri();
                if (notOnCanvas(domain, onCanvas) || !emitted.add(domain + ' ' + iri)) {
                    continue;
                }
                byClass.computeIfAbsent(domain, k -> new ArrayList<>())
                        .add(DiagramDto.PropertyRow.of(iri, slugs.get(iri), rowBacking, overlay));
            }
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

    /** True when this endpoint cannot anchor an edge: absent, or not a node on the canvas. */
    private boolean notOnCanvas(String iri, Set<String> onCanvas) {
        return iri == null || iri.isBlank() || !onCanvas.contains(iri);
    }

    /**
     * Deterministic id for an edge with no backing concept, unique per (kind, source, target), so ReactFlow
     * keeps the edge's identity across reloads. It doubles as the join key onto the persisted waypoints, so
     * repointing an endpoint drops them. A VZTAH edge uses its concept IRI instead.
     */
    static String projectedEdgeId(DiagramEdgeKind kind, String source, String target) {
        return COMPOSITE_ID_PREFIX + String.join("|", kind.name(), source, target);
    }
}
