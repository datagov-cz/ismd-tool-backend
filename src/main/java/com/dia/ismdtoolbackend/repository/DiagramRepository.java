package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.DiagramEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DiagramRepository extends JpaRepository<DiagramEntity, Long> {

    /** Every diagram of an ontology, oldest first — the diagram picker. */
    List<DiagramEntity> findByOntologyMetadataIdOrderByIdAsc(Long ontologyMetadataId);

    /**
     * A diagram, resolved only if it belongs to the given ontology.
     *
     * <p>The write endpoints authorize the ontology <em>slug</em>; the diagram id in the path is
     * unconstrained by that check, so an owner of any ontology could otherwise reach another
     * ontology's diagram through their own slug. Filtering on both columns in ONE query is the guard —
     * a load-by-id followed by a comparison would leak existence through timing and through any
     * exception thrown before the compare.
     */
    Optional<DiagramEntity> findByIdAndOntologyMetadataId(Long id, Long ontologyMetadataId);

    /** Whether an ontology has any diagram at all. */
    boolean existsByOntologyMetadataId(Long ontologyMetadataId);

    /** Names identify a canvas to the user, so they are unique within an ontology. */
    boolean existsByOntologyMetadataIdAndName(Long ontologyMetadataId, String name);

    /**
     * Diagrams whose ontology slug matches the query (accent-insensitive), mirroring the ontology
     * text search. Backs {@code type=DIAGRAM} results in ISMD search.
     *
     * <p><strong>One row per diagram.</strong> Each canvas is a distinct destination with its own name,
     * so all of an ontology's diagrams are returned; the result rows carry the diagram id and name that
     * tell them apart.
     */
    @Query(value = """
            SELECT d.* FROM ismd_schema.diagrams d
            JOIN ismd_schema.ontologies o ON o.id = d.ontology_metadata_id
            WHERE ismd_schema.unaccent(o.slug) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
               OR ismd_schema.unaccent(d.name) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
            ORDER BY d.ontology_metadata_id, d.id
            """, nativeQuery = true)
    List<DiagramEntity> searchByOntologyText(@Param("query") String query);

    /**
     * As {@link #searchByOntologyText}, narrowed to diagrams of unpublished ontologies. A diagram has no
     * publish state of its own — it mirrors its ontology's, so an UNPUBLISHED search filters on the join.
     */
    @Query(value = """
            SELECT d.* FROM ismd_schema.diagrams d
            JOIN ismd_schema.ontologies o ON o.id = d.ontology_metadata_id
            WHERE (ismd_schema.unaccent(o.slug) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
                OR ismd_schema.unaccent(d.name) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%')))
              AND o.is_published = false
            ORDER BY d.ontology_metadata_id, d.id
            """, nativeQuery = true)
    List<DiagramEntity> searchByOntologyTextUnpublished(@Param("query") String query);

    @Query(value = """
            SELECT COUNT(*) FROM ismd_schema.diagrams d
            JOIN ismd_schema.ontologies o ON o.id = d.ontology_metadata_id
            WHERE ismd_schema.unaccent(o.slug) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
               OR ismd_schema.unaccent(d.name) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
            """, nativeQuery = true)
    long countSearchByOntologyText(@Param("query") String query);

    @Query(value = """
            SELECT COUNT(*) FROM ismd_schema.diagrams d
            JOIN ismd_schema.ontologies o ON o.id = d.ontology_metadata_id
            WHERE (ismd_schema.unaccent(o.slug) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
                OR ismd_schema.unaccent(d.name) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%')))
              AND o.is_published = false
            """, nativeQuery = true)
    long countSearchByOntologyTextUnpublished(@Param("query") String query);
}