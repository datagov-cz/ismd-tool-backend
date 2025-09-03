package com.dia.ismdtoolbackend.entity;

import com.dia.ismdtoolbackend.enums.OntologyLevel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "ontologies")
@NoArgsConstructor
@Getter
@Setter
public class OntologyMetadataEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "graph_name")
    private String graphName;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "is_published")
    private Boolean isPublished;

    @OneToOne(mappedBy = "ontologyMetadata", cascade = CascadeType.ALL)
    private ValidationReportEntity validationReport;

    @Column(name = "ontology_level")
    @Enumerated(EnumType.STRING)
    private OntologyLevel ontologyLevel;

    @Column(name = "comments", columnDefinition = "text")
    private String commentsJson;
}
