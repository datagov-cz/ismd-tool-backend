package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.query.NKDSPARQLConstructQuery;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.sparql.engine.http.QueryExceptionHTTP;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTPBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class NkdSparqlClient {

    @Value("${nkd.sparql.endpoint}")
    private String nkdSparqlEndpoint;

    @Value("${nkd.sparql.timeout:10000}")
    private int queryTimeout;

    private final OntologyDetailExtractor detailExtractor;

    public Optional<OntologyDetailModel.ConceptDetailModel> fetchPublishedConcept(String conceptIri) {
        try {
            log.debug("Fetching published concept from NKD: {}", conceptIri);

            if (nkdSparqlEndpoint == null || nkdSparqlEndpoint.trim().isEmpty()) {
                log.warn("NKD SPARQL endpoint not configured");
                return Optional.empty();
            }

            String query = NKDSPARQLConstructQuery.buildConstructQuery(conceptIri);

            Model resultModel = QueryExecutionHTTPBuilder.service(nkdSparqlEndpoint)
                    .query(query)
                    .timeout(queryTimeout, TimeUnit.MILLISECONDS)
                    .construct();

            if (resultModel == null || resultModel.isEmpty()) {
                log.warn("No data found for concept in NKD: {}", conceptIri);
                return Optional.empty();
            }

            log.debug("Fetched {} triples from NKD for concept: {}", resultModel.size(), conceptIri);

            Model processedModel = detailExtractor.applyOFNTransformations(resultModel);
            OntologyDetailModel.ConceptDetailModel conceptDetail =
                    detailExtractor.extractConceptDetail(processedModel, conceptIri);

            if (conceptDetail == null) {
                log.warn("Failed to extract concept detail from NKD data for: {}", conceptIri);
                return Optional.empty();
            }

            log.debug("Successfully extracted published concept detail from NKD: {}", conceptIri);
            return Optional.of(conceptDetail);

        } catch (QueryExceptionHTTP e) {
            log.error("SPARQL query error for concept {}: {}", conceptIri, e.getMessage());
            return Optional.empty();
        } catch (HttpException e) {
            log.error("HTTP error querying NKD SPARQL endpoint for concept {}: {}", conceptIri, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.error("Unexpected error fetching published concept {}: {}", conceptIri, e.getMessage(), e);
            return Optional.empty();
        }
    }

    public Optional<OntologyDetailModel> fetchPublishedOntology(String ontologyIri) {
        try {
            log.debug("Fetching published ontology from NKD: {}", ontologyIri);

            if (nkdSparqlEndpoint == null || nkdSparqlEndpoint.trim().isEmpty()) {
                log.warn("NKD SPARQL endpoint not configured");
                return Optional.empty();
            }

            String query = NKDSPARQLConstructQuery.buildOntologyConstructQuery(ontologyIri);

            Model resultModel = QueryExecutionHTTPBuilder.service(nkdSparqlEndpoint)
                    .query(query)
                    .timeout(queryTimeout, TimeUnit.MILLISECONDS)
                    .construct();

            if (resultModel == null || resultModel.isEmpty()) {
                log.warn("No data found for ontology in NKD: {}", ontologyIri);
                return Optional.empty();
            }

            log.debug("Fetched {} triples from NKD for ontology: {}", resultModel.size(), ontologyIri);

            Model processedModel = detailExtractor.applyOFNTransformations(resultModel);
            OntologyDetailModel ontologyDetail = detailExtractor.extractOntologyDetail(processedModel);

            if (ontologyDetail == null) {
                log.warn("Failed to extract ontology detail from NKD data for: {}", ontologyIri);
                return Optional.empty();
            }

            log.debug("Successfully extracted published ontology detail from NKD: {}", ontologyIri);
            return Optional.of(ontologyDetail);

        } catch (QueryExceptionHTTP e) {
            log.error("SPARQL query error for ontology {}: {}", ontologyIri, e.getMessage());
            return Optional.empty();
        } catch (HttpException e) {
            log.error("HTTP error querying NKD SPARQL endpoint for ontology {}: {}", ontologyIri, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.error("Unexpected error fetching published ontology {}: {}", ontologyIri, e.getMessage(), e);
            return Optional.empty();
        }
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

        List<String> publishedResources = resourceIris.parallelStream()
                .filter(this::isConceptPublishedInNKD)
                .toList();

        log.info("Found {} published resources out of {} total resources", publishedResources.size(), resourceIris.size());
        return publishedResources;
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
            log.debug("Concept not found in NKD (SPARQL error): {}", conceptIri);
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
