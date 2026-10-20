package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.ViewportDto;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the response needs from PG, detached from the persistence context so the Fuseki fetch and
 * the assembly after it touch no lazy association. {@code nodes} preserves the diagram's node order.
 */
record DiagramSnapshot(
        Long diagramId,
        String name,
        String graphName,
        Long version,
        ViewportDto viewport,
        List<DiagramNodeEntity> nodes,
        Map<String, ConceptType> types,
        Map<String, String> slugs,
        Map<Long, String> nodeIriByRowId,
        Map<String, List<EdgeWaypoint>> edgeWaypoints,
        /* Edge membership: the projected edge ids placed on this canvas. */
        Set<String> onCanvasEdges,
        /* Last-projected endpoints by edge key, [source, target]; read only for a deleted concept. */
        Map<String, String[]> edgeTombstones,
        /* Staged edits by concept IRI. Independent of `nodes`; either may exist without the other. */
        Map<String, DiagramPendingEdit> overlays,
        /* Graph name per foreign node IRI. An NKD IRI has no PG row, so no entry. */
        Map<String, String> foreignGraphs
) {
}
