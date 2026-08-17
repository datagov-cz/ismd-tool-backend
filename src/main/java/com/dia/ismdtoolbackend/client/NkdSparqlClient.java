package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.config.NkdConfig;
import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.query.NKDSPARQLConstructQuery;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import com.dia.ismdtoolbackend.utility.sparql.HttpSparqlExecutor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.query.QuerySolution;
import org.springframework.cache.annotation.Cacheable;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.util.*;

@Component
@Slf4j
public class NkdSparqlClient {

    /**
     * Endpoint label used for {@link com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException}
     * — surfaces in the global handler's Czech response when NKD is unreachable.
     */
    public static final String NKD_LABEL = "NKD";

    /**
     * Caffeine cache for NKD-published concept/ontology projections used by the deviation
     * checks (concept detail fires one of these per published concept). The cached value is
     * the NKD-published representation, which changes only when NKD republishes — NOT when a
     * local ISMD copy is edited — so local ISMD mutations intentionally do NOT evict this
     * cache; the cache's write TTL ({@code CacheConfig}) is the sole freshness mechanism.
     * Registered/sized in {@code CacheConfig}.
     */
    public static final String PUBLISHED_RESOURCE_CACHE = "nkdPublishedResource";

    private final HttpSparqlExecutor executor;
    private final OntologyDetailExtractor detailExtractor;

    /**
     * Self-reference through the Spring proxy so {@link #fetchPublishedConceptWithScheme}'s
     * {@code @Cacheable} is honoured when called from {@link #fetchPublishedConcept}. A plain
     * {@code this.} call would bypass the cache proxy, so the two keys would each pay their own
     * SPARQL round-trip instead of sharing one.
     */
    private final NkdSparqlClient self;

    public NkdSparqlClient(NkdConfig config, OntologyDetailExtractor detailExtractor,
                           @Qualifier("externalSparqlHttpClient") HttpClient externalSparqlHttpClient,
                           @Lazy NkdSparqlClient self) {
        this.executor = new HttpSparqlExecutor(
                NKD_LABEL,
                config.getSparql().getEndpoint(),
                config.getSparql().getTimeout(),
                externalSparqlHttpClient,
                config.getSparql().getMaxConcurrentRequests());
        this.detailExtractor = detailExtractor;
        // Falls back to this when constructed outside Spring (tests): no proxy means no cache to
        // re-enter, so a direct call is the correct behaviour rather than an NPE.
        this.self = self != null ? self : this;
    }

    @Cacheable(cacheNames = PUBLISHED_RESOURCE_CACHE, key = "'concept:' + #conceptIri")
    public Optional<OntologyDetailModel.ConceptDetailModel> fetchPublishedConcept(String conceptIri) {
        return self.fetchPublishedConceptWithScheme(conceptIri).map(PublishedConcept::detail);
    }

    /**
     * Concept fetch that also surfaces the {@code skos:inScheme} target
     */
    @Cacheable(cacheNames = PUBLISHED_RESOURCE_CACHE, key = "'conceptWithScheme:' + #conceptIri")
    public Optional<PublishedConcept> fetchPublishedConceptWithScheme(String conceptIri) {
        log.debug("Fetching published concept from NKD: {}", conceptIri);
        String query = NKDSPARQLConstructQuery.buildConstructQuery(conceptIri);
        Optional<Model> resultModel = executor.construct("NKD concept fetch for " + conceptIri, query);
        if (resultModel.isEmpty()) {
            log.info("No data found for concept in NKD: {}", conceptIri);
            return Optional.empty();
        }
        Model rawModel = resultModel.get();
        log.debug("Fetched {} triples from NKD for concept: {}", rawModel.size(), conceptIri);
        String inSchemeIri = extractInSchemeIri(rawModel, conceptIri);
        Model processedModel = detailExtractor.applyOFNTransformationsForNkd(rawModel);
        OntologyDetailModel.ConceptDetailModel conceptDetail =
                detailExtractor.extractConceptDetail(processedModel, conceptIri,
                        OntologyDetailExtractor.iriResolver());
        log.debug("Successfully extracted published concept detail from NKD: {} (inScheme={})",
                conceptIri, inSchemeIri);
        return Optional.of(new PublishedConcept(conceptDetail, inSchemeIri));
    }

