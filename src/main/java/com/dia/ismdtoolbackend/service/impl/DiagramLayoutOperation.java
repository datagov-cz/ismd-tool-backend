package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.DiagramVersionConflictException;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/** Saves a diagram's layout against the version the client rendered from. */
@Slf4j
@Component
@RequiredArgsConstructor
class DiagramLayoutOperation {

    private final DiagramRepository diagramRepository;
    private final DiagramLayoutReconciler layoutReconciler;
    private final DiagramScopeReader scopeReader;
    private final DiagramSnapshotReader snapshotReader;

    DiagramSnapshot commitLayout(String ontologySlug, Long diagramId, DiagramLayoutDto layout) {
        OntologyMetadataEntity ontology = scopeReader.requireOntology(ontologySlug);
        DiagramEntity diagram = scopeReader.requireDiagramOf(ontology, ontologySlug, diagramId);
        requireCurrentVersion(diagram, layout.version());

        Map<String, DiagramNodeEntity> incoming = layoutReconciler.reconcileNodes(diagram, layout);
        // New nodes need an identity before parentId references resolve against them.
        diagramRepository.saveAndFlush(diagram);
        layoutReconciler.finalizeLayout(diagram, layout, incoming);
        diagram.touch();
        // The snapshot must carry the post-increment @Version the client echoes on its next save.
        diagramRepository.saveAndFlush(diagram);

        return snapshotReader.snapshot(diagram, ontology);
    }

    /**
     * Optimistic lock enforced here rather than by JPA: {@code saveLayout} re-reads the diagram in its own
     * transaction, so {@code @Version} would only compare that value against itself. Membership is a full
     * replace, so a stale save would delete another editor's nodes. A null version is accepted only for a
     * diagram with no saved state yet.
     */
    private void requireCurrentVersion(DiagramEntity diagram, Long clientVersion) {
        Long stored = diagram.getVersion();
        if (clientVersion == null && (stored == null || stored == 0L) && diagram.getNodes().isEmpty()) {
            return;
        }
        if (!Objects.equals(clientVersion, stored)) {
            log.warn("Rejected stale diagram save for {}: client version {}, stored {}",
                    diagram.getOntologyMetadata().getSlug(), clientVersion, stored);
            throw new DiagramVersionConflictException(
                    "Diagram byl mezitím uložen jiným editorem; načtěte jej znovu a uložte změny znovu.");
        }
    }
}
