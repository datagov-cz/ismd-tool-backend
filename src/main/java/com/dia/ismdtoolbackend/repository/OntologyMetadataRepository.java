package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OntologyMetadataRepository extends JpaRepository<OntologyMetadataEntity, Long> {

    /**
     * Fetch an ontology's metadata row with a {@code SELECT … FOR UPDATE} row lock.
     * <p>
     * Used by the {@code updatedAt} touch path, where several concepts of one ontology contend for
     * the same parent row. Callers that lock both rows take the concept lock first
     * ({@link ConceptMetadataRepository#findWithLockById}) and this one second; that concept →
     * ontology order is the project-wide convention and is what keeps two concurrent edits of
     * different concepts in the same ontology from deadlocking.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM OntologyMetadataEntity o WHERE o.id = :id")
    Optional<OntologyMetadataEntity> findWithLockById(@Param("id") Long id);

    /**
     * Default (no-source) ontology search. Any authenticated caller sees every
     * ontology — published AND unpublished drafts of all users. Anonymous callers
     * never reach this method (they are forced to NKD at
     * {@code SearchServiceImpl.resolveSource}).
     */
    @Query(value = """
            SELECT * FROM ismd_schema.ontologies o
            WHERE ismd_schema.unaccent(o.slug) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
            ORDER BY o.is_published NULLS FIRST, o.updated_at DESC NULLS LAST, o.id
            """, nativeQuery = true)
    List<OntologyMetadataEntity> searchByText(@Param("query") String query);

    /**
     * Variant of {@link #searchByText} backing {@code source=UNPUBLISHED}
     * ("rozpracovaný") — every local ISMD ontology, with no publish-state restriction.
     * <p>
     * "Rozpracovaný" means local, not draft-flagged. NKD is the published world and
     * ISMD is the workbench, so anything held locally is material the user is working
     * on. The upload path sets {@code is_published = true} on any ontology whose own
     * IRI resolves in NKD, and on each of its concepts, so a working copy reads as
     * fully published until someone edits a concept — filtering on the flag hid whole
     * uploaded vocabularies. Draft state is surfaced through ordering (drafts first)
     * rather than by excluding rows.
     * <p>
     * Anonymous callers are rejected upstream and never reach this method.
     */
    @Query(value = """
            SELECT * FROM ismd_schema.ontologies o
            WHERE ismd_schema.unaccent(o.slug) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
            ORDER BY o.is_published NULLS FIRST, o.updated_at DESC NULLS LAST, o.id
            """, nativeQuery = true)
    List<OntologyMetadataEntity> searchByTextUnpublished(@Param("query") String query);

    /**
     * Graph names of every local ontology — the Fuseki-side scope for
     * {@code source=UNPUBLISHED}. Mirrors {@link #searchByTextUnpublished} so the
     * Fuseki label search and the PG slug search agree on what "unpublished" means.
     */
    @Query(value = """
            SELECT o.graph_name FROM ismd_schema.ontologies o
            """, nativeQuery = true)
    List<String> findAllGraphNames();

    @Query(value = """
            SELECT COUNT(*) FROM ismd_schema.ontologies o
            WHERE ismd_schema.unaccent(o.slug) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
            """, nativeQuery = true)
    long countSearchByText(@Param("query") String query);

    /**
     * Count companion to {@link #searchByTextUnpublished}. Carries the identical
     * predicate so the reported total and the returned rows agree.
     */
    @Query(value = """
            SELECT COUNT(*) FROM ismd_schema.ontologies o
            WHERE ismd_schema.unaccent(o.slug) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
            """, nativeQuery = true)
    long countSearchByTextUnpublished(@Param("query") String query);

    Optional<OntologyMetadataEntity> findByGraphName(String graphName);

    List<OntologyMetadataEntity> findAllByGraphNameIn(List<String> graphNames);

    List<OntologyMetadataEntity> findAllByUserId(String userId);

    List<OntologyMetadataEntity> findAllByIsPublished(Boolean isPublished);

    List<OntologyMetadataEntity> findAllByUserIdAndIsPublished(String userId, Boolean isPublished);

    Optional<OntologyMetadataEntity> findBySlug(String slug);

    List<OntologyMetadataEntity> findBySlugIn(List<String> slugs);
}
