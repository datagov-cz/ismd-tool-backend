package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramSummaryDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.MaterializeResultDto;
import com.dia.ismdtoolbackend.exception.DiagramContentUnavailableException;
import com.dia.ismdtoolbackend.exception.DiagramReadbackFailedException;
import com.dia.ismdtoolbackend.models.diagram.BackingResolver;
import com.dia.ismdtoolbackend.service.DiagramService.ConflictResolution;
import com.dia.ismdtoolbackend.service.DiagramService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Diagram reads (layout ⋈ live content, overlays applied, edges projected) and the Save-time layout,
 * membership and overlay reconcile. Pure PG — never writes RDF; materialize is delegated to
 * {@link DiagramMaterializeService}. See {@code docs/DIAGRAM_LAYER.md}.
 */
@Service
@RequiredArgsConstructor
public class DiagramServiceImpl implements DiagramService {

    private final DiagramTransactions transactions;
    private final DiagramLabelReader labelReader;
    private final DiagramLiveContentReader liveContentReader;
    private final DiagramResponseAssembler assembler;
    private final DiagramMaterializeService materializeService;

    @Override
    public List<DiagramSummaryDto> listAll(String userId) {
        return labelReader.withOntologyLabels(transactions.listAll(userId));
    }

    @Override
    public List<DiagramSummaryDto> listForOntology(String ontologySlug) {
        return labelReader.withOntologyLabels(transactions.listForOntology(ontologySlug));
    }

    /** Create an empty canvas. Creation is explicit — a read never provisions a row. */
    @Override
    public DiagramDto createDiagram(String ontologySlug, String name) {
        DiagramSnapshot snapshot = transactions.commitNewDiagram(ontologySlug, name);
        return assembler.assemble(ontologySlug, snapshot, liveContentReader.readbackConcepts(snapshot));
    }

    /** Renames one diagram, then reads the ontology label. Renaming to the current name is a no-op. */
    @Override
    public DiagramSummaryDto renameDiagram(String ontologySlug, Long diagramId, String name) {
        return labelReader.withOntologyLabel(transactions.renameDiagram(ontologySlug, diagramId, name));
    }

    /** Deletes one diagram. Nodes, waypoints and staged edits cascade with it; concepts do not. */
    @Override
    public void deleteDiagram(String ontologySlug, Long diagramId) {
        transactions.deleteDiagram(ontologySlug, diagramId);
    }

    /**
     * Untransacted: {@link DiagramTransactions#loadForRead} returns a detached snapshot, so the Fuseki
     * fetch holds no connection. An unreadable own graph is a 502, the same status the save path returns
     * for the same failure.
     */
    @Override
    public DiagramDto getDiagram(String ontologySlug, Long diagramId) {
        DiagramSnapshot snapshot = transactions.loadForRead(ontologySlug, diagramId);
        BackingResolver live;
        try {
            live = liveContentReader.liveContent(snapshot);
        } catch (RuntimeException e) {
            throw new DiagramContentUnavailableException(e);
        }
        return assembler.assemble(ontologySlug, snapshot, live);
    }

    /**
     * Commits the layout, then fetches the graph outside that transaction so a slow Fuseki holds no
     * connection. A fetch failure lands after a durable write and surfaces as
     * {@link DiagramReadbackFailedException} — reload, not retry. See {@code docs/DIAGRAM_LAYER.md}.
     */
    @Override
    public DiagramDto saveLayout(String ontologySlug, Long diagramId, DiagramLayoutDto layout) {
        DiagramSnapshot snapshot = transactions.commitLayout(ontologySlug, diagramId, layout);
        return assembler.assemble(ontologySlug, snapshot, liveContentReader.readbackConcepts(snapshot));
    }

    // Not transactional: each staged change materializes in its own REQUIRES_NEW transaction, so a
    // failure cannot roll back earlier successes.
    @Override
    public MaterializeResultDto materialize(String ontologySlug, Long diagramId,
                                            ConflictResolution onConflict, Long winnerDiagramId) {
        // Detection and resolution commit before the per-change loop writes any RDF.
        DiagramConflictOperation.Resolved resolved =
                transactions.resolveConflicts(ontologySlug, diagramId, onConflict, winnerDiagramId);
        return materializeService.materialize(resolved.winnerDiagramId(), resolved.ontologyId());
    }
}
