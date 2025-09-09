package com.dia.ismdtoolbackend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "ontologies")
@NoArgsConstructor
@Getter
@Setter
public class OntologyMetadata {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "graph_name")
    private String graphName;

    @Column(name = "user_id")
    private String userId;
}
