package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OntologyMetadataRepository extends JpaRepository<OntologyMetadataEntity, Long> {

    /**
     * Default (no-source) ontology search. Any authenticated caller sees every
     * ontology — published AND unpublished drafts of all users. Anonymous callers
     * never reach this method (they are forced to NKD at
     * {@code SearchServiceImpl.resolveSource}).
     */
    @Query(value = """
            SELECT * FROM ismd_schema.ontologies o
            WHERE ismd_schema.unaccent(o.slug) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
            """, nativeQuery = true)
    List<OntologyMetadataEntity> searchByText(@Param("query") String query);

    /**
     * Variant of {@link #searchByText} that restricts to {@code is_published = false}.
     * <p>
     * Every authenticated caller sees every unpublished ontology, regardless of
     * ownership. Anonymous callers are rejected upstream and never reach this method.
     */
    @Query(value = """
            SELECT * FROM ismd_schema.ontologies o
            WHERE ismd_schema.unaccent(o.slug) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
              AND o.is_published = false
            """, nativeQuery = true)
    List<OntologyMetadataEntity> searchByTextUnpublished(@Param("query") String query);

    @Query(value = """
            SELECT COUNT(*) FROM ismd_schema.ontologies o
            WHERE ismd_schema.unaccent(o.slug) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
            """, nativeQuery = true)
    long countSearchByText(@Param("query") String query);

    @Query(value = """
            SELECT COUNT(*) FROM ismd_schema.ontologies o
            WHERE ismd_schema.unaccent(o.slug) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
              AND o.is_published = false
            """, nativeQuery = true)
    long countSearchByTextUnpublished(@Param("query") String query);

    Optional<OntologyMetadataEntity> findByGraphName(String graphName);

    Optional<OntologyMetadataEntity> findByGraphNameAndUserId(String graphName, String userId);

    List<OntologyMetadataEntity> findAllByUserId(String userId);

    List<OntologyMetadataEntity> findAllByIsPublished(Boolean isPublished);

    List<OntologyMetadataEntity> findAllByUserIdAndIsPublished(String userId, Boolean isPublished);

    Optional<OntologyMetadataEntity> findBySlug(String slug);

    List<OntologyMetadataEntity> findBySlugIn(List<String> slugs);
}
