package com.dia.ismdtoolbackend.entity;

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
 * The persisted <em>waypoints</em> of a diagram edge, and nothing else. An edge's existence, kind and
 * endpoints are NOT read from here — they are re-projected on every load from {@code live ⊕ overlay} by
 * {@code EdgeProjector}, because the semantics always live on a concept (a VZTAH's {@code rdfs:domain} /
 * {@code rdfs:range}, a hierarchy target, an {@code skos:exactMatch}). This row carries only what RDF
 * cannot express: how the link is routed.
 *
 * <p>A row is matched to its projected edge by {@link #edgeKey} — a VZTAH's own concept IRI, or the
 * composite {@code edge|KIND|source|target} of a hierarchy/equivalence link. Repointing an endpoint
 * therefore drops the saved waypoints: the geometry was drawn for an endpoint the edge no longer has, and
 * the stale row is cleared by the next Save, which full-replaces the edge set.
 *
 * <p>Endpoints are deliberately absent as columns. They were stored once and could silently disagree with
 * the projection they duplicated; see {@code .planning/diagram-edge-model-REDESIGN.md}.
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

    /**
     * The projected edge id these waypoints belong to; unique per diagram. A hierarchy key concatenates two
     * full concept IRIs, so the Postgres column is TEXT — {@code length} here is JPA metadata for schema
     * validation (H2 uses a bounded VARCHAR), not a ceiling on what Postgres stores.
     */
    @Column(name = "edge_key", nullable = false, length = 2048)
    private String edgeKey;

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
