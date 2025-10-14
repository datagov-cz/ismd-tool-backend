package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.exception.JenaTDB2Exception;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdfconnection.RDFConnection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * Repository for managing RDF resources in Fuseki TDB2 via HTTP connection.
 * Uses RDFConnection to connect to Fuseki server instead of direct TDB2 file access.
 */
@Repository
@Slf4j
@Getter
public class JenaTDB2Repository {

    @Value("${jena.fuseki.url}")
    private String fusekiEndpoint;

    @PostConstruct
    public void init() {
        try {
            log.info("Initializing Fuseki connection to: {}", fusekiEndpoint);

            try (RDFConnection conn = RDFConnection.connect(fusekiEndpoint)) {
                boolean connected = conn.queryAsk("ASK { ?s ?p ?o }");
                log.info("Fuseki connection successful. Dataset has data: {}", connected);
            }

        } catch (Exception e) {
            log.warn("Failed to connect to Fuseki at: {}. This is expected in test environments. Error: {}",
                    fusekiEndpoint, e.getMessage());
        }
    }

    public String saveConcept(Resource conceptResource, String graphName) {
        if (graphName == null || graphName.trim().isEmpty()) {
            throw new IllegalArgumentException("Graph name is required - concepts cannot be saved to the default graph");
        }

        try (RDFConnection conn = RDFConnection.connect(fusekiEndpoint)) {
            Model conceptModel = conceptResource.getModel();

            log.info("=== SAVING CONCEPT ===");
            log.info("Concept URI: {}", conceptResource.getURI());
            log.info("Graph name: {}", graphName);
            log.info("Model size: {} statements", conceptModel.size());

            conceptModel.listStatements().forEachRemaining(stmt -> log.info("  {} --{}--> {}",
                    stmt.getSubject(),
                    stmt.getPredicate().getLocalName(),
                    stmt.getObject()));

            conn.load(graphName, conceptModel);

            log.info("Successfully saved concept to TDB2 graph {}: {}", graphName, conceptResource.getURI());
            return conceptResource.getURI();

        } catch (Exception e) {
            log.error("Failed to save concept to graph {}", graphName, e);
            throw new JenaTDB2Exception("Nepodařilo se uložit pojem do databáze", e);
        }
    }

    public boolean conceptExists(String conceptUri) {
        if (conceptUri == null || conceptUri.trim().isEmpty()) {
            return false;
        }

        try (RDFConnection conn = RDFConnection.connect(fusekiEndpoint)) {
            String askQuery = String.format(
                    "ASK { <%s> ?p ?o }",
                    conceptUri
            );

            boolean exists = conn.queryAsk(askQuery);
            log.debug("Concept existence check for '{}': {}", conceptUri, exists);
            return exists;

        } catch (Exception e) {
            log.error("Error checking concept existence for URI: {}", conceptUri, e);
            return false;
        }
    }

    public String findGraphContainingConcept(String conceptUri) {
        if (conceptUri == null || conceptUri.trim().isEmpty()) {
        return null;
    }

        try (RDFConnection conn = RDFConnection.connect(fusekiEndpoint)) {
            String selectQuery = String.format(
                    "SELECT ?g WHERE { GRAPH ?g { <%s> ?p ?o } } LIMIT 1",
                    conceptUri
            );

            try (QueryExecution qExec = conn.query(selectQuery)) {
                ResultSet results = qExec.execSelect();
                if (results.hasNext()) {
                    String graphUri = results.next().getResource("g").getURI();
                    log.debug("Found concept '{}' in graph: {}", conceptUri, graphUri);
                    return graphUri;
                }
            }

            return null;
        } catch (Exception e) {
            log.error("Error searching for concept in graphs for URI: {}", conceptUri, e);
            return null;
        }
    }

    public boolean conceptExistsInGraph(String conceptUri, String graphName) {
        if (conceptUri == null || conceptUri.trim().isEmpty()) {
            return false;
        }

        try (RDFConnection conn = RDFConnection.connect(fusekiEndpoint)) {
            String askQuery = String.format(
                    "ASK { GRAPH <%s> { <%s> ?p ?o } }",
                    graphName, conceptUri
            );

            boolean exists = conn.queryAsk(askQuery);
            log.debug("Concept existence check in graph '{}' for '{}': {}",
                    graphName, conceptUri, exists);
            return exists;

        } catch (Exception e) {
            log.error("Error checking concept existence in graph {} for URI: {}",
                    graphName, conceptUri, e);
            return false;
        }
    }

