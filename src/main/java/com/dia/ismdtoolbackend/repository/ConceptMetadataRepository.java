package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConceptMetadataRepository extends JpaRepository<ConceptMetadataEntity, Long> {

    @Query(value = """
            SELECT * FROM ismd_schema.concepts c
            WHERE (ismd_schema.unaccent(c.concept_name) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
                   OR ismd_schema.unaccent(c.slug) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%')))
              AND (c.is_published = true OR c.user_id = :userId)
              AND (:hasGraphFilter = false OR c.graph_name IN (:graphNames))
            """, nativeQuery = true)
    List<ConceptMetadataEntity> searchByText(@Param("query") String query,
                                              @Param("userId") String userId,
                                              @Param("hasGraphFilter") boolean hasGraphFilter,
                                              @Param("graphNames") List<String> graphNames);

    /**
     * Variant of {@link #searchByText} that restricts to {@code is_published = false}.
     * <p>
     * Visibility: when {@code isAdmin = true} every unpublished concept is visible;
     * otherwise only concepts owned by {@code userId}. Anonymous callers have no
     * rows they can see and should never reach this method.
     */
    @Query(value = """
            SELECT * FROM ismd_schema.concepts c
            WHERE (ismd_schema.unaccent(c.concept_name) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
                   OR ismd_schema.unaccent(c.slug) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%')))
              AND c.is_published = false
              AND (:isAdmin = true OR c.user_id = :userId)
              AND (:hasGraphFilter = false OR c.graph_name IN (:graphNames))
            """, nativeQuery = true)
    List<ConceptMetadataEntity> searchByTextUnpublished(@Param("query") String query,
                                                         @Param("userId") String userId,
                                                         @Param("isAdmin") boolean isAdmin,
                                                         @Param("hasGraphFilter") boolean hasGraphFilter,
                                                         @Param("graphNames") List<String> graphNames);

    Optional<ConceptMetadataEntity> findByConceptIri(String conceptIri);

    Optional<ConceptMetadataEntity> findBySlug(String slug);

    List<ConceptMetadataEntity> findByGraphName(String graphName);

    List<ConceptMetadataEntity> findByOntologyMetadataId(Long ontologyMetadataId);

    List<ConceptMetadataEntity> findAllByUserIdAndIsPublished(String userId, Boolean isPublished);

    List<ConceptMetadataEntity> findAllByUserId(String userId);

    List<ConceptMetadataEntity> findAllByIsPublished(Boolean isPublished);
}
