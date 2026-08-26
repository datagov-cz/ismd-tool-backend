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
 * Re-derives diagram edges from {@code live ⊕ overlay} — an edge's existence, kind and endpoints are a
 * pure projection, never read from storage as truth. Only waypoints are joined on from the persisted
 * {@code diagram_edges} rows, keyed by the projected edge id.
 *
 * <p>Three kinds are projected:
 * <ul>
 *   <li>{@code VZTAH} — one edge per relationship concept, drawn from its {@code rdfs:domain} class to its
 *       {@code rdfs:range} class, carrying the concept's own identity. The relationship is the edge; it is
 *       not a node with an edge to each endpoint.</li>
 *   <li>{@code SUBCLASS_OF} and {@code EXACT_MATCH} — bare RDF triples between two classes, with no
 *       backing concept.</li>
 * </ul>
 *
 * <p>VLASTNOSTi are not projected here at all: a property renders as a row inside its domain class
 * (see {@link #propertyRows}). Sub-property/sub-relation hierarchy is not projected either — neither
 * endpoint is a node, so the link has nothing to attach to. See
 * {@code .planning/diagram-edge-model-REDESIGN.md}.
 */
class EdgeProjector {

    private final DiagramMapper mapper;
    private final Map<String, List<EdgeWaypoint>> waypoints;

    EdgeProjector(DiagramMapper mapper, Map<String, List<EdgeWaypoint>> waypoints) {
        this.mapper = mapper;
        this.waypoints = waypoints != null ? waypoints : Map.of();
    }

    /**
     * Project every edge the canvas should render. {@code nodes} is the canvas membership (classes);
     * {@code live} is the whole graph, so relationships that are not themselves on the canvas still
     * project as long as both their endpoint classes are.
     */
    List<DiagramDto.Edge> project(List<DiagramNodeEntity> nodes,
                                  Map<String, ConceptDetailModel> live,
                                  Map<String, ConceptType> types,
                                  Map<String, String> slugs) {
        Set<String> onCanvas = onCanvas(nodes, types);
        Map<String, DiagramPendingEdit> overlays = overlays(nodes);

        List<DiagramDto.Edge> edges = new ArrayList<>();
        for (Map.Entry<String, ConceptDetailModel> entry : live.entrySet()) {
            String iri = entry.getKey();
            ConceptDetailModel detail = entry.getValue();
            DiagramPendingEdit overlay = overlays.get(iri);

            if (isRelationship(iri, detail, types)) {
                projectRelationship(edges, iri, detail, overlay, onCanvas, slugs);
            }
            if (onCanvas.contains(iri)) {
                projectHierarchy(edges, iri, detail, overlay, onCanvas);
                projectExactMatch(edges, iri, detail, overlay, onCanvas);
            }
        }
        return edges;
    }

    /**
     * Canvas membership is the set of CLASS nodes. A row exists for every concept carrying a staged
     * overlay — including relationships and properties, which are never sent in {@code nodes[]} — so
     * "has a row" is not the same question as "is a box on the canvas", and only the latter may serve as
     * an edge endpoint.
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

    /** Staged overlays by concept IRI, for concepts that are canvas nodes and those that are not. */
    private Map<String, DiagramPendingEdit> overlays(List<DiagramNodeEntity> nodes) {
        Map<String, DiagramPendingEdit> overlays = new HashMap<>();
        for (DiagramNodeEntity n : nodes) {
            if (n.getPendingEdit() != null) {
                overlays.put(n.getConceptIri(), n.getPendingEdit());
            }
        }
        return overlays;
    }

    /**
     * A concept is a relationship when PG says so, or — when PG has no type for it — when its RDF carries
     * both a domain and a range. {@code concept_metadata.concept_type} is null for uploads whose OFN tag
     * carries no matching OWL type, and gating solely on it would silently drop a real VZTAH from the
     * canvas with no way for the user to put it back.
     */
    private boolean isRelationship(String iri, ConceptDetailModel detail, Map<String, ConceptType> types) {
        ConceptType type = types.get(iri);
        if (type != null) {
            return type == ConceptType.VZTAH;
        }
        return detail.getDomain() != null && detail.getRange() != null;
    }

    /**
     * A VZTAH is one edge from its domain class to its range class, carrying its own concept identity.
     * A relationship missing either endpoint — or pointing off-canvas — is simply not drawn; it is placed
     * by being dragged in from the ontology detail, which supplies both.
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
     * Group the graph's VLASTNOSTi by their {@code rdfs:domain} (live ⊕ overlay) into the rows each class
     * node renders. A property is never a canvas object of its own: its range is a literal datatype, so
     * there is no second concept to draw an edge to.
     *
     * <p>Rows are ordered by label — falling back to the IRI when a concept has none, or the order would be
     * unstable for exactly the concepts a user is least able to identify. Only properties whose domain is a
     * class on the canvas appear; a domainless property has no row to live in and is placed by being
     * dragged in from the ontology detail.
     *
     * <p>Membership is CURATED, not derived: a property renders only when its domain class lists it in
     * {@code visibleProperties}.
     */
    Map<String, List<DiagramDto.PropertyRow>> propertyRows(List<DiagramNodeEntity> nodes,
                                                           Map<String, ConceptDetailModel> live,
                                                           Map<String, ConceptType> types,
                                                           Map<String, String> slugs) {
        Set<String> onCanvas = onCanvas(nodes, types);
        Map<String, DiagramPendingEdit> overlays = overlays(nodes);
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

    /** Czech-aware-enough ordering key: the label if there is one, else the IRI. */
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

    /** TRIDA: SUBCLASS_OF, child → broader. A bare triple; no concept backs it. */
    private void projectHierarchy(List<DiagramDto.Edge> edges, String iri, ConceptDetailModel detail,
                                  DiagramPendingEdit overlay, Set<String> onCanvas) {
        boolean pending = overlay != null && overlay.getBroaderConcept() != null;
        List<String> broader = pending ? overlay.getBroaderConcept() : detail.getBroaderClasses();
        addTripleEdges(edges, iri, broader, DiagramEdgeKind.SUBCLASS_OF, pending, onCanvas);
    }

    private void projectExactMatch(List<DiagramDto.Edge> edges, String iri, ConceptDetailModel detail,
                                   DiagramPendingEdit overlay, Set<String> onCanvas) {
        boolean pending = overlay != null && overlay.getExactMatch() != null;
        List<String> matches = pending ? overlay.getExactMatch() : detail.getExactMatches();
        addTripleEdges(edges, iri, matches, DiagramEdgeKind.EXACT_MATCH, pending, onCanvas);
    }

    private void addTripleEdges(List<DiagramDto.Edge> edges, String source, List<String> targets,
                                DiagramEdgeKind kind, boolean pending, Set<String> onCanvas) {
        if (targets == null) {
            return;
        }
        for (String target : targets) {
            if (drawable(target, onCanvas)) {
                continue;
            }
            String id = projectedEdgeId(kind, source, target);
            edges.add(new DiagramDto.Edge(
                    id,
                    mapper.nodeId(source),
                    mapper.nodeId(target),
                    "hierarchyEdge",
                    waypoints.get(id),
                    new DiagramDto.EdgeData(kind, pending)));
        }
    }

    private boolean drawable(String iri, Set<String> onCanvas) {
        return iri == null || iri.isBlank() || !onCanvas.contains(iri);
    }

    /**
     * Deterministic id for an edge with no backing concept, unique per (kind, source, target). Edges are
     * re-derived on every read, so a stable id lets ReactFlow keep an edge's identity (selection/animation)
     * across reloads. It doubles as the join key onto the persisted waypoints, which is why repointing an
     * endpoint deliberately drops them: the geometry was drawn for an endpoint the edge no longer has.
     *
     * <p>A VZTAH edge does not use this — its concept IRI is already a unique, stable id.
     */
    static String projectedEdgeId(DiagramEdgeKind kind, String source, String target) {
        return String.join("|", "edge", kind.name(), source, target);
    }
}
