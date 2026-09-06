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
 * One visual layout of an ontology — a ReactFlow canvas. An ontology may have MANY diagrams, each a
 * differently-scoped view of the same concepts.
 *
 * <p>Holds only presentation data: node positions, edges the diagram owns, and the saved viewport. Nodes
 * reference a concept by IRI and are joined to live PG/RDF on read, so this entity lives purely in
 * Postgres — no outbox, no RDF write, no reconciler.
 *
 * <p>The {@code version} column backs an optimistic lock on the layout save. The comparison is made in
 * {@code DiagramServiceImpl.requireCurrentVersion} against the version the CLIENT sends, not by JPA.
 *
 * <p><strong>Save-path obligation.</strong> {@code @Version} bumps only when a column of the
 * {@code diagrams} row itself changes, and node/edge edits touch child tables. A save that mutates
 * {@code nodes}/{@code edges} must therefore call {@link #touch()} (or take
 * {@code OPTIMISTIC_FORCE_INCREMENT}) or the lock covers viewport changes alone.
 *
 * <p><strong>Node deletion.</strong> Delete nodes through {@link #removeNode(DiagramNodeEntity)},
 * never {@code getNodes().remove(...)}.
 */
@Entity
@Table(
        name = "diagrams",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_diagrams_ontology_name",
                columnNames = {"ontology_metadata_id", "name"}))
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor
@Getter
@Setter
public class DiagramEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The ontology this canvas visualizes. Many diagrams may share one ontology. DB-cascade on ontology
     * delete drops every diagram, and via their own FKs their nodes, edges and staged edits.
     *
     * <p>A diagram carries no owner column of its own, so an endpoint that authorizes the ontology slug
     * must still assert that the diagram it was handed belongs to that ontology.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ontology_metadata_id", nullable = false)
    private OntologyMetadataEntity ontologyMetadata;

    /**
     * User-facing name, distinguishing this canvas from the ontology's other diagrams. Unique within the
     * ontology, not globally — two slovníky may each have a "Hlavní diagram".
     */
    @Column(name = "name", nullable = false)
    private String name;

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
     * Incident waypoint rows are left alone: a row whose edge no longer projects finds no match on read
     * and is dropped by the next Save.
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