package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Direct node access for the per-node endpoints (overlay stage/discard, add/remove), which address a
 * single node by its id rather than going through the whole diagram aggregate. Bulk layout changes still
 * flow through {@code DiagramEntity}'s cascade.
 */
public interface DiagramNodeRepository extends JpaRepository<DiagramNodeEntity, Long> {

    /** All nodes of a diagram (read-side assembly, coverage diff). */
    List<DiagramNodeEntity> findByDiagramId(Long diagramId);

    /** The node referencing a given concept IRI on a diagram, if present (dangling-ref / dedup checks). */
    Optional<DiagramNodeEntity> findByDiagramIdAndConceptIri(Long diagramId, String conceptIri);

    /** Ids of nodes carrying a staged overlay — the Převzít work-list, resolved without a live session. */
    @Query("select n.id from DiagramNodeEntity n "
            + "where n.diagram.id = :diagramId and n.pendingEditJson is not null")
    List<Long> findStagedNodeIds(@Param("diagramId") Long diagramId);
}
