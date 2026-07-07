package com.dia.ismdtoolbackend.entity;

import com.dia.ismdtoolbackend.enums.DiagramEdgeKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A persisted diagram edge — the diagram stores every edge the FE draws, real↔real included. The edge's
 * {@link #edgeKind} plus its two endpoints fully describe the link (what kind of relationship it is and
 * what it connects); edges are not re-derived from RDF on load. A class property is one of these: a
 * property node linked to its class by a {@link com.dia.ismdtoolbackend.enums.DiagramEdgeKind#DOMAIN}
 * ({@code rdfs:domain}) edge.
 *
 * <p>Endpoints are {@code diagram_nodes} ids. A node's id serves as its ReactFlow {@code node.id} for
 * drafts; a real-concept node's ReactFlow id is its {@code iri:…} reference, so the read-side maps these
 * FK ids to the wire ids when assembling the payload.
 */
@Entity
@Table(name = "diagram_edges")
@NoArgsConstructor
@Getter
@Setter
public class DiagramEdgeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "diagram_id", nullable = false)
    private DiagramEntity diagram;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_node_id", nullable = false)
    private DiagramNodeEntity sourceNode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_node_id", nullable = false)
    private DiagramNodeEntity targetNode;

    @Column(name = "edge_kind", nullable = false)
    @Enumerated(EnumType.STRING)
    private DiagramEdgeKind edgeKind;

    /** Anchor id on the source node when it exposes multiple handles (e.g. per-property ports); nullable. */
    @Column(name = "source_handle")
    private String sourceHandle;

    /** Anchor id on the target node; nullable. */
    @Column(name = "target_handle")
    private String targetHandle;
}