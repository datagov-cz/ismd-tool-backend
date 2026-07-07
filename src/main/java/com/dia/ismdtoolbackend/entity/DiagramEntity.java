package com.dia.ismdtoolbackend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The canonical visual layout of one ontology — a ReactFlow canvas. One diagram per ontology.
 *
 * <p>Holds only presentation data: node positions, edges the diagram owns, and the saved viewport. It
 * never owns concept content — real-concept nodes reference an IRI and are joined to live PG/RDF on read;
 * only draft nodes carry their own (pre-promotion) content. Because content has a single owner elsewhere,
 * this entity lives purely in Postgres — no outbox, no RDF write, no reconciler.
 *
 * <p>The {@code version} column backs an optimistic lock on the layout save so concurrent editors of the
 * same canvas get a conflict instead of a silent last-write-wins clobber.
 */
@Entity
@Table(
        name = "diagrams",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_diagrams_ontology_metadata",
                columnNames = "ontology_metadata_id"))
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor
@Getter
@Setter
public class DiagramEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The ontology this canvas visualizes. Unique — one canonical diagram per ontology. DB-cascade on
     * ontology delete drops the diagram (and, via their own FKs, its nodes and edges).
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ontology_metadata_id", nullable = false)
    private OntologyMetadataEntity ontologyMetadata;

    @Column(name = "user_id")
    private String userId;

    /** Saved pan/zoom, restored on load. Null until the canvas is first saved. */
    @Column(name = "viewport_x")
    private Double viewportX;

    @Column(name = "viewport_y")
    private Double viewportY;

    @Column(name = "viewport_zoom")
    private Double viewportZoom;

    @JsonIgnore
    @OneToMany(mappedBy = "diagram", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DiagramNodeEntity> nodes = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "diagram", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DiagramEdgeEntity> edges = new ArrayList<>();

    /** Optimistic-lock guard for concurrent layout saves. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}