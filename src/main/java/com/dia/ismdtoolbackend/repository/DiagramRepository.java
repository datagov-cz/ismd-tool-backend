package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.DiagramEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DiagramRepository extends JpaRepository<DiagramEntity, Long> {

    /** Every diagram of an ontology, oldest first — the diagram picker. */
    List<DiagramEntity> findByOntologyMetadataIdOrderByIdAsc(Long ontologyMetadataId);

    /** One list row per diagram: identity, its ontology, and the node count summed in SQL. */
    interface DiagramSummaryRow {
        Long getDiagramId();
        String getName();
        String getSlug();
        String getGraphName();
        long getNodeCount();
        LocalDateTime getUpdatedAt();
    }

    /**
     * Diagram list rows, joined to the ontology and counting nodes in one query. The entity path would
     * lazy-load {@code nodes} (and, for the all-diagrams list, {@code ontologyMetadata}) once per row.
     */
    @Query("""
            select d.id as diagramId, d.name as name, o.slug as slug, o.graphName as graphName,
                   d.updatedAt as updatedAt, count(n.id) as nodeCount
            from DiagramEntity d
            join d.ontologyMetadata o
            left join d.nodes n
            where (:ontologyMetadataId is null or o.id = :ontologyMetadataId)
            group by d.id, d.name, o.slug, o.graphName, d.updatedAt
            order by d.id asc
            """)
    List<DiagramSummaryRow> findSummaries(@Param("ontologyMetadataId") Long ontologyMetadataId);

    /**
     * A diagram, resolved only if it belongs to the given ontology. The endpoints authorize the ontology
     * <em>slug</em> and leave the diagram id unconstrained, so filtering on both columns in ONE query is
     * the guard against reaching another ontology's diagram through your own slug.
     */
    Optional<DiagramEntity> findByIdAndOntologyMetadataId(Long id, Long ontologyMetadataId);

    /** Whether an ontology has any diagram at all. */
    boolean existsByOntologyMetadataId(Long ontologyMetadataId);

    /** Names identify a canvas to the user, so they are unique within an ontology. */
    boolean existsByOntologyMetadataIdAndName(Long ontologyMetadataId, String name);

    /**
     * The same check for a rename, excluding the diagram being renamed — otherwise a diagram collides
     * with its own current name and no rename could ever be saved.
     */
    boolean existsByOntologyMetadataIdAndNameAndIdNot(Long ontologyMetadataId, String name, Long id);

    /**
     * Diagrams whose ontology slug or own name matches the query (accent-insensitive). Backs
     * {@code type=DIAGRAM} results in ISMD search, one row per diagram — each canvas is its own
     * destination.
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