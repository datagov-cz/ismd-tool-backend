package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.DiagramEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DiagramRepository extends JpaRepository<DiagramEntity, Long> {

    /** The canonical diagram for an ontology, if one has been created (lazy-provisioned on first open). */
    Optional<DiagramEntity> findByOntologyMetadataId(Long ontologyMetadataId);

    /** Resolve a diagram from the ontology's FE-facing slug (the read/save endpoints are slug-addressed). */
    Optional<DiagramEntity> findByOntologyMetadataSlug(String ontologySlug);

    /** Whether an ontology already has its canonical diagram (guards the lazy-create race). */
    boolean existsByOntologyMetadataId(Long ontologyMetadataId);

    /**
     * Diagrams whose ontology slug matches the query (accent-insensitive), mirroring the ontology
     * text search. Backs {@code type=DIAGRAM} results in ISMD search.
     */
    @Query(value = """
            SELECT d.* FROM ismd_schema.diagrams d
            JOIN ismd_schema.ontologies o ON o.id = d.ontology_metadata_id
            WHERE ismd_schema.unaccent(o.slug) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
            """, nativeQuery = true)
    List<DiagramEntity> searchByOntologyText(@Param("query") String query);

    @Query(value = """
            SELECT COUNT(*) FROM ismd_schema.diagrams d
            JOIN ismd_schema.ontologies o ON o.id = d.ontology_metadata_id
            WHERE ismd_schema.unaccent(o.slug) ILIKE ismd_schema.unaccent(CONCAT('%', :query, '%'))
            """, nativeQuery = true)
    long countSearchByOntologyText(@Param("query") String query);
}