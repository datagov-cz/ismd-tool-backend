package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConceptMetadataRepository extends JpaRepository<ConceptMetadataEntity, Long> {

    Optional<ConceptMetadataEntity> findByConceptIri(String conceptIri);

    Optional<ConceptMetadataEntity> findBySlug(String slug);

    List<ConceptMetadataEntity> findByGraphName(String graphName);

    List<ConceptMetadataEntity> findAllByUserIdAndIsPublished(String userId, Boolean isPublished);

    List<ConceptMetadataEntity> findAllByUserId(String userId);

    List<ConceptMetadataEntity> findAllByIsPublished(Boolean isPublished);
}
