package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.exception.JenaTDB2Exception;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.rdfconnection.RDFConnectionRemote;
import org.apache.jena.sparql.engine.http.QueryExceptionHTTP;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Repository for managing RDF resources in Fuseki TDB2 via HTTP connection.
 * Uses RDFConnection to connect to Fuseki server instead of direct TDB2 file access.
 */
@Repository
@Slf4j
@Getter
public class JenaTDB2Repository {

    private final HttpClient fusekiHttpClient;
    private final Semaphore fusekiSemaphore;
    private final int fusekiSemaphoreTimeout;

    @Value("${jena.fuseki.url}")
    private String fusekiEndpoint;

    public JenaTDB2Repository(HttpClient fusekiHttpClient, Semaphore fusekiSemaphore, int fusekiSemaphoreTimeout) {
        this.fusekiHttpClient = fusekiHttpClient;
        this.fusekiSemaphore = fusekiSemaphore;
        this.fusekiSemaphoreTimeout = fusekiSemaphoreTimeout;
    }

    private RDFConnection createConnection() {
        return RDFConnectionRemote.newBuilder()
                .destination(fusekiEndpoint)
                .queryEndpoint("sparql")
                .gspEndpoint("data")
                .updateEndpoint("update")
                .httpClient(fusekiHttpClient)
                .build();
    }

