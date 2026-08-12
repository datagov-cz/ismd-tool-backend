package com.dia.ismdtoolbackend.entity;

import com.dia.ismdtoolbackend.enums.DiagramNodeBacking;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * One node on a diagram canvas — always references a materialized ISMD concept ({@link #conceptIri}), and
 * may carry a staged structural overlay ({@link #pendingEditJson}) that coexists with the IRI.
 * See {@code docs/DIAGRAM_LAYER.md}.
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

    /** Serialized {@link DiagramPendingEdit} overlay; null when the node has no staged edits. */
    @Column(name = "pending_edit_json", columnDefinition = "text")
    private String pendingEditJson;

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

    /** Deserialize the overlay; {@code null} on absent/malformed JSON (logged). */
    public DiagramPendingEdit getPendingEdit() {
        if (pendingEditJson == null || pendingEditJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(pendingEditJson, DiagramPendingEdit.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize pending-edit JSON for diagram node id={}", id, e);
            return null;
        }
    }

    /** Serialize and store the overlay; a null value clears the column. */
    public void setPendingEdit(DiagramPendingEdit edit) {
        if (edit == null) {
            this.pendingEditJson = null;
            return;
        }
        try {
            this.pendingEditJson = objectMapper.writeValueAsString(edit);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Failed to serialize pending edit for diagram node id=" + id, e);
        }
    }
}
