package com.dia.ismdtoolbackend.outbox;

import com.dia.ismdtoolbackend.testsupport.pg.OutboxEntries;
import com.dia.ismdtoolbackend.testsupport.pg.OutboxFlowIT;
import com.dia.ismdtoolbackend.testsupport.pg.OutboxFlowTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2 (outbox plan) — verifies the {@code outbox_entry} schema applies under Liquibase +
 * {@code ddl-auto=validate} against REAL Postgres, the entity round-trips, and the claim query's
 * {@code FOR UPDATE SKIP LOCKED} actually skips a row another transaction holds. The last point is
 * exactly what H2 can't model, which is why this runs on Testcontainers.
 *
 * <p>Each test runs in a rolled-back test transaction unless it says otherwise.
 */
@OutboxFlowIT
@Transactional
class OutboxEntryRepositoryTest extends OutboxFlowTestBase {

    @Autowired
    private OutboxEntryRepository repository;

    @Autowired
    private TransactionTemplate txTemplate;

    private OutboxEntry pending(String aggregateIri, String graph, long seq) {
        OutboxEntry e = OutboxEntries.pending(graph, aggregateIri, OutboxOperation.UPSERT_CONCEPT, seq);
        e.setInsertTriples("<" + aggregateIri + "> <http://x/p> \"v\" .");
        return e;
    }