    private <T> T executeWithSemaphore(FusekiOperation<T> operation) {
        boolean acquired;
        try {
            acquired = fusekiSemaphore.tryAcquire(fusekiSemaphoreTimeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JenaTDB2Exception("Interrupted while waiting for Fuseki connection", e);
        }
        if (!acquired) {
            log.warn("Fuseki semaphore acquisition timed out after {}ms, available permits: {}",
                    fusekiSemaphoreTimeout, fusekiSemaphore.availablePermits());
            throw new JenaTDB2Exception("Fuseki server is busy, try again later");
        }
        try (RDFConnection conn = createConnection()) {
            return operation.execute(conn);
        } finally {
            fusekiSemaphore.release();
        }
    }

    private void executeWithSemaphoreVoid(FusekiVoidOperation operation) {
        boolean acquired;
        try {
            acquired = fusekiSemaphore.tryAcquire(fusekiSemaphoreTimeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JenaTDB2Exception("Interrupted while waiting for Fuseki connection", e);
        }
        if (!acquired) {
            log.warn("Fuseki semaphore acquisition timed out after {}ms, available permits: {}",
                    fusekiSemaphoreTimeout, fusekiSemaphore.availablePermits());
            throw new JenaTDB2Exception("Fuseki server is busy, try again later");
        }
        try (RDFConnection conn = createConnection()) {
            operation.execute(conn);
        } finally {
            fusekiSemaphore.release();
        }
    }

    @FunctionalInterface
    private interface FusekiOperation<T> {
        T execute(RDFConnection conn);
    }

    @FunctionalInterface
    private interface FusekiVoidOperation {
        void execute(RDFConnection conn);
    }

    @PostConstruct
    public void init() {
        try {
            log.info("Initializing Fuseki connection to: {}", fusekiEndpoint);

            try (RDFConnection conn = createConnection()) {
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

        try {
            return executeWithSemaphore(conn -> {
                Model conceptModel = conceptResource.getModel();

                log.info("=== SAVING CONCEPT ===");
                log.info("Concept URI: {}", conceptResource.getURI());
                log.info("Graph name: {}", graphName);
                log.info("Model size: {} statements", conceptModel.size());

                conceptModel.listStatements().forEachRemaining(stmt -> log.debug("  {} --{}--> {}",
                        stmt.getSubject(),
                        stmt.getPredicate().getLocalName(),
                        stmt.getObject()));

                conn.load(graphName, conceptModel);

                log.info("Successfully saved concept to TDB2 graph {}: {}", graphName, conceptResource.getURI());
                return conceptResource.getURI();
            });
        } catch (QueryExceptionHTTP e) {
            log.error("Fuseki HTTP error saving concept to graph {}: {}", graphName, e.getMessage());
            throw new JenaTDB2Exception("Nepodařilo se uložit pojem do databáze", e);
        } catch (HttpException e) {
            log.error("HTTP connection error saving concept to graph {}: {}", graphName, e.getMessage());
            throw new JenaTDB2Exception("Nepodařilo se uložit pojem do databáze", e);
        } catch (JenaTDB2Exception e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error saving concept to graph {}: {}", graphName, e);
            throw new JenaTDB2Exception("Nepodařilo se uložit pojem do databáze", e);
        }
    }

    public boolean conceptNotFoundInGraph(String conceptUri, String graphName) {
        if (conceptUri == null || conceptUri.trim().isEmpty()) {
            return true;
        }

        try {
            return executeWithSemaphore(conn -> {
                ParameterizedSparqlString pss = new ParameterizedSparqlString();
                pss.setCommandText("ASK { GRAPH ?g { ?s ?p ?o } }");
                pss.setIri("g", graphName);
                pss.setIri("s", conceptUri);
                String askQuery = pss.toString();

                boolean exists = conn.queryAsk(askQuery);
                log.debug("Concept existence check in graph '{}' for '{}': {}",
                        graphName, conceptUri, exists);
                return !exists;
            });
        } catch (QueryExceptionHTTP e) {
            log.error("Fuseki HTTP error checking concept existence in graph {} for URI {}: {}", graphName, conceptUri, e.getMessage());
            throw new JenaTDB2Exception("Nepodařilo se ověřit existenci pojmu v grafu", e);
        } catch (HttpException e) {
            log.error("HTTP connection error checking concept existence in graph {} for URI {}: {}", graphName, conceptUri, e.getMessage());
            throw new JenaTDB2Exception("Nepodařilo se ověřit existenci pojmu v grafu", e);
        } catch (JenaTDB2Exception e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error checking concept existence in graph {} for URI: {}", graphName, conceptUri, e);
            throw new JenaTDB2Exception("Nepodařilo se ověřit existenci pojmu v grafu", e);
        }
    }

    private boolean conceptNotFoundInGraph(RDFConnection conn, String conceptUri, String graphName) {
        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText("ASK { GRAPH ?g { ?s ?p ?o } }");
        pss.setIri("g", graphName);
        pss.setIri("s", conceptUri);
        String askQuery = pss.toString();

        boolean exists = conn.queryAsk(askQuery);
        log.debug("Concept existence check in graph '{}' for '{}': {}",
                graphName, conceptUri, exists);
        return !exists;
    }

    public void deleteConceptFromGraph(String conceptUri, String graphName) {
        if (conceptUri == null || conceptUri.trim().isEmpty()) {
            throw new IllegalArgumentException("Concept URI cannot be null or empty");
        }

        try {
            executeWithSemaphoreVoid(conn -> {
                log.info("=== DELETING CONCEPT FROM GRAPH ===");
                log.info("Concept URI: {}", conceptUri);
                log.info("Graph name: {}", graphName);

                if (conceptNotFoundInGraph(conn, conceptUri, graphName)) {
                    log.warn("Cannot delete concept - not found in graph {}: {}",
                            graphName, conceptUri);
                    return;
                }

                ParameterizedSparqlString pss = new ParameterizedSparqlString();
                pss.setCommandText(
                        "DELETE WHERE { GRAPH ?g { ?concept ?p ?o } }; " +
                        "DELETE WHERE { GRAPH ?g { ?s ?p ?concept } }"
                );
                pss.setIri("g", graphName);
                pss.setIri("concept", conceptUri);
                String deleteUpdate = pss.toString();

                conn.update(deleteUpdate);
                log.info("Successfully deleted concept from TDB2 graph {}: {}",
                        graphName, conceptUri);
            });
        } catch (QueryExceptionHTTP e) {
            log.error("Fuseki HTTP error deleting concept from graph {} {}: {}", graphName, conceptUri, e.getMessage());
            throw new JenaTDB2Exception("Nepodařilo se odstranit pojem z TDB2", e);
        } catch (HttpException e) {
            log.error("HTTP connection error deleting concept from graph {} {}: {}", graphName, conceptUri, e.getMessage());
            throw new JenaTDB2Exception("Nepodařilo se odstranit pojem z TDB2", e);
        } catch (JenaTDB2Exception e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error deleting concept from graph {} {}: {}", graphName, conceptUri, e);
            throw new JenaTDB2Exception("Nepodařilo se odstranit pojem z TDB2", e);
        }
    }

    public void deleteConceptsFromGraph(List<String> conceptUris, String graphName) {
        if (conceptUris == null || conceptUris.isEmpty()) {
            throw new IllegalArgumentException("Concept URI cannot be null or empty");
        }

        try {
            executeWithSemaphoreVoid(conn -> {
                log.info("=== DELETING CONCEPT FROM GRAPH ===");
                log.info("Concept URIs: {}", conceptUris.size());
                log.info("Graph name: {}", graphName);

                for (String conceptUri : conceptUris) {
                    if (conceptNotFoundInGraph(conn, conceptUri, graphName)) {
                        log.warn("Cannot delete concept - not found in graph {}: {}",
                                graphName, conceptUri);
                        continue;
                    }
                    ParameterizedSparqlString pss = new ParameterizedSparqlString();
                    pss.setCommandText(
                            "DELETE WHERE { GRAPH ?g { ?concept ?p ?o } }; " +
                            "DELETE WHERE { GRAPH ?g { ?s ?p ?concept } }"
                    );
                    pss.setIri("g", graphName);
                    pss.setIri("concept", conceptUri);
                    String deleteUpdate = pss.toString();

                    conn.update(deleteUpdate);
                    log.info("Successfully deleted concept from TDB2 graph {}: {}",
                            graphName, conceptUri);
                }
            });
        } catch (QueryExceptionHTTP e) {
            log.error("Fuseki HTTP error deleting concepts from graph {}: {}", graphName, e.getMessage());
            throw new JenaTDB2Exception("Nepodařilo se odstranit pojem z TDB2", e);
        } catch (HttpException e) {
            log.error("HTTP connection error deleting concepts from graph {}: {}", graphName, e.getMessage());
            throw new JenaTDB2Exception("Nepodařilo se odstranit pojem z TDB2", e);
        } catch (JenaTDB2Exception e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error deleting concepts from graph {}: {}", graphName, e);
            throw new JenaTDB2Exception("Nepodařilo se odstranit pojem z TDB2", e);
        }
    }

    public void saveOntologyModel(String graphName, Model model) {
        try {
            executeWithSemaphoreVoid(conn -> {
                log.info("=== SAVING ONTOLOGY ===");
                log.info("Graph name: {}", graphName);
                log.info("Model size: {} statements", model.size());

                conn.load(graphName, model);
                log.info("Successfully saved ontology model to TDB2 with graph name: {}", graphName);
            });
        } catch (QueryExceptionHTTP e) {
            log.error("Fuseki HTTP error saving ontology model to graph {}: {}", graphName, e.getMessage());
            throw new JenaTDB2Exception("Nepodařilo se uložit slovník do databáze: " + e.getMessage(), e);
        } catch (HttpException e) {
            log.error("HTTP connection error saving ontology model to graph {}: {}", graphName, e.getMessage());
            throw new JenaTDB2Exception("Nepodařilo se uložit slovník do databáze: " + e.getMessage(), e);
        } catch (JenaTDB2Exception e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error saving ontology model to graph {}: {}", graphName, e);
            throw new JenaTDB2Exception("Nepodařilo se uložit slovník do databáze: " + e.getMessage(), e);
        }
    }

    public void putOntologyModel(String graphName, Model model) {
        try {
            executeWithSemaphoreVoid(conn -> {
                log.info("=== UPLOADING ONTOLOGY ===");
                log.info("Graph name: {}", graphName);
                log.info("Model size: {} statements", model.size());

                conn.put(graphName, model);
            });
        } catch (QueryExceptionHTTP e) {
            log.error("Fuseki HTTP error uploading ontology model to graph {}: {}", graphName, e.getMessage());
            throw new JenaTDB2Exception("Failed to upload to TDB2", e);
        } catch (HttpException e) {
            log.error("HTTP connection error uploading ontology model to graph {}: {}", graphName, e.getMessage());
            throw new JenaTDB2Exception("Failed to upload to TDB2", e);
        } catch (JenaTDB2Exception e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error uploading ontology model to graph {}: {}", graphName, e);
            throw new JenaTDB2Exception("Failed to upload to TDB2", e);
        }
    }

    public void deleteGraph(String graphName) {
        try {
            executeWithSemaphoreVoid(conn -> {
                log.info("=== DELETING ONTOLOGY ===");
                log.info("Graph name: {}", graphName);

                conn.delete(graphName);
                log.info("Successfully deleted graph: {}", graphName);
            });
        } catch (QueryExceptionHTTP e) {
            log.error("Fuseki HTTP error deleting graph {}: {}", graphName, e.getMessage());
            throw new JenaTDB2Exception("Failed to delete graph: " + e.getMessage(), e);
        } catch (HttpException e) {
            log.error("HTTP connection error deleting graph {}: {}", graphName, e.getMessage());
            throw new JenaTDB2Exception("Failed to delete graph: " + e.getMessage(), e);
        } catch (JenaTDB2Exception e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error deleting graph {}: {}", graphName, e);
            throw new JenaTDB2Exception("Failed to delete graph: " + e.getMessage(), e);
        }
    }

    public Model fetchGraph(String graphName) {
        try {
            return executeWithSemaphore(conn -> {
                Model model = conn.fetch(graphName);
                log.debug("Fetched graph '{}' with {} statements", graphName, model.size());
                return model;
            });
        } catch (QueryExceptionHTTP e) {
            log.error("Fuseki HTTP error fetching graph {}: {}", graphName, e.getMessage());
            throw new JenaTDB2Exception("Failed to fetch graph: " + e.getMessage(), e);
        } catch (HttpException e) {
            log.error("HTTP connection error fetching graph {}: {}", graphName, e.getMessage());
            throw new JenaTDB2Exception("Failed to fetch graph: " + e.getMessage(), e);
        } catch (JenaTDB2Exception e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error fetching graph {}: {}", graphName, e);
            throw new JenaTDB2Exception("Failed to fetch graph: " + e.getMessage(), e);
        }
    }

    public boolean graphHasData(String graphName) {
        try {
            return executeWithSemaphore(conn -> {
                ParameterizedSparqlString pss = new ParameterizedSparqlString();
                pss.setCommandText("ASK { GRAPH ?g { ?s ?p ?o } }");
                pss.setIri("g", graphName);
                boolean hasData = conn.queryAsk(pss.toString());
                log.debug("Graph '{}' has data: {}", graphName, hasData);
                return hasData;
            });
        } catch (QueryExceptionHTTP e) {
            log.error("Fuseki HTTP error checking graph {}: {}", graphName, e.getMessage());
            throw new JenaTDB2Exception("Failed to check graph: " + e.getMessage(), e);
        } catch (HttpException e) {
            log.error("HTTP connection error checking graph {}: {}", graphName, e.getMessage());
            throw new JenaTDB2Exception("Failed to check graph: " + e.getMessage(), e);
        } catch (JenaTDB2Exception e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error checking graph {}: {}", graphName, e);
            throw new JenaTDB2Exception("Failed to check graph: " + e.getMessage(), e);
        }
    }

    public Model fetchMetadataProperties(List<String> graphNames) {
        if (graphNames == null || graphNames.isEmpty()) {
            return org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        }

        try {
            return executeWithSemaphore(conn -> {
                StringBuilder valuesClause = new StringBuilder();
                for (String graphName : graphNames) {
                    valuesClause.append("<").append(graphName).append("> ");
                }

                String query = "CONSTRUCT { " +
                        "  ?ontology <http://www.w3.org/2004/02/skos/core#prefLabel> ?label . " +
                        "  ?ontology <http://purl.org/dc/terms/description> ?desc . " +
                        "} WHERE { " +
                        "  VALUES ?g { " + valuesClause + "} " +
                        "  GRAPH ?g { " +
                        "    BIND(?g AS ?ontology) " +
                        "    OPTIONAL { ?ontology <http://www.w3.org/2004/02/skos/core#prefLabel> ?label } " +
                        "    OPTIONAL { ?ontology <http://purl.org/dc/terms/description> ?desc } " +
                        "  } " +
                        "}";

                try (QueryExecution qExec = conn.query(query)) {
                    Model result = qExec.execConstruct();
                    log.debug("Fetched metadata properties for {} graphs, result has {} statements",
                            graphNames.size(), result.size());
                    return result;
                }
            });
        } catch (QueryExceptionHTTP e) {
            log.error("Fuseki HTTP error fetching metadata properties: {}", e.getMessage());
            throw new JenaTDB2Exception("Failed to fetch metadata properties: " + e.getMessage(), e);
        } catch (HttpException e) {
            log.error("HTTP connection error fetching metadata properties: {}", e.getMessage());
            throw new JenaTDB2Exception("Failed to fetch metadata properties: " + e.getMessage(), e);
        } catch (JenaTDB2Exception e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error fetching metadata properties: {}", e.getMessage());
            throw new JenaTDB2Exception("Failed to fetch metadata properties: " + e.getMessage(), e);
        }
    }

    public Model fetchMetadataProperties(String graphName) {
        return fetchMetadataProperties(List.of(graphName));
    }

    public List<String> findRelatedConceptUris(String conceptUri, String graphName) {
        try {
            return executeWithSemaphore(conn -> {
                ParameterizedSparqlString pss = getParameterizedSparqlString(conceptUri, graphName);

                List<String> relatedUris = new ArrayList<>();
                try (QueryExecution qExec = conn.query(pss.toString())) {
                    ResultSet results = qExec.execSelect();
                    while (results.hasNext()) {
                        QuerySolution solution = results.next();
                        Resource related = solution.getResource("related");
                        if (related != null && related.isURIResource()) {
                            relatedUris.add(related.getURI());
                        }
                    }
                }
                log.debug("Found {} related concepts for '{}' in graph '{}'",
                        relatedUris.size(), conceptUri, graphName);
                return relatedUris;
            });
        } catch (QueryExceptionHTTP e) {
            log.error("Fuseki HTTP error finding related concepts for {} in graph {}: {}", conceptUri, graphName, e.getMessage());
            throw new JenaTDB2Exception("Failed to find related concepts: " + e.getMessage(), e);
        } catch (HttpException e) {
            log.error("HTTP connection error finding related concepts for {} in graph {}: {}", conceptUri, graphName, e.getMessage());
            throw new JenaTDB2Exception("Failed to find related concepts: " + e.getMessage(), e);
        } catch (JenaTDB2Exception e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error finding related concepts for {} in graph {}: {}", conceptUri, graphName, e);
            throw new JenaTDB2Exception("Failed to find related concepts: " + e.getMessage(), e);
        }
    }

    private static ParameterizedSparqlString getParameterizedSparqlString(String conceptUri, String graphName) {
        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText(
                "SELECT ?related WHERE { " +
                "  GRAPH ?g { " +
                "    { ?related <http://www.w3.org/2000/01/rdf-schema#domain> ?concept } " +
                "    UNION " +
                "    { ?related <http://www.w3.org/2000/01/rdf-schema#range> ?concept } " +
                "  } " +
                "}"
        );
        pss.setIri("g", graphName);
        pss.setIri("concept", conceptUri);
        return pss;
    }
}
