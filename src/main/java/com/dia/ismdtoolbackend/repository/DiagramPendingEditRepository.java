package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.DiagramPendingEditEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Staged structural edits, addressed by (ontology, concept IRI). */
public interface DiagramPendingEditRepository extends JpaRepository<DiagramPendingEditEntity, Long> {

    /** Every staged edit for an ontology — the read-side join and the Převzít work-list. */
    List<DiagramPendingEditEntity> findByOntologyMetadataId(Long ontologyMetadataId);

    /** One concept's staged edit, if any — the stage/update/discard target. */
    Optional<DiagramPendingEditEntity> findByOntologyMetadataIdAndConceptIri(
            Long ontologyMetadataId, String conceptIri);

    /** Discard one concept's staged edit; deleting nothing is a no-op. */
    @Modifying
    @Query("delete from DiagramPendingEditEntity e "
            + "where e.ontologyMetadata.id = :ontologyId and e.conceptIri = :conceptIri")
    int deleteByOntologyMetadataIdAndConceptIri(@Param("ontologyId") Long ontologyMetadataId,
                                                @Param("conceptIri") String conceptIri);

    /** The concept IRIs carrying a staged edit — the Převzít work-list, resolved without a live session. */
    @Query("select e.conceptIri from DiagramPendingEditEntity e "
            + "where e.ontologyMetadata.id = :ontologyId")
    List<String> findStagedConceptIris(@Param("ontologyId") Long ontologyMetadataId);
}