package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.NkdConceptSnapshotEntity;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.service.snapshot.OwnerChangeSet;
import org.apache.jena.rdf.model.Statement;

import java.util.List;
import java.util.Set;

/**
 * Tracks "local copies" of published NKD concepts that local concepts link to. Centralizes
 * snapshot create/refresh, deviation evaluation, and removal.
 * <p>
 * The write methods never call the outbox themselves; they contribute the materialized
 * triples to an {@link OwnerChangeSet} the caller passes in. The caller (edit hook / local-copy endpoints)
 * flushes that change set as a single {@code OutboxWriter.enqueueUpsert} keyed on the owner concept's IRI
 * so the link triple and the copy triples land in one aggregate, atomically and in order. The PG snapshot-row
 * write happens inside the same transaction as that enqueue.
 */
public interface NkdSnapshotService {

    /**
     * Creates or refreshes the snapshot for an (owner, NKD IRI) link, contributing the resulting
     * triple delta to {@code ownerChangeSet}.
     * @param linkType the logical link type token (see {@code SnapshotLinkType.value()})
     * @return the upserted snapshot, or {@code null} if NKD had no such concept (removal path taken)
     */
    NkdConceptSnapshotEntity createOrRefreshSnapshot(ConceptMetadataEntity owner, String nkdIri,
                                                     String linkType, OwnerChangeSet ownerChangeSet);

    /**
     * Compares the stored snapshot against live NKD and caches the result on the row
     * ({@code lastDeviationStatus} / {@code lastCheckedAt}).
     */
    PublishedConceptDeviationModel evaluateDeviation(NkdConceptSnapshotEntity snapshot);

    /**
     * Removes the link + snapshot, contributing the removal to {@code ownerChangeSet}.
     * <p>
     * {@code ownerOutgoingStatements} are the owner concept's current outgoing triples (from the
     * owner's graph model); every triple among them whose object is {@code snapshot.nkdIri} is added
     * to {@code toRemove} — this drops all link predicates to the target (broaderClass writes
     * both {@code rdfs:subClassOf} and a namespaced hierarchy prop), without reconstructing predicate
     * IRIs. The materialized copy triples are added to {@code toRemove} only when this is the
     * last referencing concept for the NKD IRI in the graph.
     */
    void removeSnapshotAndLink(NkdConceptSnapshotEntity snapshot, Set<Statement> ownerOutgoingStatements,
                               OwnerChangeSet ownerChangeSet);

    List<NkdConceptSnapshotEntity> findForConcept(Long conceptId);

    List<NkdConceptSnapshotEntity> findForGraph(String graphName);

    /**
     * Concept-deletion cascade. Deletes the PG snapshot rows owned by the concepts being removed.
     * The copy lives only in PG, so there is nothing to sweep out of TDB2.
     * @param deletedConceptIds the owned concepts being deleted (their PG ids)
     * @param graphName         the graph they belong to
     */
    void cascadeConceptDeletion(List<Long> deletedConceptIds, String graphName);

    /** Whole-ontology deletion cascade. Deletes all PG snapshot rows for the graph. */
    void cascadeGraphDeletion(String graphName);
}
