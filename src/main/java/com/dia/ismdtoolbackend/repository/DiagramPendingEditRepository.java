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

    /**
     * A staged overlay paired with the diagram holding it. Carries the raw JSON rather than a
     * {@code DiagramPendingEdit} because the overlay is not a JPA field — it is a text column with a
     * hand-rolled accessor, so JPQL cannot project the deserialized form.
     */
    interface ConceptOverlayRow {
        Long getDiagramId();
        String getPendingEditJson();
    }

    /**
     * One concept's staged edits across the named diagrams — the usage read's overlay join. Inverts the
     * usual direction (one diagram, many concepts), so the diagrams are given and the concept is fixed;
     * fetching them one diagram at a time would be a query per placement.
     *
     * <p>Projects {@code diagram.id} rather than returning entities: the caller runs outside a
     * transaction (its Fuseki calls must not hold a connection), where touching the lazy {@code diagram}
     * association would fail.
     */
    @Query("select e.diagram.id as diagramId, e.pendingEditJson as pendingEditJson "
            + "from DiagramPendingEditEntity e "
            + "where e.conceptIri = :conceptIri and e.diagram.id in :diagramIds")
    List<ConceptOverlayRow> findByConceptIriAcrossDiagrams(
            @Param("conceptIri") String conceptIri,
            @Param("diagramIds") Collection<Long> diagramIds);

    /** The concept IRIs carrying a staged edit — the Převzít work-list, resolved without a live session. */
    @Query("select e.conceptIri from DiagramPendingEditEntity e where e.diagram.id = :diagramId")
    List<String> findStagedConceptIris(@Param("diagramId") Long diagramId);

    /**
     * Staged edits on the SAME concepts held by OTHER diagrams of this ontology — the conflict set.
     * Scoped by ontology as well as by concept IRI: a diagram only ever conflicts with a sibling view
     * of its own ontology.
     */
    @Query("select e from DiagramPendingEditEntity e "
            + "where e.ontologyMetadata.id = :ontologyId "
            + "and e.diagram.id <> :diagramId "
            + "and e.conceptIri in :conceptIris")
    List<DiagramPendingEditEntity> findConflicting(@Param("ontologyId") Long ontologyId,
                                                   @Param("diagramId") Long diagramId,
                                                   @Param("conceptIris") Collection<String> conceptIris);

    /**
     * Discard the named concepts' staged edits on every diagram of this ontology EXCEPT the winner — one
     * resolution covering however many canvases the conflict spans, in a single pass.
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
