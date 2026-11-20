package com.dia.ismdtoolbackend.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One user delete. Rows are only inserted; {@link DeleteAuditRepository} has no update or delete. */
@Entity
@Table(name = "delete_audit")
@NoArgsConstructor
@Getter
@Setter
public class DeleteAuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deleted_at", nullable = false, columnDefinition = "timestamptz")
    private Instant deletedAt;

    /** Keycloak {@code sub} of the user who deleted. */
    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column(name = "actor_admin", nullable = false)
    private boolean actorAdmin;

    @Column(name = "entity_type", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private DeletedEntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "entity_iri", length = 1024)
    private String entityIri;

    @Column(name = "entity_slug")
    private String entitySlug;

    @Column(name = "entity_name", length = 1024)
    private String entityName;

    @Column(name = "graph_name", length = 1024)
    private String graphName;

    /** Concept owning a deleted local copy, or concept a deleted comment was on. */
    @Column(name = "parent_iri", length = 1024)
    private String parentIri;

    /** JSON of what went with the delete: cascaded concept IRIs, or child counts of an ontology. */
    @Column(name = "details", columnDefinition = "text")
    private String details;
}
