package com.dia.ismdtoolbackend.rdfmigration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** One applied RDF changeset (see {@link RdfMigrationRunner}). */
@Entity
@Table(name = "rdf_migration_log")
@NoArgsConstructor
@Getter
@Setter
public class RdfMigrationEntity {

    @Id
    @Column(name = "id", nullable = false, length = 255)
    private String id;

    @Column(name = "checksum", nullable = false, length = 64)
    private String checksum;

    @Column(name = "description", length = 1024)
    private String description;

    @Column(name = "applied_at", nullable = false)
    private LocalDateTime appliedAt;

    @Column(name = "graphs_processed", nullable = false)
    private int graphsProcessed;

    @Column(name = "execution_ms", nullable = false)
    private long executionMs;
}
