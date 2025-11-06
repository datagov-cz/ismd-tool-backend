package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OntologyMetadataRepository extends JpaRepository<OntologyMetadataEntity, Long> {

    Optional<OntologyMetadataEntity> findByGraphName(String graphName);

    Optional<OntologyMetadataEntity> findByGraphNameAndUserId(String graphName, String userId);

    List<OntologyMetadataEntity> findAllByUserId(String userId);

    List<OntologyMetadataEntity> findAllByIsPublished(Boolean isPublished);

    List<OntologyMetadataEntity> findAllByUserIdAndIsPublished(String userId, Boolean isPublished);

    Optional<OntologyMetadataEntity> findBySlug(String slug);

    List<OntologyMetadataEntity> findBySlugIn(List<String> slugs);
}
