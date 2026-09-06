package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.DiagramPendingEditEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Staged structural edits, addressed by (diagram, concept IRI). */
public interface DiagramPendingEditRepository extends JpaRepository<DiagramPendingEditEntity, Long> {

    /** Every staged edit on one diagram — the read-side join and the Převzít work-list. */
    List<DiagramPendingEditEntity> findByDiagramId(Long diagramId);

    /** One concept's staged edit on one diagram, if any — the stage/update/discard target. */
    Optional<DiagramPendingEditEntity> findByDiagramIdAndConceptIri(Long diagramId, String conceptIri);

    /** Discard one concept's staged edit on one diagram; deleting nothing is a no-op. */
    @Modifying
    @Query("delete from DiagramPendingEditEntity e "
            + "where e.diagram.id = :diagramId and e.conceptIri = :conceptIri")
    int deleteByDiagramIdAndConceptIri(@Param("diagramId") Long diagramId,
                                       @Param("conceptIri") String conceptIri);

    /** The concept IRIs carrying a staged edit — the Převzít work-list, resolved without a live session. */
    @Query("select e.conceptIri from DiagramPendingEditEntity e where e.diagram.id = :diagramId")
    List<String> findStagedConceptIris(@Param("diagramId") Long diagramId);

    /**
     * Staged edits on the SAME concepts held by OTHER diagrams of this ontology — the conflict set.
     *
     * <p>Materializing one diagram writes RDF the sibling's staged edit was based on, so the sibling
     * would afterwards fail STALE_BASE. Reporting the collision up front lets the user choose which
     * side to discard instead of discovering it one concept at a time.
     *
     * <p>Scoped by ontology as well as by the concept IRIs: a diagram may only ever conflict with a
     * sibling view of its own ontology.
     */
    @Query("select e from DiagramPendingEditEntity e "
            + "where e.ontologyMetadata.id = :ontologyId "
            + "and e.diagram.id <> :diagramId "
            + "and e.conceptIri in :conceptIris")
    List<DiagramPendingEditEntity> findConflicting(@Param("ontologyId") Long ontologyId,
                                                   @Param("diagramId") Long diagramId,
                                                   @Param("conceptIris") Collection<String> conceptIris);

    /** Discard the named concepts' staged edits across every OTHER diagram of this ontology. */
    @Modifying
    @Query("delete from DiagramPendingEditEntity e "
            + "where e.ontologyMetadata.id = :ontologyId "
            + "and e.diagram.id <> :diagramId "
            + "and e.conceptIri in :conceptIris")
    int deleteConflictingOnSiblings(@Param("ontologyId") Long ontologyId,
                                    @Param("diagramId") Long diagramId,
                                    @Param("conceptIris") Collection<String> conceptIris);

    /** Discard the named concepts' staged edits on THIS diagram. */
    @Modifying
    @Query("delete from DiagramPendingEditEntity e "
            + "where e.diagram.id = :diagramId and e.conceptIri in :conceptIris")
    int deleteOnDiagram(@Param("diagramId") Long diagramId,
                        @Param("conceptIris") Collection<String> conceptIris);
}
