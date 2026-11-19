package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.models.diagram.Backing;
import com.dia.ismdtoolbackend.models.diagram.BackingResolver;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Assembles the diagram response from a snapshot and its live content. */
@Slf4j
@Component
@RequiredArgsConstructor
class DiagramResponseAssembler {

    private final DiagramMapper mapper;

    /** Joins layout rows to live content, applies overlays, projects edges and property rows. */
    DiagramDto assemble(String ontologySlug, DiagramSnapshot snapshot, BackingResolver live) {
        DiagramContentResolver projector = new DiagramContentResolver(mapper, snapshot.edgeWaypoints(),
                snapshot.overlays(), new HashSet<>(DiagramSnapshotReader.foreignIris(snapshot.nodes())),
                snapshot.onCanvasEdges(), live, snapshot.edgeTombstones());
        Map<String, List<DiagramDto.PropertyRow>> rows =
                projector.propertyRows(snapshot.nodes(), snapshot.types(), snapshot.slugs());

        // nodes[] is classes only; relationships render as edges and properties as rows.
        List<DiagramDto.Node> nodes = new ArrayList<>();
        for (DiagramNodeEntity node : snapshot.nodes()) {
            if (!ConceptType.isCanvasMember(snapshot.types().get(node.getConceptIri()))) {
                continue;
            }
            nodes.add(toNode(snapshot, node, live, rows.getOrDefault(node.getConceptIri(), List.of())));
        }

        List<DiagramDto.Edge> edges =
                projector.project(snapshot.nodes(), snapshot.types(), snapshot.slugs());

        log.debug("Diagram {} assembled: {} of {} node(s) on canvas, {} projected edge(s), {} property row(s)"
                        + ", {} live concept(s), {} unavailable",
                snapshot.diagramId(), nodes.size(), snapshot.nodes().size(), edges.size(),
                rows.values().stream().mapToInt(List::size).sum(), live.byIri().size(),
                live.unavailable().size());

        return new DiagramDto(snapshot.diagramId(), snapshot.name(), ontologySlug, snapshot.version(),
                snapshot.viewport(), nodes, edges, pendingEdits(snapshot, live));
    }

    /** Every staged edit on the diagram — what Převzít applies. Not filtered by canvas membership. */
    private List<DiagramDto.PendingEditEntry> pendingEdits(DiagramSnapshot snapshot, BackingResolver live) {
        List<DiagramDto.PendingEditEntry> entries = new ArrayList<>();
        for (Map.Entry<String, DiagramPendingEdit> staged : snapshot.overlays().entrySet()) {
            String iri = staged.getKey();
            Backing backing = live.of(iri);
            entries.add(DiagramDto.PendingEditEntry.of(
                    iri,
                    snapshot.types().get(iri),
                    snapshot.slugs().get(iri),
                    backing,
                    staged.getValue()));
        }
        entries.sort(Comparator.comparing(DiagramDto.PendingEditEntry::iri));
        return entries;
    }

    /** Builds one render-ready node; type and slug come from the snapshot, never a fresh PG read. */
    private DiagramDto.Node toNode(DiagramSnapshot snapshot, DiagramNodeEntity node,
                                   BackingResolver live, List<DiagramDto.PropertyRow> properties) {
        String conceptIri = node.getConceptIri();
        ConceptType type = snapshot.types().get(conceptIri);
        String slug = snapshot.slugs().get(conceptIri);
        DiagramDto.NodeData data = mapper.toNodeData(node, type, slug, live.of(conceptIri), properties,
                snapshot.overlays().get(conceptIri));
        String parentId = node.getParentNodeId() != null
                ? parentWireId(snapshot, node.getParentNodeId())
                : null;
        return new DiagramDto.Node(
                mapper.nodeId(conceptIri),
                mapper.nodeType(type),
                mapper.toPosition(node),
                parentId,
                node.isCollapsed(),
                data);
    }

    private String parentWireId(DiagramSnapshot snapshot, Long parentNodeRowId) {
        String conceptIri = snapshot.nodeIriByRowId().get(parentNodeRowId);
        return conceptIri != null ? mapper.nodeId(conceptIri) : null;
    }
}
