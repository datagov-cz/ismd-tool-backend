package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Direct node access for callers that address a single layout row rather than going through the whole
 * diagram aggregate. Bulk layout changes still flow through {@code DiagramEntity}'s cascade. Staged
 * edits are not here — see {@code DiagramPendingEditRepository}.
 */
public interface DiagramNodeRepository extends JpaRepository<DiagramNodeEntity, Long> {

    /** All nodes of a diagram (read-side assembly, coverage diff). */
    List<DiagramNodeEntity> findByDiagramId(Long diagramId);

    /** The node referencing a given concept IRI on a diagram, if present (dangling-ref / dedup checks). */
    Optional<DiagramNodeEntity> findByDiagramIdAndConceptIri(Long diagramId, String conceptIri);
}