    /**
     * Batched counterpart to {@link #fetchPublishedConcept}: one CONSTRUCT for every requested IRI,
     * then the same per-concept processing applied to each subject's slice. A concept absent from NKD
     * maps to {@link Optional#empty()}, exactly as the per-IRI path reports it.
     */
    public Map<String, Optional<PublishedConcept>> fetchPublishedConceptsBatched(List<String> conceptIris) {
        List<String> safeIris = conceptIris.stream()
                .filter(SparqlIriValidator::isSafeHttpIri)
                .distinct()
                .toList();
        Map<String, Optional<PublishedConcept>> out = new LinkedHashMap<>();
        conceptIris.forEach(iri -> out.put(iri, Optional.empty()));
        if (safeIris.isEmpty()) {
            return out;
        }

        log.debug("Batched NKD concept fetch for {} IRIs", safeIris.size());
        String query = NKDSPARQLConstructQuery.buildBatchedConstructQuery(safeIris);
        Optional<Model> resultModel = executor.construct(
                "NKD batched concept fetch (" + safeIris.size() + " IRIs)", query);
        if (resultModel.isEmpty()) {
            log.info("No data found for any of the {} batched concepts in NKD", safeIris.size());
            return out;
        }

        Model batched = resultModel.get();
        log.debug("Fetched {} triples from NKD for {} concepts", batched.size(), safeIris.size());
        for (String conceptIri : safeIris) {
            Model slice = sliceForSubject(batched, conceptIri);
            if (slice.isEmpty()) {
                log.debug("No data found for concept in NKD: {}", conceptIri);
                continue;
            }
            String inSchemeIri = extractInSchemeIri(slice, conceptIri);
            Model processedModel = detailExtractor.applyOFNTransformationsForNkd(slice);
            OntologyDetailModel.ConceptDetailModel conceptDetail =
                    detailExtractor.extractConceptDetail(processedModel, conceptIri,
                            OntologyDetailExtractor.iriResolver());
            out.put(conceptIri, Optional.of(new PublishedConcept(conceptDetail, inSchemeIri)));
        }
        return out;
    }

    /**
     * Scheme-scoped counterpart to {@link #fetchPublishedConceptsBatched}: derives every requested
     * concept from the ontology model the caller already fetched, with no further round-trip.
     *
     * <p>{@link NKDSPARQLConstructQuery#buildOntologyConstructQuery} returns the scheme's own triples
     * <em>and</em> every {@code skos:inScheme} member with blank-node expansion, so it is a strict
     * superset of the batched concept CONSTRUCT for concepts of that scheme. Per-concept output is
     * produced by the same slice → OFN → extract pipeline, so it matches the batched path field-for-field.
     *
     * <p>Only for concepts belonging to {@code ontologyModel}'s scheme. A concept absent from the
     * model maps to {@link Optional#empty()}, exactly as the batched path reports it.
     */
    public Map<String, Optional<PublishedConcept>> derivePublishedConceptsFromOntology(
            Model ontologyModel, List<String> conceptIris) {
        Map<String, Optional<PublishedConcept>> out = new LinkedHashMap<>();
        conceptIris.forEach(iri -> out.put(iri, Optional.empty()));
        if (ontologyModel == null || ontologyModel.isEmpty()) {
            return out;
        }
        for (String conceptIri : conceptIris.stream().distinct().toList()) {
            if (!SparqlIriValidator.isSafeHttpIri(conceptIri)) {
                continue;
            }
            Model slice = sliceForSubject(ontologyModel, conceptIri);
            if (slice.isEmpty()) {
                log.debug("Concept not present in NKD ontology model: {}", conceptIri);
                continue;
            }
            String inSchemeIri = extractInSchemeIri(slice, conceptIri);
            Model processedModel = detailExtractor.applyOFNTransformationsForNkd(slice);
            OntologyDetailModel.ConceptDetailModel conceptDetail =
                    detailExtractor.extractConceptDetail(processedModel, conceptIri,
                            OntologyDetailExtractor.iriResolver());
            out.put(conceptIri, Optional.of(new PublishedConcept(conceptDetail, inSchemeIri)));
        }
        return out;
    }

