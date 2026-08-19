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
 * same canvas get a conflict instead of a silent last-write-wins clobber. The comparison is made in
 * {@code DiagramServiceImpl.requireCurrentVersion} against the version the CLIENT sends, not by JPA:
 * the save loads this row fresh in its own transaction, so Hibernate would only ever compare the
 * just-read version against itself and always win.
 *
 * <p><strong>Save-path obligation.</strong> {@code @Version} only bumps when a column of the
 * {@code diagrams} row itself changes. Node/edge edits touch child tables and do <em>not</em> dirty this
 * row, so a layout save that only moves nodes would slip past the lock. The save service MUST force the
 * bump — acquire {@code LockModeType.OPTIMISTIC_FORCE_INCREMENT} on the diagram (or call
 * {@link #touch()}) whenever it mutates {@code nodes}/{@code edges} — or the optimistic lock protects
 * only viewport changes.
 *
 * <p><strong>Node deletion.</strong> Delete nodes through {@link #removeNode(DiagramNodeEntity)},
 * never {@code getNodes().remove(...)}.
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
     *
     * <p>Ownership is gated by diagram's ontology. Diagram carries no owner column of its own.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ontology_metadata_id", nullable = false)
    private OntologyMetadataEntity ontologyMetadata;

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

    /**
     * Mark the diagram row dirty so a save bumps {@code @Version} even when only child nodes/edges changed.
     */
    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Attach a node to this diagram (both sides of the association). Always add through here so the
     * collection holds the managed instance.
     */
    public void addNode(DiagramNodeEntity node) {
        node.setDiagram(this);
        this.nodes.add(node);
    }

    /** Attach an edge's waypoint row to this diagram (both sides). */
    public void addEdge(DiagramEdgeEntity edge) {
        edge.setDiagram(this);
        this.edges.add(edge);
    }

    /**
     * Remove a node and null the {@code parentNodeId} of any children grouped under it — one unit of work.
     *
     * <p>Incident waypoint rows are deliberately left alone: an edge row records only the projected edge id
     * it carries geometry for, so a row whose edge no longer projects finds no match on read and is dropped
     * by the next Save, which full-replaces the set.
     */
    public void removeNode(DiagramNodeEntity node) {
        if (node.getId() != null) {
            for (DiagramNodeEntity child : this.nodes) {
                if (node.getId().equals(child.getParentNodeId())) {
                    child.setParentNodeId(null);
                }
            }
        }
        this.nodes.remove(node);
    }
}