package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.NkdConceptSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NkdConceptSnapshotRepository extends JpaRepository<NkdConceptSnapshotEntity, Long> {

    /** All snapshots owned by one concept (detail view, concept-delete cleanup). */
    List<NkdConceptSnapshotEntity> findByOwningConceptId(Long owningConceptId);

    /** All snapshots owned by any of the given concepts (concept-delete cleanup). */
    List<NkdConceptSnapshotEntity> findByOwningConceptIdIn(List<Long> owningConceptIds);

    /** All snapshots in one graph (ontology-delete cleanup). */
    List<NkdConceptSnapshotEntity> findByGraphName(String graphName);

    /** The unique snapshot for an (owner, NKD IRI) link, if any (upsert lookup). */
    Optional<NkdConceptSnapshotEntity> findByOwningConceptIdAndNkdIri(Long owningConceptId, String nkdIri);

    /** Every snapshot pointing at one NKD IRI (cross-owner). */
    List<NkdConceptSnapshotEntity> findByNkdIri(String nkdIri);
}
