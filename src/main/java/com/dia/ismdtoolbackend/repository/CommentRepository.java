package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.CommentEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommentRepository extends JpaRepository<CommentEntity, Long> {
    List<CommentEntity> findByOntologyMetadataId(Long ontologyMetadataId);
    List<CommentEntity> findByOntologyMetadataIdIn(List<Long> ontologyMetadataIds);

    /**
     * Join-fetches both owners: {@code ConceptMetadataMapper.commentEntityToModel} reads
     * {@code ontologyMetadata.graphName} and {@code conceptMetadata.conceptIri}, and the concept
     * detail read maps these after the fetching transaction has closed.
     */
    @EntityGraph(attributePaths = {"ontologyMetadata", "conceptMetadata"})
    List<CommentEntity> findByConceptMetadataId(Long conceptMetadataId);

    List<CommentEntity> findByConceptMetadataIdIn(List<Long> conceptMetadataIds);
}
