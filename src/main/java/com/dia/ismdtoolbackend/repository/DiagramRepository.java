package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.DiagramEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DiagramRepository extends JpaRepository<DiagramEntity, Long> {

    /** The canonical diagram for an ontology, if one has been created (lazy-provisioned on first open). */
    Optional<DiagramEntity> findByOntologyMetadataId(Long ontologyMetadataId);

    /** Resolve a diagram from the ontology's FE-facing slug (the read/save endpoints are slug-addressed). */
    Optional<DiagramEntity> findByOntologyMetadataSlug(String ontologySlug);

    /** Whether an ontology already has its canonical diagram (guards the lazy-create race). */
    boolean existsByOntologyMetadataId(Long ontologyMetadataId);
}