package com.dia.ismdtoolbackend.entity;

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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * One staged, uncommitted structural edit to a real concept, applied to RDF by Převzít.
 *
 * <p>Scoped to the DIAGRAM and keyed by concept IRI. Each canvas stages independently, so two diagrams
 * of one ontology may hold competing edits on the same concept — that is what makes a cross-diagram
 * conflict a detectable state instead of a shared row one save silently overwrites. It is still
 * independent of canvas <em>membership</em>: a staged edit needs no node row and survives a node's
 * removal.
 *
 * <p>{@code ontologyMetadata} is kept alongside {@code diagram} deliberately — it is the cheap scope
 * check and the join key for the conflict query, which looks for one concept staged across SIBLING
 * diagrams of the same ontology. A row exists only while there is an edit: discarding deletes it.
 * See {@code docs/DIAGRAM_LAYER.md}.
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

    /** The canvas that staged this edit. Deleting the diagram discards its staged work (DB cascade). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "diagram_id", nullable = false)
    private DiagramEntity diagram;

    /** The diagram's ontology, denormalized: the scope check and the sibling-conflict join key. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ontology_metadata_id", nullable = false)
    private OntologyMetadataEntity ontologyMetadata;

    @Column(name = "concept_iri", length = 1024, nullable = false)
    private String conceptIri;

    @Column(name = "pending_edit_json", columnDefinition = "text", nullable = false)
    private String pendingEditJson;

    /** The target concept's {@code updatedAt} at stage time; the STALE_BASE fingerprint. Server-stamped. */
    @Column(name = "base_updated_at")
    private LocalDateTime baseUpdatedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Deserialize the overlay; {@code null} on malformed JSON (logged). */
    public DiagramPendingEdit getPendingEdit() {
        if (pendingEditJson == null || pendingEditJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(pendingEditJson, DiagramPendingEdit.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize pending-edit JSON for row id={}, concept {}",
                    id, conceptIri, e);
            return null;
        }
    }

    /** Serialize and store the overlay. Rejects null — discarding deletes the row instead. */
    public void setPendingEdit(DiagramPendingEdit edit) {
        if (edit == null) {
            throw new IllegalArgumentException(
                    "A pending-edit row must carry an edit; discard deletes the row instead (concept "
                            + conceptIri + ")");
        }
        try {
            this.pendingEditJson = objectMapper.writeValueAsString(edit);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Failed to serialize pending edit for concept " + conceptIri, e);
        }
    }
}