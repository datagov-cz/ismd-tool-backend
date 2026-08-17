package com.dia.ismdtoolbackend.entity;

import com.dia.ismdtoolbackend.enums.DiagramEdgeKind;
import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * The persisted <em>presentation</em> state of a diagram edge. An edge's existence and kind are NOT read
 * from here — they are re-projected on every load from {@code live ⊕ overlay} by
 * {@code EdgeProjector}, because the semantics always live on a node (a VZTAH's {@code rdfs:domain} /
 * {@code rdfs:range}, a hierarchy target, an {@code skos:exactMatch}). This row carries only what RDF
 * cannot express: which handle each end attaches to.
 *
 * <p>A row is matched to its projected edge by {@code (edgeKind, sourceIri, targetIri)}. Repointing an
 * endpoint therefore drops the saved handles — the geometry was drawn for an endpoint the edge no longer
 * has — and the stale row is cleared by the next Save, which full-replaces the edge set.
 *
 * <p>Endpoints are {@code diagram_nodes} ids; a node's wire id is its {@code iri:…} reference, so the
 * read-side maps these FK ids to IRIs when assembling the payload.
 */
@Slf4j
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

    /** Serialized {@link EdgeWaypoint} list; null when the edge uses default routing. */
    @Column(name = "segments_json", columnDefinition = "text")
    private String segmentsJson;

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<List<EdgeWaypoint>> WAYPOINTS = new TypeReference<>() {
    };

    /** Deserialize the waypoints; {@code null} on absent/malformed JSON (logged). */
    public List<EdgeWaypoint> getSegments() {
        if (segmentsJson == null || segmentsJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(segmentsJson, WAYPOINTS);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize segments JSON for diagram edge id={}", id, e);
            return null;
        }
    }

    /** Serialize and store the waypoints; a null or empty list clears the column. */
    public void setSegments(List<EdgeWaypoint> segments) {
        if (segments == null || segments.isEmpty()) {
            this.segmentsJson = null;
            return;
        }
        try {
            this.segmentsJson = objectMapper.writeValueAsString(segments);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Failed to serialize segments for diagram edge id=" + id, e);
        }
    }
}