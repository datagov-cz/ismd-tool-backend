package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.exception.JenaTDB2Exception;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
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
            log.error("Failed to connect to Fuseki at: {}", fusekiEndpoint, e);
            throw new JenaTDB2Exception("Cannot initialize Fuseki connection", e);
        }
    }

    public String saveConcept(Resource conceptResource) {
        try (RDFConnection conn = RDFConnection.connect(fusekiEndpoint)) {
            Model conceptModel = conceptResource.getModel();

            log.info("=== SAVING CONCEPT ===");
            log.info("Concept URI: {}", conceptResource.getURI());
            log.info("Model size: {} statements", conceptModel.size());

            conceptModel.listStatements().forEachRemaining(stmt -> log.info("  {} --{}--> {}",
                    stmt.getSubject(),
                    stmt.getPredicate().getLocalName(),
                    stmt.getObject()));

            conn.load(conceptModel);

            log.info("Successfully saved concept to TDB2: {}", conceptResource.getURI());
            return conceptResource.getURI();

        } catch (Exception e) {
            log.error("Failed to save concept", e);
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
