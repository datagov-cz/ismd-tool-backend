package com.dia.ismdtoolbackend.entity;

import com.dia.ismdtoolbackend.entity.dto.CommentDto;
import com.dia.ismdtoolbackend.enums.OntologyLevel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

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

    @Column(name = "validation_report_id")
    private Long validationReportId;

    @Column(name = "ontology_level")
    @Enumerated(EnumType.STRING)
    private OntologyLevel ontologyLevel;

    @Column(name = "comments", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<CommentDto> comments;

}
