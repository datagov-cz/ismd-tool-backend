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

    /** Every staged edit on one diagram: the read-side join and the Převzít work-list. */
    List<DiagramPendingEditEntity> findByDiagramId(Long diagramId);

    /** One concept's staged edit on one diagram: the stage, update and discard target. */
    Optional<DiagramPendingEditEntity> findByDiagramIdAndConceptIri(Long diagramId, String conceptIri);

    /** Discards one concept's staged edit on one diagram; deleting nothing is a no-op. */
    @Modifying
    @Query("delete from DiagramPendingEditEntity e "
            + "where e.diagram.id = :diagramId and e.conceptIri = :conceptIri")
    int deleteByDiagramIdAndConceptIri(@Param("diagramId") Long diagramId,
                                       @Param("conceptIri") String conceptIri);

    /**
     * A staged overlay paired with the diagram holding it. Carries the raw JSON because the overlay is a
     * text column with a hand-rolled accessor rather than a JPA field, so JPQL cannot project it parsed.
     */
    interface ConceptOverlayRow {
        Long getDiagramId();
        String getPendingEditJson();
    }

    /**
     * One concept's staged edits across the named diagrams: the usage read's overlay join. Inverts the usual
     * direction of one diagram and many concepts, since fetching one diagram at a time would be a query per
     * placement.
     *
     * <p>Projects {@code diagram.id} rather than entities, because the caller runs outside a transaction —
     * its Fuseki calls must not hold a connection — where the lazy association would fail.
     */
    @Query("select e.diagram.id as diagramId, e.pendingEditJson as pendingEditJson "
            + "from DiagramPendingEditEntity e "
            + "where e.conceptIri = :conceptIri and e.diagram.id in :diagramIds")
    List<ConceptOverlayRow> findByConceptIriAcrossDiagrams(
            @Param("conceptIri") String conceptIri,
            @Param("diagramIds") Collection<Long> diagramIds);

    /** The concept IRIs carrying a staged edit: the Převzít work-list, resolved without a live session. */
    @Query("select e.conceptIri from DiagramPendingEditEntity e where e.diagram.id = :diagramId")
    List<String> findStagedConceptIris(@Param("diagramId") Long diagramId);

    /**
     * The conflict set: staged edits on the same concepts held by other diagrams of this ontology. Scoped by
     * ontology as well as concept IRI, since a diagram only conflicts with a sibling view of its own.
     */
    @Query("select e from DiagramPendingEditEntity e "
            + "where e.ontologyMetadata.id = :ontologyId "
            + "and e.diagram.id <> :diagramId "
            + "and e.conceptIri in :conceptIris")
    List<DiagramPendingEditEntity> findConflicting(@Param("ontologyId") Long ontologyId,
                                                   @Param("diagramId") Long diagramId,
                                                   @Param("conceptIris") Collection<String> conceptIris);

    /**
     * Discards the named concepts' staged edits on every diagram of this ontology except the winner: one
     * resolution covering however many canvases the conflict spans.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from DiagramPendingEditEntity e "
            + "where e.ontologyMetadata.id = :ontologyId "
            + "and e.diagram.id <> :winnerDiagramId "
            + "and e.conceptIri in :conceptIris")
    int deleteConflictingExceptWinner(@Param("ontologyId") Long ontologyId,
                                      @Param("winnerDiagramId") Long winnerDiagramId,
                                      @Param("conceptIris") Collection<String> conceptIris);
}
