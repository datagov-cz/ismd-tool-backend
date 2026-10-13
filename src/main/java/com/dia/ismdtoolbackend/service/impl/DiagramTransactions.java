package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramSummaryDto;
import com.dia.ismdtoolbackend.service.DiagramService.ConflictResolution;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** The diagram steps that each run in their own Postgres transaction. None of them reads Fuseki for content. */
@Component
@Transactional
@RequiredArgsConstructor
class DiagramTransactions {

    private final DiagramListReader listReader;
    private final DiagramLifecycleOperation lifecycleOperation;
    private final DiagramLayoutOperation layoutOperation;
    private final DiagramConflictOperation conflictOperation;
    private final DiagramSnapshotReader snapshotReader;

    @Transactional(readOnly = true)
    public List<DiagramSummaryDto> listAll(String userId) {
        return listReader.listAll(userId);
    }

    @Transactional(readOnly = true)
    public List<DiagramSummaryDto> listForOntology(String ontologySlug) {
        return listReader.listForOntology(ontologySlug);
    }

    public DiagramSnapshot commitNewDiagram(String ontologySlug, String name) {
        return lifecycleOperation.commitNewDiagram(ontologySlug, name);
    }

    public DiagramSummaryDto renameDiagram(String ontologySlug, Long diagramId, String name) {
        return lifecycleOperation.renameDiagram(ontologySlug, diagramId, name);
    }

    public void deleteDiagram(String ontologySlug, Long diagramId) {
        lifecycleOperation.deleteDiagram(ontologySlug, diagramId);
    }

    @Transactional(readOnly = true)
    public DiagramSnapshot loadForRead(String ontologySlug, Long diagramId) {
        return snapshotReader.loadForRead(ontologySlug, diagramId);
    }

    public DiagramSnapshot commitLayout(String ontologySlug, Long diagramId, DiagramLayoutDto layout) {
        return layoutOperation.commitLayout(ontologySlug, diagramId, layout);
    }

    public DiagramConflictOperation.Resolved resolveConflicts(String ontologySlug, Long diagramId,
                                                              ConflictResolution onConflict, Long winnerDiagramId) {
        return conflictOperation.resolveConflicts(ontologySlug, diagramId, onConflict, winnerDiagramId);
    }
}
