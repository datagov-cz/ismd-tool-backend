package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.config.NkdConfig;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.query.NKDSPARQLConstructQuery;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.sparql.HttpSparqlExecutor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@Slf4j
public class NkdSparqlClient {

    /**
     * Endpoint label used for {@link com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException}
     * — surfaces in the global handler's Czech response when NKD is unreachable.
     */
    public static final String NKD_LABEL = "NKD";

    private final int maxConcurrentRequests;
    private final HttpSparqlExecutor executor;
    private final OntologyDetailExtractor detailExtractor;

    public NkdSparqlClient(NkdConfig config, OntologyDetailExtractor detailExtractor) {
        this.maxConcurrentRequests = config.getSparql().getMaxConcurrentRequests();
        this.executor = new HttpSparqlExecutor(
                NKD_LABEL,
                config.getSparql().getEndpoint(),
                config.getSparql().getTimeout());
        this.detailExtractor = detailExtractor;
    }

    public Optional<OntologyDetailModel.ConceptDetailModel> fetchPublishedConcept(String conceptIri) {
        log.debug("Fetching published concept from NKD: {}", conceptIri);
        String query = NKDSPARQLConstructQuery.buildConstructQuery(conceptIri);
        Optional<Model> resultModel = executor.construct("NKD concept fetch for " + conceptIri, query);
        if (resultModel.isEmpty()) {
            log.info("No data found for concept in NKD: {}", conceptIri);
            return Optional.empty();
        }
        log.debug("Fetched {} triples from NKD for concept: {}", resultModel.get().size(), conceptIri);
        Model processedModel = detailExtractor.applyOFNTransformations(resultModel.get());
        OntologyDetailModel.ConceptDetailModel conceptDetail =
                detailExtractor.extractConceptDetail(processedModel, conceptIri,
                        OntologyDetailExtractor.iriResolver());
        log.debug("Successfully extracted published concept detail from NKD: {}", conceptIri);
        return Optional.of(conceptDetail);
    }

    public Optional<OntologyDetailModel> fetchPublishedOntology(String ontologyIri) {
        return fetchPublishedOntologyRaw(ontologyIri).map(resultModel -> {
            Model processedModel = detailExtractor.applyOFNTransformations(resultModel);
            OntologyDetailModel ontologyDetail = detailExtractor.extractOntologyDetail(processedModel,
                    OntologyDetailExtractor.iriResolver());
            log.debug("Successfully extracted published ontology detail from NKD: {}", ontologyIri);
            return ontologyDetail;
        });
    }

    /**
     * Raw NKD ontology model — same CONSTRUCT as {@link #fetchPublishedOntology}
     * but returned before OFN extraction, for callers that need to serialize
     * the source RDF (e.g. download endpoint).
     */
    public Optional<Model> fetchPublishedOntologyRaw(String ontologyIri) {
        log.debug("Fetching raw NKD ontology model: {}", ontologyIri);
        String query = NKDSPARQLConstructQuery.buildOntologyConstructQuery(ontologyIri);
        Optional<Model> resultModel = executor.construct("NKD ontology fetch for " + ontologyIri, query);
        if (resultModel.isEmpty()) {
            log.info("No data found for ontology in NKD: {}", ontologyIri);
            return Optional.empty();
        }
        log.debug("Fetched {} triples from NKD for ontology: {}", resultModel.get().size(), ontologyIri);
        return resultModel;
    }

    public List<String> getPublishedResourcesList(List<String> resourceIris) {
        if (resourceIris == null || resourceIris.isEmpty()) {
            log.debug("No resource IRIs provided for NKD verification");
            return new ArrayList<>();
        }

        if (!executor.isConfigured()) {
            log.warn("NKD SPARQL endpoint not configured, skipping verification");
            return new ArrayList<>();
        }

        log.debug("Verifying {} resources against NKD", resourceIris.size());

        ExecutorService threadPool = Executors.newFixedThreadPool(
                Math.min(maxConcurrentRequests, resourceIris.size())
        );
        try {
            List<CompletableFuture<String>> futures = resourceIris.stream()
                    .map(iri -> CompletableFuture.supplyAsync(() ->
                            isConceptPublishedInNKD(iri) ? iri : null, threadPool))
                    .toList();

            List<String> publishedResources = futures.stream()
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .toList();

            log.info("Found {} published resources out of {} total resources",
                    publishedResources.size(), resourceIris.size());
            return publishedResources;
        } finally {
            threadPool.shutdown();
        }
    }

    /**
     * Executes a SPARQL SELECT query against the NKD endpoint.
     * Returns results as a list of maps (variable name → string value).
     */
    public List<Map<String, String>> executeSelect(String sparqlQuery) {
        if (!executor.isConfigured()) {
            log.warn("NKD SPARQL endpoint not configured");
            return List.of();
        }
        log.debug("Executing NKD SELECT query");
        List<Map<String, String>> results = executor.select("NKD generic SELECT", sparqlQuery, rs -> {
            List<Map<String, String>> rows = new ArrayList<>();
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
                rows.add(row);
            }
            return rows;
        });
        log.debug("NKD SELECT returned {} rows", results.size());
        return results;
    }

    public boolean isEndpointConfigured() {
        return executor.isConfigured();
    }

    private boolean isConceptPublishedInNKD(String conceptIri) {
        String query = NKDSPARQLConstructQuery.buildConstructQuery(conceptIri);
        boolean isPublished = executor
                .constructLenient("NKD publication check for " + conceptIri, query)
                .isPresent();
        if (isPublished) {
            log.debug("Concept is published in NKD: {}", conceptIri);
        }
        return isPublished;
    }
}
