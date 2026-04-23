package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OntologyMetadataRepository extends JpaRepository<OntologyMetadataEntity, Long> {

    @Query(value = """
            SELECT * FROM ismd_schema.ontologies o
            WHERE ismd_schema.unaccent(o.slug) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
              AND (o.is_published = true OR o.user_id = :userId)
            """, nativeQuery = true)
    List<OntologyMetadataEntity> searchByText(@Param("query") String query,
                                               @Param("userId") String userId);

    /**
     * Variant of {@link #searchByText} that restricts to {@code is_published = false}.
     * <p>
     * Visibility: when {@code isAdmin = true} every unpublished ontology is visible;
     * otherwise only ontologies owned by {@code userId}. Anonymous callers have no
     * rows they can see and should never reach this method.
     */
    @Query(value = """
            SELECT * FROM ismd_schema.ontologies o
            WHERE ismd_schema.unaccent(o.slug) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
              AND o.is_published = false
              AND (:isAdmin = true OR o.user_id = :userId)
            """, nativeQuery = true)
    List<OntologyMetadataEntity> searchByTextUnpublished(@Param("query") String query,
                                                          @Param("userId") String userId,
                                                          @Param("isAdmin") boolean isAdmin);

    /**
     * All unpublished ontologies visible to the caller — admin sees everything,
     * regular user sees only their own. Used by search to scope Fuseki graph
     * enumeration when {@code source=UNPUBLISHED}.
     */
    @Query(value = """
            SELECT * FROM ismd_schema.ontologies o
            WHERE o.is_published = false
              AND (:isAdmin = true OR o.user_id = :userId)
            """, nativeQuery = true)
    List<OntologyMetadataEntity> findVisibleUnpublished(@Param("userId") String userId,
                                                         @Param("isAdmin") boolean isAdmin);

    Optional<OntologyMetadataEntity> findByGraphName(String graphName);

    Optional<OntologyMetadataEntity> findByGraphNameAndUserId(String graphName, String userId);

    List<OntologyMetadataEntity> findAllByUserId(String userId);

    List<OntologyMetadataEntity> findAllByIsPublished(Boolean isPublished);

    List<OntologyMetadataEntity> findAllByUserIdAndIsPublished(String userId, Boolean isPublished);

    Optional<OntologyMetadataEntity> findBySlug(String slug);

    List<OntologyMetadataEntity> findBySlugIn(List<String> slugs);
}
