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
 * A tracked "local copy" of a published NKD concept that an owning local concept links to. One row per
 * (owning concept, NKD IRI) link, holding the snapshotted NKD detail (for deviation comparison) and the
 * copy's exact triple set.
 *
 * <p>Written only through {@code NkdSnapshotService}. This row is the copy's <strong>sole home</strong>
 * — nothing is written to TDB2; the owner's graph holds only the link triple. Only
 * {@link SnapshotOrigin#LINK_TARGET} rows are currently produced.
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
    // TODO id is required for proper endpoint calling, we are NOT currently return it anywhere in the data layer
    private Long id;

    /**
     * The local concept that owns this link. Not db-cascade on delete — {@code NkdSnapshotService}
     * cascades removal so this row and the owner's link triple are dropped in the same boundary.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owning_concept_id", nullable = false)
    private ConceptMetadataEntity owningConcept;

    /** The published NKD concept's IRI (the link target). Czech/percent-encoded IRIs exceed 255. */
    @Column(name = "nkd_iri", nullable = false, length = 1024)
    private String nkdIri;

    /** Denormalized owner graph — the graph whose link triple this copy backs. */
    @Column(name = "graph_name", length = 1024)
    private String graphName;

    @Column(name = "origin", nullable = false)
    @Enumerated(EnumType.STRING)
    private SnapshotOrigin origin;

    /**
     * Which relation created the link — {@code subClassOf} / {@code subPropertyOf} / {@code exactMatch}
     * only (never domain/range/related). Null for {@link SnapshotOrigin#WORKING_COPY}.
     */
    @Column(name = "link_predicate", length = 1024)
    private String linkPredicate;

    /** Serialized {@link ConceptDetailModel} — the snapshotted NKD detail, for deviation comparison. */
    @Column(name = "snapshot_json", columnDefinition = "text")
    private String snapshotJson;

    /**
     * The copy's exact N-Triples set — its sole home; nothing is written to TDB2. A faithful, non-lossy
     * record of the NKD concept, unlike {@link #snapshotJson}, which holds the detail model the deviation
     * comparison runs on.
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