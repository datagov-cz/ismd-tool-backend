package com.dia.ismdtoolbackend.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2 (outbox plan) — verifies the {@code outbox_entry} schema applies under Liquibase +
 * {@code ddl-auto=validate} against REAL Postgres, the entity round-trips, and the claim query's
 * {@code FOR UPDATE SKIP LOCKED} actually skips a row another transaction holds. The last point is
 * exactly what H2 can't model, which is why this runs on Testcontainers.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@Import(TransactionTemplateConfig.class)
// Scope the persistence unit to the outbox package so ddl-auto=validate checks ONLY the outbox
// schema. (Other entities — e.g. ValidationReportEntity — have a pre-existing Instant↔timestamptz
// mapping that fails strict validation on real Postgres; that's out of T2's scope. See notes.)
@EntityScan(basePackageClasses = OutboxEntry.class)
@EnableJpaRepositories(basePackageClasses = OutboxEntryRepository.class)
class OutboxEntryRepositoryTest extends PostgresIntegrationTestBase {

    @Autowired
    private OutboxEntryRepository repository;

    @Autowired
    private TransactionTemplate txTemplate;

    private OutboxEntry pending(String aggregateIri, String graph, long seq) {
        OutboxEntry e = new OutboxEntry();
        e.setAggregateIri(aggregateIri);
        e.setGraphName(graph);
        e.setOperation(OutboxOperation.UPSERT_CONCEPT);
        e.setInsertTriples("<" + aggregateIri + "> <http://x/p> \"v\" .");
        e.setStatus(OutboxStatus.PENDING);
        e.setCreatedAt(Instant.now());
        e.setSeq(seq);
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
    void barrierAndOrderingHelpers() {
        repository.save(pending("https://x/pojem/a", "https://g", 10L));
        repository.save(pending("https://x/pojem/a", "https://g", 11L));
        repository.save(pending("https://x/pojem/b", "https://g", 12L));

        // Earlier pending exists for the same graph below seq 12 → DELETE_GRAPH barrier holds.
        assertThat(repository.existsEarlierPendingForGraph("https://g", 12L)).isTrue();
        assertThat(repository.existsEarlierPendingForGraph("https://g", 10L)).isFalse();

        // Aggregate 'a' has an earlier unapplied row below seq 11.
        assertThat(repository.existsEarlierUnappliedForAggregate("https://x/pojem/a", 11L)).isTrue();
        assertThat(repository.existsEarlierUnappliedForAggregate("https://x/pojem/b", 12L)).isFalse();

        assertThat(repository.countByStatus(OutboxStatus.PENDING)).isEqualTo(3);
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
     * it drives two genuinely-independent transactions itself and cleans up its own rows.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void claimPendingBatch_SkipsRowsLockedByAnotherTransaction() {
        Long id1 = txTemplate.execute(tx -> repository.save(pending("https://x/pojem/a", "https://g", 100L)).getId());
        txTemplate.executeWithoutResult(tx -> repository.save(pending("https://x/pojem/b", "https://g", 101L)));

        AtomicReference<List<Long>> claimedWhileLocked = new AtomicReference<>();
        try {
            // Open TX #1 that claims+holds row id1 (lowest seq); while it's held, TX #2 (separate
            // thread, own connection) claims and must skip the locked row.
            txTemplate.executeWithoutResult(tx1 -> {
                List<OutboxEntry> firstClaim = repository.claimPendingBatch(PageRequest.of(0, 1));
                assertThat(firstClaim).hasSize(1);
                assertThat(firstClaim.get(0).getId()).isEqualTo(id1);

                CompletableFuture<List<Long>> other = CompletableFuture.supplyAsync(() ->
                        txTemplate.execute(tx2 ->
                                repository.claimPendingBatch(PageRequest.of(0, 10)).stream()
                                        .map(OutboxEntry::getId).toList()));
                claimedWhileLocked.set(other.join());
            });

            // TX #2 must NOT have seen id1 (locked) — it skipped to the next pending row only.
            assertThat(claimedWhileLocked.get()).doesNotContain(id1);
            assertThat(claimedWhileLocked.get()).hasSize(1);
        } finally {
            txTemplate.executeWithoutResult(tx -> repository.deleteAll());
        }
    }
}