    @Test
    void schemaValidatesAndEntityRoundTrips() {
        OutboxEntry saved = repository.save(pending("https://x/pojem/a", "https://x", 1L));
        OutboxEntry found = repository.findById(saved.getId()).orElseThrow();

        assertThat(found.getAggregateIri()).isEqualTo("https://x/pojem/a");
        assertThat(found.getOperation()).isEqualTo(OutboxOperation.UPSERT_CONCEPT);
        assertThat(found.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(found.getAttempts()).isZero();
        assertThat(found.getInsertTriples()).contains("http://x/p");
        assertThat(found.getSeq()).isEqualTo(1L);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void initialGraphBarrierIncludesFailedButExcludesDoneAndOtherGraphs() {
        var create = pending("https://g", "https://g", 10L);
        create.setOperation(OutboxOperation.CREATE_GRAPH);
        repository.saveAndFlush(create);
        assertThat(repository.existsEarlierUnappliedCreateGraph("https://g", 10L)).isFalse();
        assertThat(repository.existsEarlierUnappliedCreateGraph("https://g", 11L)).isTrue();
        assertThat(repository.existsEarlierUnappliedCreateGraph("https://other", 11L)).isFalse();
        create.setStatus(OutboxStatus.FAILED);
        repository.saveAndFlush(create);
        assertThat(repository.existsEarlierUnappliedCreateGraph("https://g", 11L)).isTrue();
        create.setStatus(OutboxStatus.DONE);
        repository.saveAndFlush(create);
        assertThat(repository.existsEarlierUnappliedCreateGraph("https://g", 11L)).isFalse();
    }

    @Test
    void barrierAndOrderingHelpers() {
        repository.save(pending("https://x/pojem/a", "https://g", 10L));
        repository.save(pending("https://x/pojem/a", "https://g", 11L));
        repository.save(pending("https://x/pojem/b", "https://g", 12L));

        // Earlier unapplied exists for the same graph below seq 12 → DELETE_GRAPH barrier holds.
        assertThat(repository.existsEarlierUnappliedForGraph("https://g", 12L)).isTrue();
        assertThat(repository.existsEarlierUnappliedForGraph("https://g", 10L)).isFalse();

        // Aggregate 'a' has an earlier unapplied row below seq 11.
        assertThat(repository.existsEarlierUnappliedForAggregate("https://x/pojem/a", 11L)).isTrue();
        assertThat(repository.existsEarlierUnappliedForAggregate("https://x/pojem/b", 12L)).isFalse();

        assertThat(repository.countByStatus(OutboxStatus.PENDING)).isEqualTo(3);
    }

    // H1 regression: a FAILED earlier row must STILL block (both the graph barrier and the
    // aggregate guard use <> DONE, not = PENDING) — otherwise a DELETE_GRAPH could drop the graph
    // while a failed-and-unapplied edit to one of its concepts is outstanding, losing it silently.
    @Test
    void failedEarlierRow_stillBlocks_graphAndAggregate() {
        OutboxEntry failed = pending("https://x/pojem/a", "https://g", 20L);
        failed.setStatus(OutboxStatus.FAILED);
        repository.save(failed);
        repository.save(pending("https://x/pojem/a", "https://g", 21L)); // later DELETE_GRAPH candidate, same graph/agg

        assertThat(repository.existsEarlierUnappliedForGraph("https://g", 21L)).isTrue();
        assertThat(repository.existsEarlierUnappliedForAggregate("https://x/pojem/a", 21L)).isTrue();

        // Once the earlier row is DONE, the barrier/guard clears.
        failed.setStatus(OutboxStatus.DONE);
        repository.save(failed);
        assertThat(repository.existsEarlierUnappliedForGraph("https://g", 21L)).isFalse();
        assertThat(repository.existsEarlierUnappliedForAggregate("https://x/pojem/a", 21L)).isFalse();
    }

    // C1 regression: the claim must honour batchSize, not return the whole PENDING set (which would
    // defeat batching and SKIP-LOCKED multi-instance parallelism).
    @Test
    void claimPendingBatch_honoursBatchSize() {
        IntStream.range(0, 10).forEach(i ->
                repository.save(pending("https://x/pojem/" + i, "https://g", 1000L + i)));

        assertThat(repository.claimPendingBatch(3)).hasSize(3);
        assertThat(repository.claimPendingBatch(100)).hasSize(10); // fewer rows than the cap → all
    }

    @Test
    void retentionPruneRemovesOldDoneRows() {
        OutboxEntry done = pending("https://x/pojem/old", "https://g", 1L);
        done.setStatus(OutboxStatus.DONE);
        done.setCompletedAt(Instant.now().minusSeconds(3600));
        repository.save(done);

        OutboxEntry recentDone = pending("https://x/pojem/new", "https://g", 2L);
        recentDone.setStatus(OutboxStatus.DONE);
        recentDone.setCompletedAt(Instant.now());
        repository.save(recentDone);

        int removed = repository.deleteDoneBefore(Instant.now().minusSeconds(60));
        assertThat(removed).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(1);
    }

    /**
     * The point of Testcontainers: a row locked by an open transaction is SKIPPED (not blocked,
     * not returned) by another claimer. H2 PG-compat can't reproduce this.
     *
     * <p>{@code NOT_SUPPORTED} so this method runs with NO ambient {@code @DataJpaTest} transaction —
     * it drives two genuinely-independent transactions itself.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void claimPendingBatch_SkipsRowsLockedByAnotherTransaction() {
        Long id1 = txTemplate.execute(tx -> repository.save(pending("https://x/pojem/a", "https://g", 100L)).getId());
        txTemplate.executeWithoutResult(tx -> repository.save(pending("https://x/pojem/b", "https://g", 101L)));

        AtomicReference<List<Long>> claimedWhileLocked = new AtomicReference<>();
        ExecutorService otherClaimer = Executors.newSingleThreadExecutor();
        try {
            // Open TX #1 that claims+holds row id1 (lowest seq); while it's held, TX #2 (separate
            // thread, own connection) claims and must skip the locked row.
            txTemplate.executeWithoutResult(tx1 -> {
                List<OutboxEntry> firstClaim = repository.claimPendingBatch(1);
                assertThat(firstClaim).hasSize(1);
                assertThat(firstClaim.get(0).getId()).isEqualTo(id1);

                CompletableFuture<List<Long>> other = CompletableFuture.supplyAsync(() ->
                        txTemplate.execute(tx2 ->
                                repository.claimPendingBatch(10).stream()
                                        .map(OutboxEntry::getId).toList()), otherClaimer);
                claimedWhileLocked.set(other.join());
            });

            // TX #2 must NOT have seen id1 (locked) — it skipped to the next pending row only.
            assertThat(claimedWhileLocked.get()).doesNotContain(id1);
            assertThat(claimedWhileLocked.get()).hasSize(1);
        } finally {
            otherClaimer.shutdownNow();
        }
    }
}
