package com.dia.ismdtoolbackend.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Persistence for {@link OutboxEntry}. The relay (T5) uses the claim/barrier queries here; the
 * per-aggregate ordering and {@code DELETE_GRAPH} barrier *policy* lives in the relay, built on
 * these primitives, so the ordering rules stay unit-testable rather than buried in one mega-query.
 */
public interface OutboxEntryRepository extends JpaRepository<OutboxEntry, Long> {

    /**
     * Claims up to {@code pageable} PENDING rows in {@code seq} order for the calling relay,
     * skipping rows another relay instance already holds. {@code FOR UPDATE SKIP LOCKED} is what
     * makes the relay multi-instance-safe without a distributed lock (outbox plan §Relay). The
     * caller MUST be inside a transaction that stays open while it applies the claimed rows.
     */
    @Query(value = """
            SELECT * FROM ismd_schema.outbox_entry
            WHERE status = 'PENDING'
            ORDER BY seq
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEntry> claimPendingBatch(Pageable pageable);

    /** Oldest pending row (lowest {@code seq}) for one graph — the relay's per-graph head. */
    List<OutboxEntry> findByGraphNameAndStatusOrderBySeqAsc(String graphName, OutboxStatus status);

    /**
     * True if any row for {@code graphName} with {@code seq} below {@code seq} is still PENDING —
     * the {@code DELETE_GRAPH} barrier: a graph delete must not apply while an earlier concept
     * mutation in that graph is unprocessed.
     */
    @Query("""
            SELECT COUNT(e) > 0 FROM OutboxEntry e
            WHERE e.graphName = :graphName AND e.status = com.dia.ismdtoolbackend.outbox.OutboxStatus.PENDING
              AND e.seq < :seq
            """)
    boolean existsEarlierPendingForGraph(@Param("graphName") String graphName, @Param("seq") long seq);

    /**
     * True if the aggregate has a PENDING row with a lower {@code seq} than {@code seq} — strict
     * per-aggregate ordering guard (don't apply a row while an earlier row for the same concept is
     * still pending or failed-blocking).
     */
    @Query("""
            SELECT COUNT(e) > 0 FROM OutboxEntry e
            WHERE e.aggregateIri = :aggregateIri
              AND e.status <> com.dia.ismdtoolbackend.outbox.OutboxStatus.DONE
              AND e.seq < :seq
            """)
    boolean existsEarlierUnappliedForAggregate(@Param("aggregateIri") String aggregateIri, @Param("seq") long seq);

    long countByStatus(OutboxStatus status);

    List<OutboxEntry> findByStatusOrderBySeqAsc(OutboxStatus status);

    /** Retention prune — drop DONE rows older than {@code cutoff}. Returns the number removed. */
    @Modifying
    @Query("DELETE FROM OutboxEntry e WHERE e.status = com.dia.ismdtoolbackend.outbox.OutboxStatus.DONE AND e.completedAt < :cutoff")
    int deleteDoneBefore(@Param("cutoff") Instant cutoff);
}
