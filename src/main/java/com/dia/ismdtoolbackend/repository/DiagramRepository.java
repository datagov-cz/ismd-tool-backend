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
     *
     * <p>Both filters are null-means-unfiltered. {@code userId} matches the OWNING ONTOLOGY's user: a
     * diagram carries no owner of its own, so "my diagrams" means the diagrams of my slovníky.
     */
    @Query("""
            select d.id as diagramId, d.name as name, o.slug as slug, o.graphName as graphName,
                   d.updatedAt as updatedAt, count(n.id) as nodeCount
            from DiagramEntity d
            join d.ontologyMetadata o
            left join d.nodes n
            where (:ontologyMetadataId is null or o.id = :ontologyMetadataId)
              and (:userId is null or o.userId = :userId)
            group by d.id, d.name, o.slug, o.graphName, d.updatedAt
            order by d.id asc
            """)
    List<DiagramSummaryRow> findSummaries(@Param("ontologyMetadataId") Long ontologyMetadataId,
                                          @Param("userId") String userId);

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
     * <p>Two predicates are load-bearing for index matching, neither redundant:
     *
     * <ul>
     *   <li>{@code md5(e.edge_key) = md5(...)} repeats the expression {@code idx_diagram_edges_edge_key} is
     *       built on. Postgres matches an expression index only when the query spells out that same
     *       expression — it does not rewrite {@code col = $1} into {@code md5(col) = md5($1)} — so without
     *       this the index is dead weight and the branch seq-scans {@code diagram_edges}. The raw equality
     *       stays alongside it as the recheck that makes an md5 collision harmless.</li>
     *   <li>{@code LIKE '[%'} is {@code idx_diagram_nodes_visible_properties}' partial-index predicate;
     *       omitting it stops the planner matching that index. It also guards the cast, which throws on a
     *       malformed row rather than returning false.</li>
     * </ul>
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
            where md5(e.edge_key) = md5(cast(:conceptIri as text))
              and e.edge_key = :conceptIri
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

    /** Names identify a canvas to the user, so they are unique within one ontology. */
    boolean existsByOntologyMetadataIdAndName(Long ontologyMetadataId, String name);

    /** The same check for a rename, excluding the diagram being renamed so it cannot collide with itself. */
    boolean existsByOntologyMetadataIdAndNameAndIdNot(Long ontologyMetadataId, String name, Long id);

    /** One search hit: everything {@code DiagramSearchLookup} maps, so no lazy association is touched. */
    interface DiagramSearchRow {
        Long getDiagramId();
        String getName();
        String getSlug();
        String getGraphName();
        Boolean getIsPublished();
        LocalDateTime getUpdatedAt();
    }

    /**
     * Diagrams whose ontology slug or own name matches the query, accent-insensitively. Backs
     * {@code type=DIAGRAM} results in ISMD search, one row per diagram.
     *
     * <p>Paginated in SQL rather than in Java: the caller shows one page, and fetching every match to slice
     * it in memory scales with the corpus instead of the page. A projection rather than {@code SELECT d.*}
     * because {@code ontologyMetadata} is LAZY, so entities would emit one extra SELECT per row.
     *
     * <p>{@code unpublishedOnly} folds in what used to be a second, near-identical query. A diagram mirrors
     * its ontology's publish state, so an UNPUBLISHED search filters on the join.
     *
     * <p>{@code ORDER BY} is total ({@code d.id} is unique), which is what makes LIMIT/OFFSET stable —
     * an unordered paginated search silently hides rows (see {@code search_unordered_pagination_hid_drafts}).
     */
    @Query(value = """
            SELECT d.id AS diagramId, d.name AS name, o.slug AS slug, o.graph_name AS graphName,
                   o.is_published AS isPublished, d.updated_at AS updatedAt
            FROM ismd_schema.diagrams d
            JOIN ismd_schema.ontologies o ON o.id = d.ontology_metadata_id
            WHERE (ismd_schema.unaccent(o.slug) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
                OR ismd_schema.unaccent(d.name) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%')))
              AND (:unpublishedOnly = false OR o.is_published = false)
            ORDER BY d.ontology_metadata_id, d.id
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<DiagramSearchRow> searchByOntologyText(@Param("query") String query,
                                                @Param("unpublishedOnly") boolean unpublishedOnly,
                                                @Param("limit") int limit,
                                                @Param("offset") int offset);

    /** Total matches for {@link #searchByOntologyText}, for the paging header. */
    @Query(value = """
            SELECT COUNT(*) FROM ismd_schema.diagrams d
            JOIN ismd_schema.ontologies o ON o.id = d.ontology_metadata_id
            WHERE (ismd_schema.unaccent(o.slug) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
                OR ismd_schema.unaccent(d.name) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%')))
              AND (:unpublishedOnly = false OR o.is_published = false)
            """, nativeQuery = true)
    long countSearchByOntologyText(@Param("query") String query,
                                   @Param("unpublishedOnly") boolean unpublishedOnly);
}