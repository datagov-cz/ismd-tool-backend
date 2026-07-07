package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.CommentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommentRepository extends JpaRepository<CommentEntity, Long> {
    List<CommentEntity> findByOntologyMetadataId(Long ontologyMetadataId);
    List<CommentEntity> findByConceptMetadataId(Long conceptMetadataId);
}
