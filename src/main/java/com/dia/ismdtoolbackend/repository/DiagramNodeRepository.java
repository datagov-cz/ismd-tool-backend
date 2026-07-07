package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.enums.DiagramNodeBacking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Direct node access for the draft endpoints (create/edit/delete/promote), which address a single node by
 * its id rather than going through the whole diagram aggregate. Bulk layout changes still flow through
 * {@code DiagramEntity}'s cascade.
 */
public interface DiagramNodeRepository extends JpaRepository<DiagramNodeEntity, Long> {

    /** All nodes of a diagram (read-side assembly, coverage diff). */
    List<DiagramNodeEntity> findByDiagramId(Long diagramId);

    /** Nodes of a diagram of one kind — e.g. all references, to diff against the ontology's concepts. */
    List<DiagramNodeEntity> findByDiagramIdAndBacking(Long diagramId, DiagramNodeBacking backing);

    /** The node referencing a given concept IRI on a diagram, if present (dangling-ref / dedup checks). */
    Optional<DiagramNodeEntity> findByDiagramIdAndConceptIri(Long diagramId, String conceptIri);
}