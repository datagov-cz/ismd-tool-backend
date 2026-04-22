package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.query.NKDSPARQLConstructQuery;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.sparql.engine.http.QueryExceptionHTTP;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTPBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class NkdSparqlClient {

    @Value("${nkd.sparql.endpoint}")
    private String nkdSparqlEndpoint;

    @Value("${nkd.sparql.timeout:10000}")
    private int queryTimeout;

    @Value("${nkd.sparql.max-concurrent-requests:4}")
    private int maxConcurrentRequests;

    private final OntologyDetailExtractor detailExtractor;

    public Optional<OntologyDetailModel.ConceptDetailModel> fetchPublishedConcept(String conceptIri) {
        log.debug("Fetching published concept from NKD: {}", conceptIri);

        String query = NKDSPARQLConstructQuery.buildConstructQuery(conceptIri);

        Model resultModel = QueryExecutionHTTPBuilder.service(nkdSparqlEndpoint)
                .query(query)
                .timeout(queryTimeout, TimeUnit.MILLISECONDS)
                .construct();

        if (resultModel == null || resultModel.isEmpty()) {
            log.info("No data found for concept in NKD: {}", conceptIri);
            return Optional.empty();
        }

        log.debug("Fetched {} triples from NKD for concept: {}", resultModel.size(), conceptIri);

        Model processedModel = detailExtractor.applyOFNTransformations(resultModel);
        OntologyDetailModel.ConceptDetailModel conceptDetail =
                detailExtractor.extractConceptDetail(processedModel, conceptIri,
                        OntologyDetailExtractor.iriResolver());

        log.debug("Successfully extracted published concept detail from NKD: {}", conceptIri);
        return Optional.of(conceptDetail);
    }

    public Optional<OntologyDetailModel> fetchPublishedOntology(String ontologyIri) {
        log.debug("Fetching published ontology from NKD: {}", ontologyIri);

        String query = NKDSPARQLConstructQuery.buildOntologyConstructQuery(ontologyIri);

        Model resultModel = QueryExecutionHTTPBuilder.service(nkdSparqlEndpoint)
                .query(query)
                .timeout(queryTimeout, TimeUnit.MILLISECONDS)
                .construct();

        if (resultModel == null || resultModel.isEmpty()) {
            log.info("No data found for ontology in NKD: {}", ontologyIri);
            return Optional.empty();
        }

        log.debug("Fetched {} triples from NKD for ontology: {}", resultModel.size(), ontologyIri);

        Model processedModel = detailExtractor.applyOFNTransformations(resultModel);
        OntologyDetailModel ontologyDetail = detailExtractor.extractOntologyDetail(processedModel,
                OntologyDetailExtractor.iriResolver());

        log.debug("Successfully extracted published ontology detail from NKD: {}", ontologyIri);
        return Optional.of(ontologyDetail);
    }

    public List<String> getPublishedResourcesList(List<String> resourceIris) {
        if (resourceIris == null || resourceIris.isEmpty()) {
            log.debug("No resource IRIs provided for NKD verification");
            return new ArrayList<>();
        }

        if (nkdSparqlEndpoint == null || nkdSparqlEndpoint.trim().isEmpty()) {
            log.warn("NKD SPARQL endpoint not configured, skipping verification");
            return new ArrayList<>();
        }

        log.debug("Verifying {} resources against NKD", resourceIris.size());

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(maxConcurrentRequests, resourceIris.size())
        );
        try {
            List<CompletableFuture<String>> futures = resourceIris.stream()
                    .map(iri -> CompletableFuture.supplyAsync(() ->
                            isConceptPublishedInNKD(iri) ? iri : null, executor))
                    .toList();

            List<String> publishedResources = futures.stream()
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .toList();

            log.info("Found {} published resources out of {} total resources",
                    publishedResources.size(), resourceIris.size());
            return publishedResources;
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Executes a SPARQL SELECT query against the NKD endpoint.
     * Returns results as a list of maps (variable name → string value).
     */
    public List<Map<String, String>> executeSelect(String sparqlQuery) {
        if (nkdSparqlEndpoint == null || nkdSparqlEndpoint.trim().isEmpty()) {
            log.warn("NKD SPARQL endpoint not configured");
            return List.of();
        }

        log.debug("Executing NKD SELECT query");

        List<Map<String, String>> results = new ArrayList<>();
        try (QueryExecution qExec = QueryExecutionHTTPBuilder.service(nkdSparqlEndpoint)
                .query(sparqlQuery)
                .timeout(queryTimeout, TimeUnit.MILLISECONDS)
                .build()) {

            ResultSet rs = qExec.execSelect();
            List<String> vars = rs.getResultVars();

            while (rs.hasNext()) {
                QuerySolution sol = rs.next();
                Map<String, String> row = new LinkedHashMap<>();
                for (String var : vars) {
                    RDFNode node = sol.get(var);
                    if (node != null) {
                        row.put(var, node.isResource() ? node.asResource().getURI() : node.asLiteral().getString());
                    }
                }
                results.add(row);
            }
        }

        log.debug("NKD SELECT returned {} rows", results.size());
        return results;
    }

    public boolean isEndpointConfigured() {
        return nkdSparqlEndpoint != null && !nkdSparqlEndpoint.trim().isEmpty();
    }

    private boolean isConceptPublishedInNKD(String conceptIri) {
        try {
            String query = NKDSPARQLConstructQuery.buildConstructQuery(conceptIri);

            Model resultModel = QueryExecutionHTTPBuilder.service(nkdSparqlEndpoint)
                    .query(query)
                    .timeout(queryTimeout, TimeUnit.MILLISECONDS)
                    .construct();

            boolean isPublished = resultModel != null && !resultModel.isEmpty();
            if (isPublished) {
                log.debug("Concept is published in NKD: {}", conceptIri);
            }
            return isPublished;

        } catch (QueryExceptionHTTP e) {
            log.warn("SPARQL error checking concept in NKD: {} - {}", conceptIri, e.getMessage());
            return false;
        } catch (HttpException e) {
            log.warn("HTTP error checking concept in NKD: {} - {}", conceptIri, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Unexpected error checking concept in NKD: {} - {}", conceptIri, e.getMessage());
            return false;
        }
    }
}
