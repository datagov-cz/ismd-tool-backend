package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConceptMetadataRepository extends JpaRepository<ConceptMetadataEntity, Long> {

    /**
     * Fetch a concept's metadata row with a {@code SELECT … FOR UPDATE} row lock.
     * <p>
     * Used on the outbox edit/delete path to serialize concurrent writes to the SAME concept:
     * the relay's per-aggregate ordering is correct only if two outbox rows for one aggregate are
     * never enqueued concurrently (a lower {@code seq} could otherwise commit after a higher one,
     * inverting their apply order — the relay's gate query is an unlocked SELECT and can't see a
     * concurrent drain's in-flight row). Locking this row at the top of an outbox-path edit/delete
     * makes a second concurrent edit of the same concept block until the first commits, so their
     * outbox rows are enqueued (and ordered) strictly one after the other. This converts the
     * "single-threaded edit-per-concept" assumption into a structural guarantee.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ConceptMetadataEntity c WHERE c.id = :id")
    Optional<ConceptMetadataEntity> findWithLockById(@Param("id") Long id);

    /**
     * Default (no-source) concept search. Any authenticated caller sees every
     * concept — published AND unpublished drafts of all users. Anonymous callers
     * never reach this method (forced to NKD at
     * {@code SearchServiceImpl.resolveSource}).
     */
    @Query(value = """
            SELECT * FROM ismd_schema.concepts c
            WHERE (ismd_schema.unaccent(c.concept_name) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
                   OR ismd_schema.unaccent(c.slug) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%')))
              AND (:hasGraphFilter = false OR c.graph_name IN (:graphNames))
              AND (:hasTypeFilter = false OR c.concept_type = :conceptType)
            """, nativeQuery = true)
    List<ConceptMetadataEntity> searchByText(@Param("query") String query,
                                              @Param("hasGraphFilter") boolean hasGraphFilter,
                                              @Param("graphNames") List<String> graphNames,
                                              @Param("hasTypeFilter") boolean hasTypeFilter,
                                              @Param("conceptType") String conceptType);

    /**
     * Variant of {@link #searchByText} that restricts to {@code is_published = false}.
     * <p>
     * Every authenticated caller sees every unpublished concept, regardless of
     * ownership. Anonymous callers are rejected upstream and never reach this method.
     */
    @Query(value = """
            SELECT * FROM ismd_schema.concepts c
            WHERE (ismd_schema.unaccent(c.concept_name) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
                   OR ismd_schema.unaccent(c.slug) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%')))
              AND c.is_published = false
              AND (:hasGraphFilter = false OR c.graph_name IN (:graphNames))
              AND (:hasTypeFilter = false OR c.concept_type = :conceptType)
            """, nativeQuery = true)
    List<ConceptMetadataEntity> searchByTextUnpublished(@Param("query") String query,
                                                         @Param("hasGraphFilter") boolean hasGraphFilter,
                                                         @Param("graphNames") List<String> graphNames,
                                                         @Param("hasTypeFilter") boolean hasTypeFilter,
                                                         @Param("conceptType") String conceptType);

    @Query(value = """
            SELECT COUNT(*) FROM ismd_schema.concepts c
            WHERE (ismd_schema.unaccent(c.concept_name) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
                   OR ismd_schema.unaccent(c.slug) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%')))
              AND (:hasGraphFilter = false OR c.graph_name IN (:graphNames))
              AND (:hasTypeFilter = false OR c.concept_type = :conceptType)
            """, nativeQuery = true)
    long countSearchByText(@Param("query") String query,
                           @Param("hasGraphFilter") boolean hasGraphFilter,
                           @Param("graphNames") List<String> graphNames,
                           @Param("hasTypeFilter") boolean hasTypeFilter,
                           @Param("conceptType") String conceptType);

    @Query(value = """
            SELECT COUNT(*) FROM ismd_schema.concepts c
            WHERE (ismd_schema.unaccent(c.concept_name) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
                   OR ismd_schema.unaccent(c.slug) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%')))
              AND c.is_published = false
              AND (:hasGraphFilter = false OR c.graph_name IN (:graphNames))
              AND (:hasTypeFilter = false OR c.concept_type = :conceptType)
            """, nativeQuery = true)
    long countSearchByTextUnpublished(@Param("query") String query,
                                      @Param("hasGraphFilter") boolean hasGraphFilter,
                                      @Param("graphNames") List<String> graphNames,
                                      @Param("hasTypeFilter") boolean hasTypeFilter,
                                      @Param("conceptType") String conceptType);

    /**
     * Concept counts grouped by graph_name, restricted to a fixed set of graphs.
     * Used by search to populate {@code conceptCount} on ontology results — one
     * batched query per page instead of N round-trips.
     * <p>
     * Returns {@code Object[]} pairs {@code [graphName, count]} so callers can
     * fold into a map. Graphs with zero matching concepts are simply absent
     * from the result.
     * <p>
     * Counts every concept in the given graphs — published and unpublished alike —
     * to stay consistent with the default search's all-drafts visibility. Only ever
     * called for graphs that already appeared as visible ontology hits.
     */
    @Query(value = """
            SELECT c.graph_name, COUNT(*) FROM ismd_schema.concepts c
            WHERE c.graph_name IN (:graphNames)
            GROUP BY c.graph_name
            """, nativeQuery = true)
    List<Object[]> countByGraphNameIn(@Param("graphNames") List<String> graphNames);

    Optional<ConceptMetadataEntity> findByConceptIri(String conceptIri);

    List<ConceptMetadataEntity> findByConceptIriIn(List<String> conceptIris);

    @EntityGraph(attributePaths = "ontologyMetadata")
    Optional<ConceptMetadataEntity> findBySlug(String slug);

    @EntityGraph(attributePaths = "ontologyMetadata")
    List<ConceptMetadataEntity> findByGraphName(String graphName);

    List<ConceptMetadataEntity> findByOntologyMetadataId(Long ontologyMetadataId);

    @EntityGraph(attributePaths = "ontologyMetadata")
    List<ConceptMetadataEntity> findAllByUserIdAndIsPublished(String userId, Boolean isPublished);

    @EntityGraph(attributePaths = "ontologyMetadata")
    List<ConceptMetadataEntity> findAllByUserId(String userId);

    @EntityGraph(attributePaths = "ontologyMetadata")
    List<ConceptMetadataEntity> findAllByIsPublished(Boolean isPublished);

    @Override
    @EntityGraph(attributePaths = "ontologyMetadata")
    List<ConceptMetadataEntity> findAll();
}
