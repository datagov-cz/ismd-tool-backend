package com.dia.ismdtoolbackend.entity;

import com.dia.ismdtoolbackend.enums.ConceptType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "concepts")
@NoArgsConstructor
@Getter
@Setter
public class ConceptMetadataEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    @Column(name = "validation_report_id")
    private Long validationReportId;

    @Column(name = "comments", columnDefinition = "text")
    private String commentsJson;
}
