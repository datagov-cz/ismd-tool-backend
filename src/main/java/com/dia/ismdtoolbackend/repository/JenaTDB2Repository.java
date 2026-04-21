package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.exception.JenaTDB2Exception;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
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

import com.dia.ismdtoolbackend.enums.RelationType;

import java.net.http.HttpClient;
import java.util.*;
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

    RDFConnection createConnection() {
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
            return ModelFactory.createDefaultModel();
        }

        // Validate IRIs before interpolating into the VALUES clause. Raw
        // concatenation would allow a crafted stored IRI to escape <...>
        // and inject SPARQL. Invalid entries are dropped rather than
        // sanitized so malformed data cannot silently change query semantics.
        List<String> safeGraphNames = graphNames.stream()
                .filter(SparqlIriValidator::isSafeHttpIri)
                .toList();
        if (safeGraphNames.size() != graphNames.size()) {
            log.warn("Dropped {} invalid graph IRI(s) from fetchMetadataProperties",
                    graphNames.size() - safeGraphNames.size());
        }
        if (safeGraphNames.isEmpty()) {
            return ModelFactory.createDefaultModel();
        }

        try {
            return executeWithSemaphore(conn -> {
                ParameterizedSparqlString pss = new ParameterizedSparqlString();
                pss.append("CONSTRUCT { ");
                pss.append("  ?ontology <http://www.w3.org/2004/02/skos/core#prefLabel> ?label . ");
                pss.append("  ?ontology <http://purl.org/dc/terms/description> ?desc . ");
                pss.append("} WHERE { VALUES ?g { ");
                for (String graphName : safeGraphNames) {
                    pss.appendIri(graphName);
                    pss.append(" ");
                }
                pss.append("} GRAPH ?g { ");
                pss.append("  BIND(?g AS ?ontology) ");
                pss.append("  OPTIONAL { ?ontology <http://www.w3.org/2004/02/skos/core#prefLabel> ?label } ");
                pss.append("  OPTIONAL { ?ontology <http://purl.org/dc/terms/description> ?desc } ");
                pss.append("} }");

                try (QueryExecution qExec = conn.query(pss.asQuery())) {
                    Model result = qExec.execConstruct();
                    log.debug("Fetched metadata properties for {} graphs, result has {} statements",
                            safeGraphNames.size(), result.size());
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

    /**
     * Full-text search across Fuseki graphs using Lucene text index.
     * The index is configured with ASCIIFoldingFilter for diacritic-insensitive search
     * (e.g., "ridic" matches "řidič").
     *
     * Searches across skos:prefLabel, skos:altLabel, dcterms:description, and skos:definition.
     *
     * @param query             the search term
     * @param visibleGraphNames graphs the user has access to
     * @param limit             maximum number of results to return
     * @return list of result maps with keys: conceptIri, graphName, prefLabel, prefLabelLang,
     *         altLabel, description, definition
     */
    public List<Map<String, String>> searchByText(String query, List<String> visibleGraphNames, int limit) {
        if (visibleGraphNames == null || visibleGraphNames.isEmpty()) {
            return List.of();
        }

        try {
            return executeWithSemaphore(conn -> {
                StringBuilder valuesClause = new StringBuilder();
                for (String graphName : visibleGraphNames) {
                    valuesClause.append("<").append(graphName).append("> ");
                }

                // Sanitize and build Lucene query term with wildcard for prefix matching
                String sanitizedQuery = sanitizeLuceneQuery(query);

                // text:query inside GRAPH — requires Jena 5.4+ where the property
                // function is correctly wired through the TextDataset assembler.
                String sparql = "PREFIX text: <http://jena.apache.org/text#> " +
                        "PREFIX skos: <http://www.w3.org/2004/02/skos/core#> " +
                        "PREFIX dcterms: <http://purl.org/dc/terms/> " +
                        "SELECT ?concept ?g ?prefLabel ?prefLabelLang ?altLabel ?description ?definition WHERE { " +
                        "  VALUES ?g { " + valuesClause + "} " +
                        "  GRAPH ?g { " +
                        "    ?concept text:query (skos:prefLabel skos:altLabel dcterms:description skos:definition '" + sanitizedQuery + "*') . " +
                        "    OPTIONAL { ?concept skos:prefLabel ?prefLabel . BIND(LANG(?prefLabel) AS ?prefLabelLang) } " +
                        "    OPTIONAL { ?concept skos:altLabel ?altLabel } " +
                        "    OPTIONAL { ?concept dcterms:description ?description } " +
                        "    OPTIONAL { ?concept skos:definition ?definition } " +
                        "  } " +
                        "} LIMIT " + limit;

                log.debug("Fuseki text search SPARQL: {}", sparql);

                List<Map<String, String>> results = new ArrayList<>();
                try (QueryExecution qExec = conn.query(sparql)) {
                    ResultSet rs = qExec.execSelect();
                    while (rs.hasNext()) {
                        QuerySolution sol = rs.next();
                        Map<String, String> row = new HashMap<>();
                        row.put("conceptIri", sol.getResource("concept") != null ? sol.getResource("concept").getURI() : null);
                        row.put("graphName", sol.getResource("g") != null ? sol.getResource("g").getURI() : null);
                        if (sol.getLiteral("prefLabel") != null) row.put("prefLabel", sol.getLiteral("prefLabel").getString());
                        if (sol.getLiteral("prefLabelLang") != null) row.put("prefLabelLang", sol.getLiteral("prefLabelLang").getString());
                        if (sol.getLiteral("altLabel") != null) row.put("altLabel", sol.getLiteral("altLabel").getString());
                        if (sol.getLiteral("description") != null) row.put("description", sol.getLiteral("description").getString());
                        if (sol.getLiteral("definition") != null) row.put("definition", sol.getLiteral("definition").getString());
                        results.add(row);
                    }
                }
                log.debug("Fuseki text search for '{}' across {} graphs returned {} results",
                        query, visibleGraphNames.size(), results.size());
                return results;
            });
        } catch (JenaTDB2Exception e) {
            throw e;
        } catch (Exception e) {
            log.error("Error executing Fuseki text search for '{}': {}", query, e.getMessage(), e);
            throw new JenaTDB2Exception("Failed to execute text search in Fuseki", e);
        }
    }

    /**
     * Sanitizes user input for use in a Lucene query string.
     * Escapes Lucene special characters to prevent query injection.
     */
    private static String sanitizeLuceneQuery(String input) {
        if (input == null) return "";
        // Escape Lucene special characters: + - && || ! ( ) { } [ ] ^ " ~ * ? : \ /
        // We keep * out since we append it ourselves for prefix matching
        return input.replaceAll("([+\\-!(){}\\[\\]^\"~?:\\\\/]|&&|\\|\\|)", "\\\\$1")
                .replace("'", "\\'")
                .trim()
                .toLowerCase();
    }

    /**
     * Fetches labels and descriptions for a batch of concept IRIs from Fuseki.
     * Returns a Model containing skos:prefLabel, skos:altLabel, dcterms:description,
     * and skos:definition triples.
     */
    public Model fetchConceptLabels(List<String> conceptIris) {
        if (conceptIris == null || conceptIris.isEmpty()) {
            return ModelFactory.createDefaultModel();
        }

        List<String> safeConceptIris = conceptIris.stream()
                .filter(SparqlIriValidator::isSafeHttpIri)
                .toList();
        if (safeConceptIris.size() != conceptIris.size()) {
            log.warn("Dropped {} invalid concept IRI(s) from fetchConceptLabels",
                    conceptIris.size() - safeConceptIris.size());
        }
        if (safeConceptIris.isEmpty()) {
            return ModelFactory.createDefaultModel();
        }

        try {
            return executeWithSemaphore(conn -> {
                ParameterizedSparqlString pss = new ParameterizedSparqlString();
                pss.append("PREFIX skos: <http://www.w3.org/2004/02/skos/core#> ");
                pss.append("PREFIX dcterms: <http://purl.org/dc/terms/> ");
                pss.append("CONSTRUCT { ");
                pss.append("  ?concept skos:prefLabel ?prefLabel . ");
                pss.append("  ?concept skos:altLabel ?altLabel . ");
                pss.append("  ?concept dcterms:description ?desc . ");
                pss.append("  ?concept skos:definition ?def . ");
                pss.append("} WHERE { VALUES ?concept { ");
                for (String iri : safeConceptIris) {
                    pss.appendIri(iri);
                    pss.append(" ");
                }
                pss.append("} GRAPH ?g { ");
                pss.append("  OPTIONAL { ?concept skos:prefLabel ?prefLabel } ");
                pss.append("  OPTIONAL { ?concept skos:altLabel ?altLabel } ");
                pss.append("  OPTIONAL { ?concept dcterms:description ?desc } ");
                pss.append("  OPTIONAL { ?concept skos:definition ?def } ");
                pss.append("} }");

                try (QueryExecution qExec = conn.query(pss.asQuery())) {
                    Model result = qExec.execConstruct();
                    log.debug("Fetched concept labels for {} IRIs, result has {} statements",
                            safeConceptIris.size(), result.size());
                    return result;
                }
            });
        } catch (JenaTDB2Exception e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching concept labels: {}", e.getMessage(), e);
            throw new JenaTDB2Exception("Failed to fetch concept labels from Fuseki", e);
        }
    }

    /**
     * Filters concept IRIs by relation types. Returns the subset of IRIs that participate
     * in at least one of the specified relation types.
     */
    public Set<String> filterByRelationTypes(List<String> conceptIris, List<RelationType> relationTypes) {
        if (conceptIris == null || conceptIris.isEmpty() || relationTypes == null || relationTypes.isEmpty()) {
            return Set.of();
        }

        try {
            return executeWithSemaphore(conn -> {
                StringBuilder valuesClause = new StringBuilder();
                for (String iri : conceptIris) {
                    valuesClause.append("<").append(iri).append("> ");
                }

                StringBuilder unionClauses = new StringBuilder();
                for (int i = 0; i < relationTypes.size(); i++) {
                    if (i > 0) unionClauses.append(" UNION ");
                    unionClauses.append("{ ").append(getRelationPattern(relationTypes.get(i))).append(" }");
                }

                String sparql = "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> " +
                        "PREFIX skos: <http://www.w3.org/2004/02/skos/core#> " +
                        "SELECT DISTINCT ?concept WHERE { " +
                        "  VALUES ?concept { " + valuesClause + "} " +
                        "  GRAPH ?g { " +
                        "    " + unionClauses + " " +
                        "  } " +
                        "}";

                Set<String> matchingIris = new HashSet<>();
                try (QueryExecution qExec = conn.query(sparql)) {
                    ResultSet rs = qExec.execSelect();
                    while (rs.hasNext()) {
                        QuerySolution sol = rs.next();
                        Resource concept = sol.getResource("concept");
                        if (concept != null && concept.isURIResource()) {
                            matchingIris.add(concept.getURI());
                        }
                    }
                }
                log.debug("Relation type filter: {} of {} concepts matched relation types {}",
                        matchingIris.size(), conceptIris.size(), relationTypes);
                return matchingIris;
            });
        } catch (JenaTDB2Exception e) {
            throw e;
        } catch (Exception e) {
            log.error("Error filtering by relation types: {}", e.getMessage(), e);
            throw new JenaTDB2Exception("Failed to filter concepts by relation types", e);
        }
    }

    private String getRelationPattern(RelationType type) {
        return switch (type) {
            case SUBCLASS -> "?concept rdfs:subClassOf ?other";
            case SUPERCLASS -> "?other rdfs:subClassOf ?concept";
            case EXACT_MATCH -> "{ ?concept skos:exactMatch ?other } UNION { ?other skos:exactMatch ?concept }";
            case PROPERTY_OF -> "?concept rdfs:domain ?other";
            case RELATIONSHIP_OF -> "?concept rdfs:range ?other";
        };
    }

    private static String escapeSparqlString(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