    /**
     * The triples of one concept out of a batched CONSTRUCT: its own statements plus everything
     * reachable through blank-node objects (the batched query's second UNION branch).
     */
    private static Model sliceForSubject(Model batched, String conceptIri) {
        Model slice = ModelFactory.createDefaultModel();
        slice.setNsPrefixes(batched.getNsPrefixMap());
        Deque<Resource> pending = new ArrayDeque<>();
        Set<Resource> visited = new HashSet<>();
        pending.add(batched.getResource(conceptIri));
        while (!pending.isEmpty()) {
            Resource subject = pending.poll();
            if (!visited.add(subject)) {
                continue;
            }
            StmtIterator stmts = batched.listStatements(subject, null, (RDFNode) null);
            try {
                while (stmts.hasNext()) {
                    Statement stmt = stmts.next();
                    slice.add(stmt);
                    RDFNode object = stmt.getObject();
                    // Only blank objects are expanded; following URI objects would pull in
                    // neighbouring concepts that the single-IRI query never returns.
                    if (object.isAnon()) {
                        pending.add(object.asResource());
                    }
                }
            } finally {
                stmts.close();
            }
        }
        return slice;
    }

    private static String extractInSchemeIri(Model rawModel, String conceptIri) {
        Resource concept = rawModel.getResource(conceptIri);
        Property inScheme = rawModel.createProperty("http://www.w3.org/2004/02/skos/core#inScheme");
        StmtIterator stmts = rawModel.listStatements(concept, inScheme, (RDFNode) null);
        try {
            while (stmts.hasNext()) {
                Statement stmt = stmts.next();
                RDFNode object = stmt.getObject();
                if (object.isURIResource()) {
                    return object.asResource().getURI();
                }
            }
        } finally {
            stmts.close();
        }
        return null;
    }

    /**
     * Carrier for concept detail + the skos:inScheme target IRI. Lives at client
     * level so the service layer doesn't need to re-parse the raw NKD model.
     */
    public record PublishedConcept(OntologyDetailModel.ConceptDetailModel detail, String ontologyIri) {}

    /**
     * Raw NKD concept model — the same single-concept CONSTRUCT as
     * {@link #fetchPublishedConceptWithScheme} but returned <em>before</em> OFN extraction, for
     * callers that need the source triples themselves (the snapshot materializer, which copies the
     * external concept's RDF into the owner graph).
     *
     * <p>Bounded: {@link NKDSPARQLConstructQuery#buildConstructQuery} pulls only the one concept's
     * triples plus one level of blank-node expansion — never a whole-ontology tree.
     *
     * <p>Lenient: an NKD outage / absent concept degrades to {@link Optional#empty()} rather than
     * throwing, so the best-effort snapshot fetch never rolls back the owning edit.
     */
    public Optional<Model> fetchPublishedConceptRaw(String conceptIri) {
        if (!SparqlIriValidator.isSafeHttpIri(conceptIri)) {
            log.warn("Refusing raw NKD concept fetch for unsafe IRI: {}", conceptIri);
            return Optional.empty();
        }
        log.debug("Fetching raw NKD concept model: {}", conceptIri);
        String query = NKDSPARQLConstructQuery.buildConstructQuery(conceptIri);
        Optional<Model> resultModel =
                executor.constructLenient("NKD raw concept fetch for " + conceptIri, query);
        if (resultModel.isEmpty()) {
            log.info("No data found for concept in NKD (raw): {}", conceptIri);
            return Optional.empty();
        }
        log.debug("Fetched {} raw triples from NKD for concept: {}", resultModel.get().size(), conceptIri);
        return resultModel;
    }

    @Cacheable(cacheNames = PUBLISHED_RESOURCE_CACHE, key = "'ontology:' + #ontologyIri")
    public Optional<OntologyDetailModel> fetchPublishedOntology(String ontologyIri) {
        return self.fetchPublishedOntologyRaw(ontologyIri).map(resultModel -> {
            Model processedModel = detailExtractor.applyOFNTransformationsForNkd(resultModel);
            return detailExtractor.extractOntologyDetail(processedModel,
                    OntologyDetailExtractor.iriResolver());
        });
    }

