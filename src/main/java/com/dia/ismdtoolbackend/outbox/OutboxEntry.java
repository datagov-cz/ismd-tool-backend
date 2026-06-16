package com.dia.ismdtoolbackend.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One pending TDB2/Fuseki mutation, written in the SAME Postgres transaction as the business
 * metadata change so the intent commits atomically with the metadata (the transactional-outbox
 * pattern). The relay (T5) drains {@code PENDING} rows to Fuseki and marks them {@code DONE} only
 * after a 2xx. See the outbox plan §Design.
 *
 * <p>The row carries the changed RDF inline ({@code deleteTriples}/{@code insertTriples} as
 * N-Triples, or {@code targetIris} as a JSON IRI list) so PG is a complete replay source — the
 * relay never re-derives RDF. Payloads are small because the only large-payload write site
 * ({@code uploadOntology}) is intentionally NOT outboxed.
 */
@Entity
@Table(name = "outbox_entry")
@NoArgsConstructor
@Getter
@Setter
public class OutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Concept or ontology IRI this op concerns. The relay orders rows per aggregate by {@link #seq}. */
    @Column(name = "aggregate_iri", nullable = false, length = 1024)
    private String aggregateIri;

    @Column(name = "graph_name", nullable = false, length = 1024)
    private String graphName;

    @Column(name = "operation", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private OutboxOperation operation;

    /** N-Triples to remove (UPSERT_CONCEPT); null otherwise. */
    @Column(name = "delete_triples", columnDefinition = "text")
    private String deleteTriples;

    /** N-Triples to add (UPSERT_CONCEPT); null otherwise. */
    @Column(name = "insert_triples", columnDefinition = "text")
    private String insertTriples;

    /** JSON array of IRIs (DELETE_CONCEPTS); null otherwise. */
    @Column(name = "target_iris", columnDefinition = "text")
    private String targetIris;

    @Column(name = "status", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @Column(name = "claimed_at", columnDefinition = "timestamptz")
    private Instant claimedAt;

    @Column(name = "completed_at", columnDefinition = "timestamptz")
    private Instant completedAt;

    /**
     * Monotonic ordering key, assigned from a dedicated DB sequence at enqueue time (by
     * {@link OutboxWriter}, T4). Drives strict per-aggregate apply order in the relay, independent
     * of the PK generation strategy.
     */
    @Column(name = "seq", nullable = false)
    private long seq;
}
