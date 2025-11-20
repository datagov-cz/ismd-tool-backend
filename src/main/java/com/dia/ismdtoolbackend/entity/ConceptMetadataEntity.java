package com.dia.ismdtoolbackend.entity;

import com.dia.ismdtoolbackend.enums.ConceptType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "concepts")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor
@Getter
@Setter
public class ConceptMetadataEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, name = "slug")
    private String slug;

    @Column(name = "concept_name")
    private String conceptName;

    @Column(name = "concept_type")
    @Enumerated(EnumType.STRING)
    private ConceptType conceptType;

    @Column(name = "graph_name")
    private String graphName;

    @Column(name = "concept_iri", unique = true)
    private String conceptIri;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "is_published")
    private Boolean isPublished;

    @Column(name = "in_tezaurus")
    private String inTezaurus;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
