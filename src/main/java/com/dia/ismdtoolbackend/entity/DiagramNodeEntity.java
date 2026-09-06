package com.dia.ismdtoolbackend.entity;

import com.dia.ismdtoolbackend.enums.DiagramNodeBacking;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * One node on a diagram canvas: a position for a materialized ISMD concept ({@link #conceptIri}).
 * Layout only — a row means "this concept is on the canvas". Staged structural edits live in
 * {@link DiagramPendingEditEntity}. See {@code docs/DIAGRAM_LAYER.md}.
 */
@Entity
@Table(name = "diagram_nodes")
@NoArgsConstructor
@Getter
@Setter
@Slf4j
public class DiagramNodeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "diagram_id", nullable = false)
    private DiagramEntity diagram;

    @Column(name = "backing", nullable = false)
    @Enumerated(EnumType.STRING)
    private DiagramNodeBacking backing = DiagramNodeBacking.ISMD_CONCEPT;

    /** Referenced concept's IRI — always set. */
    @Column(name = "concept_iri", length = 1024, nullable = false)
    private String conceptIri;

    @Column(name = "pos_x", nullable = false)
    private Double posX;

    @Column(name = "pos_y", nullable = false)
    private Double posY;

    /** Group node rendered collapsed; round-trips through the layout save and the read. */
    @Column(name = "collapsed", nullable = false)
    private boolean collapsed = false;

    /** Parent node id for grouping/containers (ReactFlow {@code parentId}); null for top-level nodes. */
    @Column(name = "parent_node_id")
    private Long parentNodeId;

    /** Serialized IRI list: the VLASTNOST rows this class cell renders. */
    @Column(name = "visible_properties_json", columnDefinition = "text")
    private String visiblePropertiesJson;

    /**
     * This node references a concept OUTSIDE the diagram's ontology graph — placed for context and
     * rendered read-only. A claim the write path verifies both ways: a foreign IRI needs this set, and a
     * row that sets it must resolve to another graph. No overlay may target such a concept.
     */
    @Column(name = "is_foreign", nullable = false)
    private boolean isForeign = false;

    @PrePersist
    @PreUpdate
    private void validateNodeInvariant() {
        if (backing == null) {
            throw new IllegalStateException("Diagram node backing must be set");
        }
        if (conceptIri == null) {
            throw new IllegalStateException(
                    "Diagram node must carry a conceptIri (id=" + id + ")");
        }
    }

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * The property IRIs this class cell renders; empty on absent/malformed JSON (logged). Empty is a
     * meaningful value — an uncurated class shows no rows — so this never falls back to "all".
     */
    public List<String> getVisibleProperties() {
        if (visiblePropertiesJson == null || visiblePropertiesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(visiblePropertiesJson, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize visible-properties JSON for diagram node id={}", id, e);
            return List.of();
        }
    }

    /** Store the rendered property IRIs; null or empty clears the column. */
    public void setVisibleProperties(List<String> properties) {
        if (properties == null || properties.isEmpty()) {
            this.visiblePropertiesJson = null;
            return;
        }
        try {
            this.visiblePropertiesJson = objectMapper.writeValueAsString(properties);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Failed to serialize visible properties for diagram node id=" + id, e);
        }
    }
}
