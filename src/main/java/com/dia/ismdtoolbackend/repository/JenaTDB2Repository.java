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
        try (RDFConnection conn = RDFConnection.connect(fusekiEndpoint)) {
            Model conceptModel = conceptResource.getModel();

            log.info("Saving concept: {} ({} statements)",
                    conceptResource.getURI(), conceptModel.size());

            StringBuilder insertQuery = new StringBuilder("INSERT DATA { \n");

            conceptModel.listStatements().forEachRemaining(stmt -> {
                String subject = formatNode(stmt.getSubject());
                String predicate = "<" + stmt.getPredicate().getURI() + ">";
                String object = formatNode(stmt.getObject());

                insertQuery.append(String.format("  %s %s %s .%n", subject, predicate, object));
            });

            insertQuery.append("}");

            conn.update(insertQuery.toString());

            log.info("Successfully saved concept to Fuseki: {}", conceptResource.getURI());
            return conceptResource.getURI();

        } catch (Exception e) {
            log.error("Failed to save concept", e);
            throw new JenaTDB2Exception("Nepodařilo se uložit pojem do Fuseki", e);
        }
    }

    private String formatNode(org.apache.jena.rdf.model.RDFNode node) {
        if (node.isURIResource()) {
            return "<" + node.asResource().getURI() + ">";
        } else if (node.isLiteral()) {
            org.apache.jena.rdf.model.Literal lit = node.asLiteral();
            String lexical = lit.getLexicalForm()
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n");

            if (lit.getLanguage() != null && !lit.getLanguage().isEmpty()) {
                return "\"" + lexical + "\"@" + lit.getLanguage();
            } else if (lit.getDatatypeURI() != null) {
                return "\"" + lexical + "\"^^<" + lit.getDatatypeURI() + ">";
            } else {
                return "\"" + lexical + "\"";
            }
        } else if (node.isAnon()) {
            return "_:" + node.asResource().getId().getLabelString();
        }
        return node.toString();
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