    public void deleteConcept(String conceptUri) {
        if (conceptUri == null || conceptUri.trim().isEmpty()) {
            throw new IllegalArgumentException("Concept URI cannot be null or empty");
        }

        try (RDFConnection conn = RDFConnection.connect(fusekiEndpoint)) {
            log.info("=== DELETING CONCEPT ===");
            log.info("Concept URI: {}", conceptUri);

            if (!conceptExists(conceptUri)) {
                log.warn("Cannot delete concept - not found: {}", conceptUri);
                return;
            }

            String deleteUpdate = String.format(
                    "DELETE WHERE { <%s> ?p ?o }; " +
                            "DELETE WHERE { ?s ?p <%s> }",
                    conceptUri, conceptUri
            );

            conn.update(deleteUpdate);
            log.info("Successfully deleted concept from TDB2: {}", conceptUri);

        } catch (Exception e) {
            log.error("Failed to delete concept from TDB2: {}", conceptUri, e);
            throw new JenaTDB2Exception("Nepodařilo se odstranit pojem z TDB2", e);
        }
    }

    public void deleteConceptFromGraph(String conceptUri, String graphName) {
        if (conceptUri == null || conceptUri.trim().isEmpty()) {
            throw new IllegalArgumentException("Concept URI cannot be null or empty");
        }

        try (RDFConnection conn = RDFConnection.connect(fusekiEndpoint)) {
            log.info("=== DELETING CONCEPT FROM GRAPH ===");
            log.info("Concept URI: {}", conceptUri);
            log.info("Graph name: {}", graphName);

            if (!conceptExistsInGraph(conceptUri, graphName)) {
                log.warn("Cannot delete concept - not found in graph {}: {}",
                        graphName, conceptUri);
                return;
            }

            String deleteUpdate = String.format(
                    "DELETE WHERE { GRAPH <%s> { <%s> ?p ?o } }; " +
                            "DELETE WHERE { GRAPH <%s> { ?s ?p <%s> } }",
                    graphName, conceptUri, graphName, conceptUri
            );

            conn.update(deleteUpdate);
            log.info("Successfully deleted concept from TDB2 graph {}: {}",
                    graphName, conceptUri);

        } catch (Exception e) {
            log.error("Failed to delete concept from TDB2 graph {}: {}",
                    graphName, conceptUri, e);
            throw new JenaTDB2Exception("Nepodařilo se odstranit pojem z TDB2", e);
        }
    }

    public void saveOntologyModel(String graphName, Model model) {
        try (RDFConnection conn = RDFConnection.connect(fusekiEndpoint)) {
            log.info("=== SAVING ONTOLOGY ===");
            log.info("Graph name: {}", graphName);
            log.info("Model size: {} statements", model.size());

            conn.load(graphName, model);
            log.info("Successfully saved ontology model to TDB2 with graph name: {}", graphName);
        } catch (Exception e) {
            log.error("Failed to save ontology model to TDB2: {}", e.getMessage());
            throw new JenaTDB2Exception("Nepodařilo se uložit slovník do databáze: " + e.getMessage(), e);
        }
    }

    public void putOntologyModel(String graphName, Model model) {
        try (RDFConnection conn = RDFConnection.connect(fusekiEndpoint)) {
            log.info("=== UPLOADING ONTOLOGY ===");
            log.info("Graph name: {}", graphName);
            log.info("Model size: {} statements", model.size());

            conn.put(graphName, model);
        } catch (Exception e) {
            log.error("Failed to put ontology model to TDB2: {}", e.getMessage());
            throw new JenaTDB2Exception("Failed to upload to TDB2", e);
        }
    }

    public void deleteGraph(String graphName) {
        try (RDFConnection conn = RDFConnection.connect(fusekiEndpoint)) {
            log.info("=== DELETING ONTOLOGY ===");
            log.info("Graph name: {}", graphName);

            conn.delete(graphName);
            log.info("Successfully deleted graph: {}", graphName);
        } catch (Exception e) {
            log.error("Failed to delete graph: {}", graphName, e);
            throw new JenaTDB2Exception("Failed to delete graph: " + e.getMessage(), e);
        }
    }

    public Model fetchGraph(String graphName) {
        try (RDFConnection conn = RDFConnection.connect(fusekiEndpoint)) {
            Model model = conn.fetch(graphName);
            log.debug("Fetched graph '{}' with {} statements", graphName, model.size());
            return model;
        } catch (Exception e) {
            log.error("Failed to fetch graph: {}", graphName, e);
            throw new JenaTDB2Exception("Failed to fetch graph: " + e.getMessage(), e);
        }
    }
}
