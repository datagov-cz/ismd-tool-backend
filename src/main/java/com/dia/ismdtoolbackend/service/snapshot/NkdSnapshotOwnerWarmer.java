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
 * NKD link-targets and flushes its single owner-keyed outbox upsert — all in one
 * {@code REQUIRES_NEW} transaction.
 *
 * <p>Why its own bean + {@code REQUIRES_NEW}: a per-target failure (e.g. the unique-constraint race
 * two concurrent warms hit) throws out of the inner {@code @Transactional} service calls, which marks
 * the current transaction rollback-only — catching it does NOT clear that flag. If all owners
 * shared one transaction, one poisoned target would silently roll back the entire graph's warm batch
 * at commit ({@code UnexpectedRollbackException}). Isolating each owner in its own transaction means a
 * failure loses only that owner; the rest commit. It also keeps the row-writes and the single
 * {@code enqueueUpsert} atomic (C1) within that owner's transaction.
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
     * Snapshot all {@code targets} for {@code owner} and flush one owner-keyed upsert. Own transaction,
     * isolated from sibling owners. Returns true if anything was materialized (caller may nudge the relay).
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
