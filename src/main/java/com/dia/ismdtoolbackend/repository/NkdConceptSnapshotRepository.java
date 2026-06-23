package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.NkdConceptSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NkdConceptSnapshotRepository extends JpaRepository<NkdConceptSnapshotEntity, Long> {

    /** All snapshots owned by one concept (detail view, concept-delete cleanup). */
    List<NkdConceptSnapshotEntity> findByOwningConceptId(Long owningConceptId);

    /** All snapshots in one graph (ontology-delete cleanup). */
    List<NkdConceptSnapshotEntity> findByGraphName(String graphName);

    /** The unique snapshot for an (owner, NKD IRI) link, if any (upsert lookup). */
    Optional<NkdConceptSnapshotEntity> findByOwningConceptIdAndNkdIri(Long owningConceptId, String nkdIri);

    /** Every snapshot pointing at one NKD IRI (cross-owner). */
    List<NkdConceptSnapshotEntity> findByNkdIri(String nkdIri);

    /**
     * How many concepts in a graph link the same NKD IRI — the refcount guarding shared-copy removal.
     * The materialized copy may be dropped only when this reaches its last referrer; otherwise removing
     * one link would strip triples other concepts still need.
     */
    long countByGraphNameAndNkdIri(String graphName, String nkdIri);
}
