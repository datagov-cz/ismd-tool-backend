package com.dia.ismdtoolbackend.testsupport.pg;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramPendingEditEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import org.springframework.boot.test.context.TestComponent;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Database fixtures for the Postgres integration tests.
 *
 * <p>Every {@code create*}/{@code stage*} method writes exactly the row it names through the
 * repositories, so it joins the caller's transaction when there is one and commits on its own
 * otherwise. Nothing is created lazily.
 */
@TestComponent
public class PgTestData {

    /** Owner of the fixture ontologies unless a test says otherwise. */
    public static final String USER = "user123";

    private static final String SCHEMA = "ismd_schema";

    private final OntologyMetadataRepository ontologyRepository;
    private final ConceptMetadataRepository conceptRepository;
    private final DiagramRepository diagramRepository;
    private final DiagramPendingEditRepository pendingEditRepository;

    public PgTestData(OntologyMetadataRepository ontologyRepository, ConceptMetadataRepository conceptRepository,
                      DiagramRepository diagramRepository, DiagramPendingEditRepository pendingEditRepository) {
        this.ontologyRepository = ontologyRepository;
        this.conceptRepository = conceptRepository;
        this.diagramRepository = diagramRepository;
        this.pendingEditRepository = pendingEditRepository;
    }

    /**
     * Truncates every table in {@code ismd_schema} except Liquibase's own, on a connection of its own
     * that commits before returning. It never joins a Spring-managed transaction, so a rolled-back
     * test cannot undo it and it cannot collide with rows the same transaction inserts afterwards.
     */
    public static void truncateAll(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                // A connection holding a lock (e.g. a stuck thread) fails the wipe instead of hanging it.
                statement.execute("SET LOCAL lock_timeout = '10s'");
                List<String> tables = applicationTables(statement);
                if (!tables.isEmpty()) {
                    statement.execute("TRUNCATE TABLE " + String.join(", ", tables) + " CASCADE");
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not wipe the test database", e);
        }
    }

    private static List<String> applicationTables(Statement statement) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery(
                "SELECT tablename FROM pg_tables WHERE schemaname = '" + SCHEMA + "'"
                        + " AND tablename NOT LIKE 'databasechangelog%' ORDER BY tablename")) {
            while (rs.next()) {
                tables.add(SCHEMA + ".\"" + rs.getString(1) + "\"");
            }
        }
        return tables;
    }

    /** An unpublished ontology owned by {@link #USER}. */
    public OntologyMetadataEntity createOntology(String slug, String graphName) {
        return createOntology(slug, graphName, USER, false);
    }

    public OntologyMetadataEntity createOntology(String slug, String graphName, String userId, Boolean published) {
        OntologyMetadataEntity o = new OntologyMetadataEntity();
        o.setSlug(slug);
        o.setGraphName(graphName);
        o.setUserId(userId);
        o.setIsPublished(published);
        o.setCreatedAt(LocalDateTime.now());
        return ontologyRepository.save(o);
    }

    /** A concept of {@code ontology}, in its graph and owned by its owner; the slug is the IRI's last segment. */
    public ConceptMetadataEntity createConcept(OntologyMetadataEntity ontology, String iri, String name,
                                               ConceptType type) {
        ConceptMetadataEntity c = new ConceptMetadataEntity();
        c.setConceptIri(iri);
        c.setConceptName(name);
        c.setConceptType(type);
        c.setGraphName(ontology.getGraphName());
        c.setUserId(ontology.getUserId());
        c.setOntologyMetadata(ontology);
        c.setSlug(iri.substring(iri.lastIndexOf('/') + 1));
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        return conceptRepository.save(c);
    }

    /** An empty diagram of {@code ontology}; returns its id. */
    public Long createDiagram(OntologyMetadataEntity ontology, String name) {
        DiagramEntity d = new DiagramEntity();
        d.setOntologyMetadata(ontology);
        d.setName(name);
        return diagramRepository.saveAndFlush(d).getId();
    }

    /** Stages {@code edit} for {@code conceptIri} on the diagram, scoped to {@code ontology}. */
    public void stagePendingEdit(Long diagramId, OntologyMetadataEntity ontology, String conceptIri,
                                 DiagramPendingEdit edit) {
        DiagramPendingEditEntity row = new DiagramPendingEditEntity();
        row.setDiagram(diagramRepository.findById(diagramId).orElseThrow());
        row.setOntologyMetadata(ontology);
        row.setConceptIri(conceptIri);
        row.setPendingEdit(edit);
        pendingEditRepository.saveAndFlush(row);
    }

    /** The diagram's stored {@code @Version}, the value a Save must echo. */
    public Long diagramVersion(Long diagramId) {
        return diagramRepository.findById(diagramId).orElseThrow().getVersion();
    }
}