    /**
     * Raw NKD ontology model — same CONSTRUCT as {@link #fetchPublishedOntology}
     * but returned before OFN extraction, for callers that need to serialize
     * the source RDF (e.g. download endpoint).
     *
     * <p>Cached under its own key so the ontology deviation check and
     * {@link #derivePublishedConceptsFromOntology} share a single round-trip: the CONSTRUCT already
     * carries every in-scheme concept, so the concept side needs no query of its own. Callers must
     * treat the returned model as read-only — it is the shared cached instance.
     */
    @Cacheable(cacheNames = PUBLISHED_RESOURCE_CACHE, key = "'ontologyRaw:' + #ontologyIri")
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

    /**
     * Batched concept-reference resolver against NKD. For each input IRI present
     * on the remote endpoint, returns its {@code skos:inScheme} (ontology IRI)
     * and the scheme's multilingual {@code dcterms:description}. IRIs absent from
     * NKD are absent from the returned map.
     *
     * <p>One CONSTRUCT for the whole batch = one HTTP round-trip. Replaces the
     * naive per-IRI fan-out which would issue N {@link #buildConstructQuery}
     * calls (each pulling 100–300 triples of full concept graph) and serialize
     * on the 4-permit thread pool.
     *
     * <p>Lenient mode: an NKD outage degrades to an empty map rather than
     * failing the whole resolve request.
     */
    public Map<String, ResolvedConceptDto> fetchConceptResolutions(List<String> conceptIris) {
        if (conceptIris == null || conceptIris.isEmpty()) {
            return Map.of();
        }
        if (!executor.isConfigured()) {
            log.warn("NKD endpoint not configured, skipping concept resolution batch");
            return Map.of();
        }
        List<String> safeConceptIris = conceptIris.stream()
                .filter(SparqlIriValidator::isSafeHttpIri)
                .toList();
        if (safeConceptIris.size() != conceptIris.size()) {
            log.warn("Dropped {} invalid concept IRI(s) from NKD fetchConceptResolutions",
                    conceptIris.size() - safeConceptIris.size());
        }
        if (safeConceptIris.isEmpty()) {
            return Map.of();
        }

        String query = NKDSPARQLConstructQuery.buildResolutionConstructQuery(safeConceptIris);
        Optional<Model> result = executor.constructLenient(
                "NKD concept resolution batch (" + safeConceptIris.size() + " IRIs)", query);
        if (result.isEmpty()) {
            log.debug("NKD returned no resolutions for {} requested IRI(s)", safeConceptIris.size());
            return Map.of();
        }
        Map<String, ResolvedConceptDto> resolutions =
                JenaTDB2Repository.projectResolutions(result.get(), SearchSource.NKD);
        log.debug("Resolved {} of {} requested concept IRI(s) against NKD",
                resolutions.size(), safeConceptIris.size());
        return resolutions;
    }

    /**
     * Returns which of {@code resourceIris} exist as published resources in NKD, in a single batched CONSTRUCT
     * rather than one round-trip per IRI — so wall-time is independent of the batch size.
     * Best-effort: an NKD outage returns an empty list, never an exception.
     */
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

        String query = NKDSPARQLConstructQuery.buildPublicationCheckQuery(resourceIris);
        Optional<Model> result = executor.constructLenient(
                "NKD publication check for " + resourceIris.size() + " resource(s)", query);
        if (result.isEmpty()) {
            log.info("Found 0 published resources out of {} total resources", resourceIris.size());
            return new ArrayList<>();
        }

        // Intersect the requested IRIs with the subjects NKD returned, so a lenient result
        // can only ever confirm IRIs the caller actually asked about.
        Set<String> requested = new HashSet<>(resourceIris);
        List<String> publishedResources = result.get().listSubjects().toList().stream()
                .filter(Resource::isURIResource)
                .map(Resource::getURI)
                .filter(requested::contains)
                .distinct()
                .toList();

        log.info("Found {} published resources out of {} total resources",
                publishedResources.size(), resourceIris.size());
        return publishedResources;
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
}
