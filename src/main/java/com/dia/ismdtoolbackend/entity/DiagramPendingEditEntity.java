package com.dia.ismdtoolbackend.entity;

import com.dia.ismdtoolbackend.models.diagram.DiagramJson;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * One staged, uncommitted structural edit to a real concept, applied to RDF by Převzít.
 *
 * <p>Scoped to the diagram and keyed by concept IRI, so two diagrams of one ontology may hold competing
 * edits on the same concept and the conflict is detectable. Independent of canvas membership: a staged edit
 * needs no node row and survives a node's removal.
 *
 * <p>{@code ontologyMetadata} is kept alongside {@code diagram} as the scope check and the sibling-conflict
 * join key. A row exists only while there is an edit; discarding deletes it. See
 * {@code docs/DIAGRAM_LAYER.md}.
 */
@Entity
@Table(name = "diagram_pending_edits")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor
@Getter
@Setter
@Slf4j
public class DiagramPendingEditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The canvas that staged this edit; deleting the diagram cascades its staged work away. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "diagram_id", nullable = false)
    private DiagramEntity diagram;

    /** The diagram's ontology, denormalized as the scope check and sibling-conflict join key. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ontology_metadata_id", nullable = false)
    private OntologyMetadataEntity ontologyMetadata;

    @Column(name = "concept_iri", length = 1024, nullable = false)
    private String conceptIri;

    @Column(name = "pending_edit_json", columnDefinition = "text", nullable = false)
    private String pendingEditJson;

    /** The target concept's {@code updatedAt} at stage time: the server-stamped STALE_BASE fingerprint. */
    @Column(name = "base_updated_at")
    private LocalDateTime baseUpdatedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** The overlay, or {@code null} on malformed JSON. */
    public DiagramPendingEdit getPendingEdit() {
        if (pendingEditJson == null || pendingEditJson.isBlank()) {
            return null;
        }
        try {
            return DiagramJson.MAPPER.readValue(pendingEditJson, DiagramPendingEdit.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize pending-edit JSON for row id={}, concept {}",
                    id, conceptIri, e);
            return null;
        }
    }

    /** Stores the overlay; null is rejected, since discarding deletes the row instead. */
    public void setPendingEdit(DiagramPendingEdit edit) {
        if (edit == null) {
            throw new IllegalArgumentException(
                    "A pending-edit row must carry an edit; discard deletes the row instead (concept "
                            + conceptIri + ")");
        }
        try {
            this.pendingEditJson = DiagramJson.MAPPER.writeValueAsString(edit);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Failed to serialize pending edit for concept " + conceptIri, e);
        }
    }
}