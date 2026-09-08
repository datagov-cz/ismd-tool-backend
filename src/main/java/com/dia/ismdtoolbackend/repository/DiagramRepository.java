package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.DiagramEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DiagramRepository extends JpaRepository<DiagramEntity, Long> {

    /** Every diagram of an ontology, oldest first. */
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
     * lazy-load {@code nodes}, and for the all-diagrams list {@code ontologyMetadata}, once per row.
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
     * A diagram, resolved only if it belongs to the given ontology. The endpoints authorize the slug and
     * leave the diagram id unconstrained, so filtering on both columns is the guard against reaching
     * another ontology's diagram through your own slug.
     */
    Optional<DiagramEntity> findByIdAndOntologyMetadataId(Long id, Long ontologyMetadataId);

    /** One diagram that draws a concept, and the shape it draws it as. */
    interface ConceptUsageRow {
        Long getDiagramId();
        String getDiagramName();
        String getOntologySlug();
        /** {@code NODE}, {@code EDGE} or {@code PROPERTY_ROW}; see {@code DiagramConceptUsageKind}. */
        String getKind();
        /** For {@code PROPERTY_ROW}, the class whose cell renders the row; null otherwise. */
        String getHostClassIri();
    }

    /**
     * Every diagram whose canvas draws this concept, in one round trip. Native because membership is
     * recorded in three places depending on the concept's type, one of them a JSON containment test JPQL
     * cannot express:
     *
     * <ul>
     *   <li><b>TŘÍDA</b> — a {@code diagram_nodes} row, served by {@code idx_diagram_nodes_concept_iri}.</li>
     *   <li><b>VZTAH</b> — a {@code diagram_edges} row whose {@code edge_key} is the VZTAH's own IRI. The
     *       composite {@code edge|KIND|src|tgt} keys of hierarchy edges never equal a bare IRI, so equality
     *       alone distinguishes them.</li>
     *   <li><b>VLASTNOST</b> — an entry in some node's {@code visible_properties_json}, reported with that
     *       node's class as {@code hostClassIri}.</li>
     * </ul>
     *
     * <p>The {@code LIKE '[%'} test is the partial index's predicate, not a redundant check: omitting it
     * stops the planner matching {@code idx_diagram_nodes_visible_properties}. It also guards the cast,
     * which throws on a malformed row rather than returning false.
     *
     * <p>A concept may be drawn on one diagram in two ways, each its own row, which is why the caller keys
     * placements by {@code (diagramId, kind)} rather than by diagram alone.
     */
    @Query(nativeQuery = true, value = """
            select n.diagram_id   as diagramId,
                   d.name         as diagramName,
                   o.slug         as ontologySlug,
                   'NODE'         as kind,
                   cast(null as varchar) as hostClassIri
            from ismd_schema.diagram_nodes n
            join ismd_schema.diagrams d on d.id = n.diagram_id
            join ismd_schema.ontologies o on o.id = d.ontology_metadata_id
            where n.concept_iri = :conceptIri
            union all
            select e.diagram_id, d.name, o.slug, 'EDGE', cast(null as varchar)
            from ismd_schema.diagram_edges e
            join ismd_schema.diagrams d on d.id = e.diagram_id
            join ismd_schema.ontologies o on o.id = d.ontology_metadata_id
            where e.edge_key = :conceptIri
            union all
            select n.diagram_id, d.name, o.slug, 'PROPERTY_ROW', n.concept_iri
            from ismd_schema.diagram_nodes n
            join ismd_schema.diagrams d on d.id = n.diagram_id
            join ismd_schema.ontologies o on o.id = d.ontology_metadata_id
            where n.visible_properties_json like '[%'
              and n.visible_properties_json::jsonb @> cast(:conceptIriJson as jsonb)
            order by diagramId asc
            """)
    List<ConceptUsageRow> findConceptUsage(@Param("conceptIri") String conceptIri,
                                           @Param("conceptIriJson") String conceptIriJson);

    /** Whether an ontology has any diagram at all. */
    boolean existsByOntologyMetadataId(Long ontologyMetadataId);

    /** Names identify a canvas to the user, so they are unique within one ontology. */
    boolean existsByOntologyMetadataIdAndName(Long ontologyMetadataId, String name);

    /** The same check for a rename, excluding the diagram being renamed so it cannot collide with itself. */
    boolean existsByOntologyMetadataIdAndNameAndIdNot(Long ontologyMetadataId, String name, Long id);

    /**
     * Diagrams whose ontology slug or own name matches the query, accent-insensitively. Backs
     * {@code type=DIAGRAM} results in ISMD search, one row per diagram.
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
     * As {@link #searchByOntologyText}, narrowed to diagrams of unpublished ontologies. A diagram mirrors
     * its ontology's publish state, so an UNPUBLISHED search filters on the join.
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