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


}
