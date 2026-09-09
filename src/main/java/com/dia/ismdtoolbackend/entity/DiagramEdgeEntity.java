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
 * One edge's presence on a canvas, plus how it is routed. The row IS the membership: canvas membership is
 * user-curated and explicit, so an edge the projection could draw but no row names is not drawn, and a class
 * can sit on the canvas with none of its relationships shown.
 *
 * <p>An edge's kind and endpoints are re-projected on every load from {@code live ⊕ overlay} by
 * {@code EdgeProjector}, the semantics always living on a concept — a VZTAH's
 * {@code rdfs:domain}/{@code rdfs:range}, a hierarchy target, an {@code skos:exactMatch}. So this row holds
 * only what RDF cannot express: that the user placed the link, and how it is routed.
 *
 * <p>A row is matched to its projected edge by {@link #edgeKey} — a VZTAH's own concept IRI, or the
 * composite {@code edge|KIND|source|target} of a hierarchy or equivalence link. Repointing an endpoint
 * therefore drops the saved waypoints, and the stale row is cleared by the next Save.
 *
 * <p>Endpoints are deliberately absent as columns, having once been stored and able to disagree with the
 * projection they duplicated; see {@code .planning/diagram-edge-model-REDESIGN.md}.
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
     * The projected edge id this row places on the canvas, unique per diagram. A hierarchy key concatenates
     * two full concept IRIs, so the Postgres column is TEXT; {@code length} is JPA metadata for schema
     * validation, where H2 uses a bounded VARCHAR, not a ceiling on what Postgres stores.
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

    /** The waypoints, or {@code null} on absent or malformed JSON. */
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

    /** Stores the waypoints; a null or empty list clears the column. */
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
