package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.exception.JenaTDB2Exception;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdfconnection.RDFConnection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * Repository for managing RDF concepts in Fuseki TDB2 via HTTP connection.
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
        if (conceptResource == null) {
            throw new IllegalArgumentException("Concept resource cannot be null");
        }

        try (RDFConnection conn = RDFConnection.connect(fusekiEndpoint)) {
            Model conceptModel = conceptResource.getModel();

            log.debug("Saving concept with {} statements to Fuseki default graph",
                    conceptModel.size());

            Model existingModel = conn.fetch();
            existingModel.add(conceptModel);
            conn.put(existingModel);

            String conceptURI = conceptResource.getURI();
            log.info("Successfully saved concept to Fuseki: {}", conceptURI);

            return conceptURI;

        } catch (Exception e) {
            log.error("Failed to save concept to Fuseki: {}", conceptResource.getURI(), e);
            throw new JenaTDB2Exception("Nepodařilo se uložit pojem do Fuseki", e);
        }
    }

    public String saveConceptToGraph(Resource conceptResource, String graphName) {
        if (conceptResource == null) {
            throw new IllegalArgumentException("Concept resource cannot be null");
        }

        try (RDFConnection conn = RDFConnection.connect(fusekiEndpoint)) {
            Model conceptModel = conceptResource.getModel();

            log.debug("Saving concept with {} statements to Fuseki graph: {}",
                    conceptModel.size(), graphName);

            Model existingModel = conn.fetch(graphName);
            if (existingModel == null) {
                existingModel = ModelFactory.createDefaultModel();
            }
            existingModel.add(conceptModel);
            conn.put(graphName, existingModel);

            String conceptURI = conceptResource.getURI();
            log.info("Successfully saved concept to Fuseki graph {}: {}", graphName, conceptURI);

            return conceptURI;

        } catch (Exception e) {
            log.error("Failed to save concept to Fuseki graph {}: {}",
                    graphName, conceptResource.getURI(), e);
            throw new JenaTDB2Exception("Nepodařilo se uložit pojem do Fuseki", e);
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

    public boolean deleteConcept(String conceptUri) {
        if (conceptUri == null || conceptUri.trim().isEmpty()) {
            throw new IllegalArgumentException("Concept URI cannot be null or empty");
        }

        try (RDFConnection conn = RDFConnection.connect(fusekiEndpoint)) {
            if (!conceptExists(conceptUri)) {
                log.warn("Cannot delete concept - not found: {}", conceptUri);
                return false;
            }

            String deleteUpdate = String.format(
                    "DELETE WHERE { <%s> ?p ?o }; " +
                            "DELETE WHERE { ?s ?p <%s> }",
                    conceptUri, conceptUri
            );

            conn.update(deleteUpdate);
            log.info("Successfully deleted concept from Fuseki: {}", conceptUri);
            return true;

        } catch (Exception e) {
            log.error("Failed to delete concept from Fuseki: {}", conceptUri, e);
            throw new JenaTDB2Exception("Nepodařilo se odstranit pojem z Fuseki", e);
        }
    }

    public boolean deleteConceptFromGraph(String conceptUri, String graphName) {
        if (conceptUri == null || conceptUri.trim().isEmpty()) {
            throw new IllegalArgumentException("Concept URI cannot be null or empty");
        }

        try (RDFConnection conn = RDFConnection.connect(fusekiEndpoint)) {
            if (!conceptExistsInGraph(conceptUri, graphName)) {
                log.warn("Cannot delete concept - not found in graph {}: {}",
                        graphName, conceptUri);
                return false;
            }

            String deleteUpdate = String.format(
                    "DELETE WHERE { GRAPH <%s> { <%s> ?p ?o } }; " +
                            "DELETE WHERE { GRAPH <%s> { ?s ?p <%s> } }",
                    graphName, conceptUri, graphName, conceptUri
            );

            conn.update(deleteUpdate);
            log.info("Successfully deleted concept from Fuseki graph {}: {}",
                    graphName, conceptUri);
            return true;

        } catch (Exception e) {
            log.error("Failed to delete concept from Fuseki graph {}: {}",
                    graphName, conceptUri, e);
            throw new JenaTDB2Exception("Nepodařilo se odstranit pojem z Fuseki", e);
        }
    }
}
