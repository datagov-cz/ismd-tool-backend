package com.dia.ismdtoolbackend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "comments")
@NoArgsConstructor
@Getter
@Setter
public class CommentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String comment;
    private String userId;

    /**
     * The ontology this comment belongs to. Exactly one of {@code ontologyMetadata} /
     * {@code conceptMetadata} is set (enforced at post time). Linking by the metadata row's
     * stable id — rather than the reusable IRI string — is what stops a re-created same-slug
     * ontology from inheriting a deleted one's comments: the DB-level FK cascade purges them on
     * delete, and the id survives concept IRI regeneration on rename.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ontology_metadata_id")
    private OntologyMetadataEntity ontologyMetadata;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "concept_metadata_id")
    private ConceptMetadataEntity conceptMetadata;

    private LocalDateTime postedTime;
}
