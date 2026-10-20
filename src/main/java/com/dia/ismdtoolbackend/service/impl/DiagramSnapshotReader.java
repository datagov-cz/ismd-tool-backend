package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEdgeEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.entity.DiagramPendingEditEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Captures a diagram's Postgres state as a detached {@link DiagramSnapshot}. Runs inside the caller's transaction. */
@Slf4j
@Component
@RequiredArgsConstructor
class DiagramSnapshotReader {

    private final ConceptMetadataRepository conceptMetadataRepository;
    private final DiagramPendingEditRepository pendingEditRepository;
    private final DiagramScopeReader scopeReader;
    private final DiagramMapper mapper;

    /** The snapshot of one diagram of the named ontology. */
    DiagramSnapshot loadForRead(String ontologySlug, Long diagramId) {
        OntologyMetadataEntity ontology = scopeReader.requireOntology(ontologySlug);
        return snapshot(scopeReader.requireDiagramOf(ontology, ontologySlug, diagramId), ontology);
    }

    /**
     * Captures the diagram's PG state; must run inside the transaction that read or wrote it. Two metadata
     * queries in all — types, slugs and foreign graph names are derived from the same two result sets.
     */
    DiagramSnapshot snapshot(DiagramEntity diagram, OntologyMetadataEntity ontology) {
        String graphName = ontology.getGraphName();
        List<DiagramNodeEntity> nodes = new ArrayList<>(diagram.getNodes());
        Map<Long, String> nodeIriByRowId = new HashMap<>();
        for (DiagramNodeEntity n : nodes) {
            if (n.getId() != null) {
                nodeIriByRowId.put(n.getId(), n.getConceptIri());
            }
        }

        List<ConceptMetadataEntity> ownConcepts = conceptMetadataRepository.findByGraphName(graphName);
        List<ConceptMetadataEntity> foreignConcepts = foreignMetadata(nodes);

        DiagramSnapshot snapshot = new DiagramSnapshot(diagram.getId(), diagram.getName(), graphName,
                diagram.getVersion(), mapper.toViewport(diagram), nodes,
                conceptTypes(ownConcepts, foreignConcepts), conceptSlugs(ownConcepts, foreignConcepts),
                nodeIriByRowId, edgeWaypoints(diagram), onCanvasEdges(diagram), edgeTombstones(diagram),
                overlays(diagram), foreignGraphs(foreignConcepts));
        log.debug("Diagram {} snapshot: {} node(s), {} placed edge(s), {} overlay(s), {} own concept(s), "
                        + "{} foreign node(s) across {} graph(s), version {}",
                diagram.getId(), nodes.size(), snapshot.onCanvasEdges().size(), snapshot.overlays().size(),
                ownConcepts.size(), snapshot.foreignGraphs().size(),
                snapshot.foreignGraphs().values().stream().distinct().count(), snapshot.version());
        return snapshot;
    }

    /** The projected edge ids on this canvas, one row per placed edge. */
    private Set<String> onCanvasEdges(DiagramEntity diagram) {
        Set<String> keys = new HashSet<>();
        for (DiagramEdgeEntity edge : diagram.getEdges()) {
            if (edge.getEdgeKey() != null) {
                keys.add(edge.getEdgeKey());
            }
        }
        return keys;
    }

    /**
     * The endpoints each placed edge last projected at. Consulted only when the edge's concept has been
     * deleted, so a deleted relationship still has two ends to render between instead of vanishing.
     */
    private Map<String, String[]> edgeTombstones(DiagramEntity diagram) {
        Map<String, String[]> byKey = new HashMap<>();
        for (DiagramEdgeEntity edge : diagram.getEdges()) {
            if (edge.getEdgeKey() == null || edge.getLastKnownSource() == null) {
                continue;
            }
            byKey.put(edge.getEdgeKey(),
                    new String[]{edge.getLastKnownSource(), edge.getLastKnownTarget()});
        }
        return byKey;
    }

    /** Graph name per foreign node IRI; an external IRI with no PG row stays absent. */
    private Map<String, String> foreignGraphs(List<ConceptMetadataEntity> foreignConcepts) {
        Map<String, String> byIri = new HashMap<>();
        for (ConceptMetadataEntity c : foreignConcepts) {
            if (c.getGraphName() != null) {
                byIri.put(c.getConceptIri(), c.getGraphName());
            }
        }
        return byIri;
    }

    static List<String> foreignIris(List<DiagramNodeEntity> nodes) {
        return nodes.stream()
                .filter(DiagramNodeEntity::isForeign)
                .map(DiagramNodeEntity::getConceptIri)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /** Staged edits by concept IRI, read inside the transaction so assembly sees a detached snapshot. */
    private Map<String, DiagramPendingEdit> overlays(DiagramEntity diagram) {
        if (diagram.getId() == null) {
            return Map.of();   // not persisted, so nothing can be staged against it
        }
        Map<String, DiagramPendingEdit> byIri = new HashMap<>();
        for (DiagramPendingEditEntity row : pendingEditRepository.findByDiagramId(diagram.getId())) {
            DiagramPendingEdit edit = row.getPendingEdit();
            if (edit != null) {
                byIri.put(row.getConceptIri(), edit);
            }
        }
        return byIri;
    }

    /** Waypoints by projected edge id. A key that no longer projects is ignored; the next Save replaces the set. */
    private Map<String, List<EdgeWaypoint>> edgeWaypoints(DiagramEntity diagram) {
        Map<String, List<EdgeWaypoint>> byKey = new HashMap<>();
        for (DiagramEdgeEntity edge : diagram.getEdges()) {
            List<EdgeWaypoint> segments = edge.getSegments();
            if (edge.getEdgeKey() == null || segments == null) {
                continue;
            }
            byKey.put(edge.getEdgeKey(), segments);
        }
        return byKey;
    }

    /**
     * Concept types by IRI: the whole own graph plus the named foreign nodes. Without a type a foreign
     * class takes {@link ConceptType#isCanvasMember}'s unknown branch and renders as deleted.
     */
    private Map<String, ConceptType> conceptTypes(List<ConceptMetadataEntity> own,
                                                  List<ConceptMetadataEntity> foreign) {
        Map<String, ConceptType> types = new HashMap<>();
        for (ConceptMetadataEntity c : own) {
            types.put(c.getConceptIri(), c.getConceptType());
        }
        for (ConceptMetadataEntity c : foreign) {
            types.putIfAbsent(c.getConceptIri(), c.getConceptType());
        }
        return types;
    }

    /** Slugs by IRI, own graph plus foreign nodes, so a foreign node can deep-link to detail. */
    private Map<String, String> conceptSlugs(List<ConceptMetadataEntity> own,
                                             List<ConceptMetadataEntity> foreign) {
        Map<String, String> slugs = new HashMap<>();
        for (ConceptMetadataEntity c : own) {
            slugs.put(c.getConceptIri(), c.getSlug());
        }
        for (ConceptMetadataEntity c : foreign) {
            slugs.putIfAbsent(c.getConceptIri(), c.getSlug());
        }
        return slugs;
    }

    /** PG rows for the foreign nodes in one query; empty for NKD IRIs, which have no local row. */
    private List<ConceptMetadataEntity> foreignMetadata(List<DiagramNodeEntity> nodes) {
        List<String> iris = foreignIris(nodes);
        return iris.isEmpty() ? List.of() : conceptMetadataRepository.findByConceptIriIn(iris);
    }
}
