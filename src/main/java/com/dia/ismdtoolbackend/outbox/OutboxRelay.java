package com.dia.ismdtoolbackend.outbox;

import com.dia.ismdtoolbackend.exception.JenaTDB2Exception;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drains {@link OutboxEntry} rows to TDB2/Fuseki. One {@link #drainOnce()} pass claims a batch of
 * PENDING rows ({@code FOR UPDATE SKIP LOCKED}) and applies each that is at its aggregate's head,
 * marking DONE on success. Per the outbox plan (claim-then-gate decision):
 *
 * <ul>
 *   <li><b>Per-aggregate strict order:</b> a row is applied only if no earlier-{@code seq} unapplied
 *       row exists for its aggregate ({@link OutboxEntryRepository#existsEarlierUnappliedForAggregate}).
 *       A blocked row is left PENDING for a later pass (self-correcting).</li>
 *   <li><b>DELETE_GRAPH barrier:</b> additionally gated on no earlier unapplied row for the graph
 *       ({@link OutboxEntryRepository#existsEarlierUnappliedForGraph}).</li>
 *   <li><b>Idempotent apply:</b> {@code DELETE DATA}/{@code INSERT DATA} and delete-by-target are
 *       safe to re-run, so a crash between Fuseki 2xx and the DONE commit just re-applies next pass.</li>
 *   <li><b>Failure:</b> on {@link JenaTDB2Exception} the row's attempts increment; at the cap it
 *       becomes FAILED (and blocks its aggregate until an admin retry). Either way the relay stops
 *       processing that aggregate for the rest of this pass to preserve order.</li>
 * </ul>
 *
 * <p>The whole pass runs in ONE transaction so the claim's row locks are held while applying — that
 * is what stops a second relay instance from applying the same rows (a concurrent relay's claim
 * reads committed state, so it sees this relay's still-uncommitted earlier row as not-DONE and gates
 * its later same-aggregate row out — that is the multi-instance safety proof).
 *
 * <p>Within one pass, each row's status change is {@code flush()}ed immediately so the NEXT row's
 * gate query observes it — correctness does not rely on Hibernate's default autoflush staying on.
 * Holding a DB tx across the (semaphore-bounded) Fuseki HTTP calls is accepted; the batch is bounded
 * by {@code outbox.batch-size}. The loop is sequential (no intra-pass parallelism).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxRelay {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> IRI_LIST = new TypeReference<>() {};

    private final OutboxEntryRepository repository;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final OutboxConfig config;

    /**
     * Runs one drain pass. Returns the number of rows successfully applied (marked DONE) this pass.
     *
     * <p>{@code REQUIRES_NEW}: a drain is always its own unit of work, independent of any caller's
     * transaction. This matters for the after-commit nudge — it runs inside the just-committed
     * transaction's {@code afterCommit} synchronization, where a default {@code REQUIRED} would find
     * the completing tx still bound and fail to start a fresh one ({@code TransactionRequiredException}).
     * A new tx also keeps the claim's row locks scoped to exactly this pass.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int drainOnce() {
        List<OutboxEntry> batch = repository.claimPendingBatch(config.getBatchSize());
        if (batch.isEmpty()) {
            return 0;
        }
        // Aggregates we've decided to stop processing this pass (a row was blocked or failed), so a
        // later same-aggregate row in this batch is not applied out of order.
        Set<String> haltedAggregates = new HashSet<>();
        int applied = 0;

        for (OutboxEntry row : batch) {
            String aggregate = row.getAggregateIri();
            if (haltedAggregates.contains(aggregate)) {
                continue;
            }
            // Gate: must be at the aggregate's head (no earlier unapplied row for it).
            if (repository.existsEarlierUnappliedForAggregate(aggregate, row.getSeq())) {
                haltedAggregates.add(aggregate);
                continue;
            }
            // DELETE_GRAPH barrier: no earlier unapplied row anywhere in the graph.
            if (row.getOperation() == OutboxOperation.DELETE_GRAPH
                    && repository.existsEarlierUnappliedForGraph(row.getGraphName(), row.getSeq())) {
                haltedAggregates.add(aggregate);
                continue;
            }
            row.setClaimedAt(Instant.now()); // record the last apply attempt (observability / T8 status)
            try {
                apply(row);
                row.setStatus(OutboxStatus.DONE);
                row.setCompletedAt(Instant.now());
                applied++;
            } catch (JenaTDB2Exception | SparqlEndpointUnavailableException e) {
                // Transient/store failure — retry until the attempts cap, then FAILED.
                // SparqlEndpointUnavailableException is how the executor now reports a Fuseki
                // outage (503/connection drop); like a JenaTDB2Exception it must retry, not
                // permanent-fail down the generic RuntimeException branch below.
                recordTransientFailure(row, e);
                haltedAggregates.add(aggregate); // preserve order: no later row for this aggregate
            } catch (IllegalArgumentException e) {
                // Permanent bad payload (e.g. a blank node, which DELETE/INSERT DATA cannot apply).
                // Retrying can't help — fail the row immediately so it stops blocking after review.
                recordPermanentFailure(row, e);
                haltedAggregates.add(aggregate);
            } catch (RuntimeException e) {
                // Any OTHER unexpected runtime — e.g. a RiotException from parsing a corrupt
                // delete/insert_triples payload, or a QueryParseException building the update —
                // is thrown INSIDE apply() but BEFORE the executor wraps it as JenaTDB2Exception,
                // so it would otherwise escape drainOnce() and roll back the whole REQUIRES_NEW pass
                // (losing earlier rows' DONE marks) while never reaching the FAILED path — a silent
                // queue wedge. Treat it as a permanent bad-payload failure: fail the row so it is
                // visible via /api/admin/outbox/failed and stops poisoning the batch. Retrying a
                // corrupt payload can't help.
                recordPermanentFailure(row, e);
                haltedAggregates.add(aggregate);
            }
            // Flush the status change NOW so the next row's gate query (existsEarlier*) sees this
            // row's new state. Do NOT rely on Hibernate's default autoflush-before-query: a later
            // same-aggregate row in this batch must observe THIS row as DONE/FAILED to be ordered
            // correctly, and that correctness must not hinge on the flush mode staying AUTO.
            repository.flush();
        }
        log.info("Outbox drain: claimed {}, applied {}", batch.size(), applied);
        return applied;
    }

    private void apply(OutboxEntry row) {
        switch (row.getOperation()) {
            case UPSERT_CONCEPT -> jenaTDB2Repository.applyConceptDelta(
                    row.getAggregateIri(),
                    row.getGraphName(),
                    OutboxTriples.parse(row.getDeleteTriples()),
                    OutboxTriples.parse(row.getInsertTriples()));
            case DELETE_CONCEPTS -> {
                List<String> iris = readIriList(row.getTargetIris());
                if (!iris.isEmpty()) {
                    jenaTDB2Repository.deleteConceptsFromGraph(iris, row.getGraphName());
                }
            }
            case DELETE_GRAPH -> jenaTDB2Repository.deleteGraph(row.getGraphName());
        }
    }

    private void recordTransientFailure(OutboxEntry row, RuntimeException e) {
        row.setAttempts(row.getAttempts() + 1);
        row.setLastError(truncate(e.getMessage()));
        if (row.getAttempts() >= config.getMaxAttempts()) {
            row.setStatus(OutboxStatus.FAILED);
            log.error("CRITICAL: outbox row {} (op={}, aggregate={}) FAILED after {} attempts; "
                            + "blocks its aggregate until an admin retry", row.getId(), row.getOperation(),
                    row.getAggregateIri(), row.getAttempts(), e);
        } else {
            log.warn("Outbox row {} apply failed (attempt {}/{}); will retry", row.getId(),
                    row.getAttempts(), config.getMaxAttempts(), e);
        }
    }

    /** Permanent failure (bad payload): straight to FAILED — retrying cannot help. */
    private void recordPermanentFailure(OutboxEntry row, RuntimeException e) {
        row.setAttempts(row.getAttempts() + 1);
        row.setLastError(truncate(e.getMessage()));
        row.setStatus(OutboxStatus.FAILED);
        log.error("CRITICAL: outbox row {} (op={}, aggregate={}) permanently FAILED (bad payload); "
                        + "blocks its aggregate until an admin resolves it", row.getId(), row.getOperation(),
                row.getAggregateIri(), e);
    }

    private List<String> readIriList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, IRI_LIST);
        } catch (Exception e) {
            // A malformed target_iris is a data corruption we cannot apply; surface as a TDB2-style
            // failure so the row goes through the normal attempts/FAILED path rather than crashing
            // the whole pass.
            throw new JenaTDB2Exception("Corrupt target_iris JSON in outbox row: " + e.getMessage(), e);
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 4000 ? s : s.substring(0, 4000);
    }
}
