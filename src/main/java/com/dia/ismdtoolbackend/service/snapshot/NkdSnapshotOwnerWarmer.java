package com.dia.ismdtoolbackend.service.snapshot;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.outbox.OutboxConfig;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.service.NkdSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The per-owner unit of work for {@link NkdSnapshotWarmer}: snapshots one owner concept's published
 * NKD link-targets (writes the PG snapshot rows) in one {@code REQUIRES_NEW} transaction. Snapshot copies
 * live only in PG, so re-evaluating an existing copy produces no TDB2 delta and the change-set flush below
 * is a guarded no-op. A first-time materialization does produce one, and when it does the owner's
 * {@code updatedAt} is stamped with it — see the flush block.
 *
 * <p>Why its own bean + {@code REQUIRES_NEW}: a per-target failure (e.g. the unique-constraint race two
 * concurrent warms hit) marks the current transaction rollback-only, and catching it does not clear that
 * flag — so a shared transaction would let one poisoned target roll back the whole graph's batch at
 * commit. Isolating each owner means a failure loses only that owner; the rest commit.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NkdSnapshotOwnerWarmer {

    private final NkdSnapshotService nkdSnapshotService;
    private final OutboxConfig outboxConfig;
    private final OutboxWriter outboxWriter;
    private final OutboxRelayTrigger outboxRelayTrigger;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final ConceptMetadataRepository conceptMetadataRepository;

    /** A published NKD link-target of one owner concept. */
    public record Target(String nkdIri, String linkType) {
    }

    /**
     * Snapshot all {@code targets} for {@code owner} (writing PG rows) in its own transaction, isolated
     * from sibling owners. Returns true only if a TDB2 delta was produced; the warm path is PG-only, so
     * this is normally false (no relay nudge needed).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean warmOwner(String graphName, ConceptMetadataEntity owner, List<Target> targets) {
        OwnerChangeSet cs = new OwnerChangeSet();
        for (Target t : targets) {
            // Read-triggered warming: an existing copy is re-evaluated (drift → HAS_DEVIATIONS), never
            // overwritten — only a first-time link materializes. Overwriting the copy is an explicit
            // command (updateLocalCopy / edit reconcile), never a side effect of a read.
            nkdSnapshotService.refreshOrSeedForWarming(owner, t.nkdIri(), t.linkType(), cs);
        }
        if (cs.toRemove.isEmpty() && cs.toAdd.isEmpty()) {
            return false;
        }
        // The owner's RDF is about to change, so its concept row must say so: `updatedAt` is the stale-base
        // fingerprint a staged diagram overlay is validated against, and every SnapshotLinkType maps onto a
        // stageable overlay field. Without this the RDF moves while the fingerprint stays frozen and
        // STALE_BASE silently misses it. Only a first-time materialization reaches here — an
        // existing copy is re-evaluated, not overwritten — so a read does not gratuitously bump the row.
        owner.setUpdatedAt(LocalDateTime.now());
        conceptMetadataRepository.save(owner);
        if (outboxConfig.isEnabled()) {
            outboxWriter.enqueueUpsert(graphName, owner.getConceptIri(), cs.toRemove, cs.toAdd);
            outboxRelayTrigger.nudgeAfterCommit();
            return true;
        }
        // outbox-disabled fallback: direct concept-scoped delta (same shape the outbox relay applies).
        Model removeModel = ModelFactory.createDefaultModel().add(new ArrayList<>(cs.toRemove));
        Model addModel = ModelFactory.createDefaultModel().add(new ArrayList<>(cs.toAdd));
        jenaTDB2Repository.applyConceptDelta(owner.getConceptIri(), graphName, removeModel, addModel);
        return true;
    }
}
