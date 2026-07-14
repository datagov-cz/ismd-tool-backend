package com.dia.ismdtoolbackend.service.snapshot;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.outbox.OutboxConfig;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.service.NkdSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * The per-owner unit of work for {@link NkdSnapshotWarmer}: snapshots one owner concept's published
 * NKD link-targets (writes the PG snapshot rows) in one {@code REQUIRES_NEW} transaction. Snapshot copies
 * live only in PG, so warming produces no TDB2 delta — the change-set flush below is a guarded no-op on
 * this path, kept for symmetry with the edit/endpoint flush shape.
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
            // createOrRefreshSnapshot seeds the deviation cache (NO_DEVIATION at snapshot time), so no
            // separate evaluateDeviation re-fetch here — that comparison is NO_DEVIATION by construction.
            nkdSnapshotService.createOrRefreshSnapshot(owner, t.nkdIri(), t.linkType(), cs);
        }
        if (cs.toRemove.isEmpty() && cs.toAdd.isEmpty()) {
            return false;
        }
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
