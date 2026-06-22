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
 *
 * <p><strong>C1 — the copy rides the OWNER aggregate.</strong> The write methods never call the
 * outbox themselves; they <em>contribute</em> the materialized triples to an {@link OwnerChangeSet}
 * the caller passes in. The caller (edit hook / local-copy endpoints) flushes that change set as a
 * single {@code OutboxWriter.enqueueUpsert} keyed on the <em>owner concept's</em> IRI, so the link
 * triple and the copy triples land in one aggregate, atomically and in order. The PG snapshot-row
 * write happens inside the same transaction as that enqueue.
 *
 * <p>This round handles {@code LINK_TARGET} snapshots only (m7).
 */
public interface NkdSnapshotService {

    /**
     * Creates or refreshes the snapshot for an (owner, NKD IRI) link, contributing the resulting
     * triple delta to {@code ownerChangeSet}.
     *
     * <p>Steps: assert {@code linkType} is one of the allowed {@code SnapshotLinkType}s (C3 enforcement
     * lives here, not in the validator); validate the IRI; fetch the published concept + its raw
     * triples from NKD (best-effort). If NKD has nothing → {@link #removeSnapshotAndLink} (upstream
     * gone). Otherwise upsert the row (snapshot JSON + {@code materializedTriples}) and:
     * <ul>
     *   <li>add the row's <em>prior</em> {@code materializedTriples} (M1 — the exact stored set, never
     *       recomputed from the lossy snapshot JSON) to {@code ownerChangeSet.toRemove};</li>
     *   <li>add the new materialized triple set to {@code ownerChangeSet.toAdd}.</li>
     * </ul>
     *
     * @param linkType the logical link type token (see {@code SnapshotLinkType.value()})
     * @return the upserted snapshot, or {@code null} if NKD had no such concept (removal path taken)
     */
    NkdConceptSnapshotEntity createOrRefreshSnapshot(ConceptMetadataEntity owner, String nkdIri,
                                                     String linkType, OwnerChangeSet ownerChangeSet);

    /**
     * Compares the stored snapshot against live NKD and caches the result on the row
     * ({@code lastDeviationStatus} / {@code lastCheckedAt}). Read-only with respect to the owner
     * graph — does not touch {@code ownerChangeSet}. On {@code CONCEPT_NOT_FOUND_IN_NKD} the caller
     * (not this method) should trigger removal in a write transaction.
     */
    PublishedConceptDeviationModel evaluateDeviation(NkdConceptSnapshotEntity snapshot);

    /**
     * Removes the link + snapshot, contributing the removal to {@code ownerChangeSet}.
     *
     * <p>{@code ownerOutgoingStatements} are the owner concept's current outgoing triples (from the
     * owner's graph model); every triple among them whose object is {@code snapshot.nkdIri} is added
     * to {@code toRemove} — this drops <em>all</em> link predicates to the target (broaderClass writes
     * both {@code rdfs:subClassOf} and a namespaced hierarchy prop), without reconstructing predicate
     * IRIs. The materialized copy triples are added to {@code toRemove} <strong>only when this is the
     * last referencing concept</strong> for the NKD IRI in the graph (C2 refcount —
     * {@code countByGraphNameAndNkdIri == 1}), so other owners' shared copy survives. Deletes the PG row.
     */
    void removeSnapshotAndLink(NkdConceptSnapshotEntity snapshot, Set<Statement> ownerOutgoingStatements,
                               OwnerChangeSet ownerChangeSet);

    List<NkdConceptSnapshotEntity> findForConcept(Long conceptId);

    List<NkdConceptSnapshotEntity> findForGraph(String graphName);
}
