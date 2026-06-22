package com.dia.ismdtoolbackend.entity;

import com.dia.ismdtoolbackend.enums.SnapshotOrigin;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.DeviationStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * A tracked "local copy" of a published NKD concept that an owning local concept links to.
 *
 * <p>One row per (owning concept, NKD IRI) link. The row holds the snapshotted NKD concept detail
 * (for deviation comparison) and the exact RDF triple set materialized into the owner's graph (the
 * source of truth for removing/replacing those triples). See
 * {@code .planning/nkd-local-copy-snapshot-PLAN.md}.
 *
 * <p><strong>Read-mostly.</strong> Writes happen only through {@code NkdSnapshotService}; the
 * materialized triples reach TDB2 via the <em>owning concept's</em> outbox aggregate (C1), never a
 * direct write from here.
 *
 * <p><strong>Scope:</strong> {@link SnapshotOrigin#LINK_TARGET} rows only this round (m7).
 */
@Entity
@Table(
        name = "nkd_concept_snapshots",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_nkd_snapshot_owner_iri",
                columnNames = {"owning_concept_id", "nkd_iri"}))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@Slf4j
public class NkdConceptSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The local concept that owns this link. Not db-cascade on delete — {@code NkdSnapshotService}
     * cascades removal so the materialized Fuseki triples are cleaned in the same boundary.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owning_concept_id", nullable = false)
    private ConceptMetadataEntity owningConcept;

    /** The published NKD concept's IRI (the link target). Czech/percent-encoded IRIs exceed 255. */
    @Column(name = "nkd_iri", nullable = false, length = 1024)
    private String nkdIri;

    /** Denormalized owner graph — where the copy is materialized. */
    @Column(name = "graph_name", length = 1024)
    private String graphName;

    @Column(name = "origin", nullable = false)
    @Enumerated(EnumType.STRING)
    private SnapshotOrigin origin;

    /**
     * Which relation created the link — {@code subClassOf} / {@code subPropertyOf} / {@code exactMatch}
     * only (never domain/range/related). Null for {@link SnapshotOrigin#SELF_PUBLISHED}.
     */
    @Column(name = "link_predicate", length = 1024)
    private String linkPredicate;

    /** Serialized {@link ConceptDetailModel} — the snapshotted NKD detail, for deviation comparison. */
    @Column(name = "snapshot_json", columnDefinition = "text")
    private String snapshotJson;

    /**
     * The exact N-Triples set last materialized into the owner graph (M1). This — NOT
     * {@link #snapshotJson}, which is lossy — is the source of truth for the delete-set when
     * re-materializing or removing the copy, so NKD drift never leaves stale triples behind.
     */
    @Column(name = "materialized_triples", columnDefinition = "text")
    private String materializedTriples;

    /** Cached result of the last deviation evaluation. */
    @Column(name = "last_deviation_status")
    @Enumerated(EnumType.STRING)
    private DeviationStatus lastDeviationStatus;

    /** When the local copy was last (re-)snapshotted. */
    @Column(name = "snapshot_at", columnDefinition = "timestamptz")
    private Instant snapshotAt;

    /** When deviation was last evaluated (staleness, distinct from {@link #snapshotAt}). */
    @Column(name = "last_checked_at", columnDefinition = "timestamptz")
    private Instant lastCheckedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Deserialize the stored NKD detail. Returns {@code null} on absent/malformed JSON (logged). */
    public ConceptDetailModel getSnapshot() {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(snapshotJson, ConceptDetailModel.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize snapshot JSON for nkdIri={}", nkdIri, e);
            return null;
        }
    }

    /** Serialize and store the NKD detail. A null snapshot clears the column. */
    public void setSnapshot(ConceptDetailModel snapshot) {
        if (snapshot == null) {
            this.snapshotJson = null;
            return;
        }
        try {
            this.snapshotJson = objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize snapshot for nkdIri={}", nkdIri, e);
            this.snapshotJson = null;
        }
    }
}