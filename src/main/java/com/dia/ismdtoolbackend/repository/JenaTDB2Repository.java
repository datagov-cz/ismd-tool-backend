package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.config.FusekiConnectionConfig;
import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.RelationType;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import com.dia.ismdtoolbackend.utility.sparql.FusekiSparqlExecutor;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdfconnection.RDFConnection;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Repository for managing RDF resources in Fuseki TDB2 via HTTP connection.
 * Holds the graph-level reads; writes, concept-level reads and text search are delegated to
 * {@link FusekiGraphWriter}, {@link FusekiConceptQueries} and {@link FusekiTextSearch}.
 */
@Repository
@Slf4j
public class JenaTDB2Repository {

    /**
     * The single source of truth for "is this concept OWNED by the scheme it declares".
     * A concept is owned if it carries {@code skos:inScheme ?scheme} AND its IRI is a
     * string prefix-match of that scheme. This is the same gate the live resolver
     * ({@link #fetchConceptResolutions}) and the upload write-gate
     * ({@code OFNTypeNormalizer.isOwnedConcept}) use; the PG↔TDB2 reconciler MUST use the
     * exact same predicate or it would invent (and, with auto-repair, delete) phantom
     * orphans. Bind {@code ?concept} before interpolating. See
     * {@code pg_tdb2_dual_write_consistency} / the reconciler plan §6.
     */
    public static final String OWNED_CONCEPT_PATTERN =
            " ?concept skos:inScheme ?scheme . FILTER(STRSTARTS(STR(?concept), STR(?scheme))) ";

    private final FusekiConnectionConfig connectionConfig;
    private final FusekiSparqlExecutor executor;
    private final FusekiGraphWriter graphWriter;
    private final FusekiConceptQueries conceptQueries;
    private final FusekiTextSearch textSearch;

    public JenaTDB2Repository(FusekiConnectionConfig connectionConfig, Environment environment) {
        this.connectionConfig = connectionConfig;
        this.executor = new FusekiSparqlExecutor(connectionConfig.getFusekiSemaphore(),
                connectionConfig.getFusekiSemaphoreTimeout(), this::createConnection);
        this.graphWriter = new FusekiGraphWriter(executor);
        this.conceptQueries = new FusekiConceptQueries(executor);
        this.textSearch = new FusekiTextSearch(executor, this::createConnection, environment);
    }

    public boolean isTextIndexAvailable() {
        return textSearch.isAvailable();
    }

    /** Test-only: declares the text index available without running the startup probe. */
    void setTextIndexAvailableForTest(boolean available) {
        textSearch.setAvailable(available);
    }

    // protected so test subclasses can override the connection source.
    protected RDFConnection createConnection() {
        return connectionConfig.createConnection();
    }

    @PostConstruct
    public void init() {
        log.info("Initializing Fuseki connection to: {}", connectionConfig.getFusekiEndpoint());

        // The connection ASK is allowed to fail silently — Fuseki may legitimately be
        // unreachable in test environments and we don't want that to block startup.
        // The text-index probe, on the other hand, MUST be allowed to throw in
        // non-test profiles so a misconfigured deployment fails fast at boot rather
        // than silently corrupting search results. Keep its call OUTSIDE this catch.
        try (RDFConnection conn = createConnection()) {
            boolean connected = conn.queryAsk("ASK { ?s ?p ?o }");
            log.info("Fuseki connection successful. Dataset has data: {}", connected);
        } catch (Exception e) {
            log.warn("Failed to connect to Fuseki at: {}. This is expected in test environments. Error: {}",
                    connectionConfig.getFusekiEndpoint(), e.getMessage());
        }

        textSearch.probe(connectionConfig.getFusekiEndpoint());
    }

    public boolean conceptNotFoundInGraph(String conceptUri, String graphName) {
        if (conceptUri == null || conceptUri.trim().isEmpty()) {
            return true;
        }
        return executor.execute(
                "checking concept existence in graph " + graphName + " for " + conceptUri,
                "Nepodařilo se ověřit existenci pojmu v grafu",
                conn -> conceptNotFoundInGraph(conn, conceptUri, graphName));
    }

    static boolean conceptNotFoundInGraph(RDFConnection conn, String conceptUri, String graphName) {
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

    public Model fetchGraph(String graphName) {
        return executor.execute(
                "fetching graph " + graphName,
                "Failed to fetch graph",
                conn -> {
                    Model model = conn.fetch(graphName);
                    log.debug("Fetched graph '{}' with {} statements", graphName, model.size());
                    return model;
                });
    }

    public boolean graphHasData(String graphName) {
        return executor.execute(
                "checking graph " + graphName,
                "Failed to check graph",
                conn -> {
                    ParameterizedSparqlString pss = new ParameterizedSparqlString();
                    pss.setCommandText("ASK { GRAPH ?g { ?s ?p ?o } }");
                    pss.setIri("g", graphName);
                    boolean hasData = conn.queryAsk(pss.toString());
                    log.debug("Graph '{}' has data: {}", graphName, hasData);
                    return hasData;
                });
    }

    /**
     * Enumerates every named graph that holds at least one triple. Used by the PG↔TDB2
     * consistency reconciler to discover what graphs exist in Fuseki before comparing
     * against the Postgres ontology rows. Like all reads here, this THROWS
     * {@code JenaTDB2Exception} on a Fuseki connection failure (never silently returns an
     * empty list), so the reconciler can abort a run rather than mistake an outage for
     * "TDB2 is empty".
     */
    public List<String> listNamedGraphs() {
        return executor.execute(
                "listing named graphs",
                "Failed to list named graphs",
                conn -> {
                    List<String> graphs = new ArrayList<>();
                    String sparql = "SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }";
                    try (QueryExecution qExec = conn.query(sparql)) {
                        ResultSet rs = qExec.execSelect();
                        while (rs.hasNext()) {
                            Resource g = rs.next().getResource("g");
                            if (g != null && g.isURIResource()) {
                                graphs.add(g.getURI());
                            }
                        }
                    }
                    log.debug("Enumerated {} named graph(s) in Fuseki", graphs.size());
                    return graphs;
                });
    }

    /**
     * Returns the IRIs of concepts OWNED by {@code graphName} — i.e. subjects carrying
     * {@code skos:inScheme ?scheme} with {@code STRSTARTS(conceptIri, scheme)} — within that
     * named graph. Uses the shared {@link #OWNED_CONCEPT_PATTERN} so the reconciler's notion
     * of "owned" is byte-identical to the resolver's.
     *
     * <p>By construction this EXCLUDES (a) referenced/external concepts, which appear only as
     * triple objects and never carry an owning {@code inScheme} (e.g. an NKD {@code adresa}
     * reference), and (b) "excluded" concepts the user declined to normalize, which have no
     * {@code inScheme} at all. So a reconciler RDF→PG sweep over this set never false-flags
     * either as an orphan. THROWS on Fuseki failure.
     */
    public List<String> listOwnedConceptIrisInGraph(String graphName) {
        if (!SparqlIriValidator.isSafeHttpIri(graphName)) {
            log.warn("Skipping listOwnedConceptIrisInGraph for unsafe graph IRI");
            return List.of();
        }
        return executor.execute(
                "listing owned concept IRIs in graph " + graphName,
                "Failed to list owned concepts in graph",
                conn -> {
                    ParameterizedSparqlString pss = new ParameterizedSparqlString();
                    pss.append("PREFIX skos: <http://www.w3.org/2004/02/skos/core#> ");
                    pss.append("SELECT DISTINCT ?concept WHERE { GRAPH ?g { ");
                    pss.append(OWNED_CONCEPT_PATTERN);
                    pss.append("} }");
                    pss.setIri("g", graphName);
                    List<String> iris = new ArrayList<>();
                    try (QueryExecution qExec = conn.query(pss.asQuery())) {
                        ResultSet rs = qExec.execSelect();
                        while (rs.hasNext()) {
                            Resource c = rs.next().getResource("concept");
                            if (c != null && c.isURIResource()) {
                                iris.add(c.getURI());
                            }
                        }
                    }
                    log.debug("Graph '{}' has {} owned concept subject(s)", graphName, iris.size());
                    return iris;
                });
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

        return executor.execute(
                "fetching metadata properties for " + safeGraphNames.size() + " graphs",
                "Failed to fetch metadata properties",
                conn -> {
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
    }

    public Model fetchMetadataProperties(String graphName) {
        return fetchMetadataProperties(List.of(graphName));
    }

    // Writes, delegated to FusekiGraphWriter.

    public void deleteConceptsFromGraph(List<String> conceptUris, String graphName) {
        graphWriter.deleteConceptsFromGraph(conceptUris, graphName);
    }

    public void saveOntologyModel(String graphName, Model model) {
        graphWriter.saveOntologyModel(graphName, model);
    }

    public void putOntologyModel(String graphName, Model model) {
        graphWriter.putOntologyModel(graphName, model);
    }

    public void applyConceptDelta(String conceptIri, String graphName, Model removeModel, Model addModel) {
        graphWriter.applyConceptDelta(conceptIri, graphName, removeModel, addModel);
    }

    public void deleteGraph(String graphName) {
        graphWriter.deleteGraph(graphName);
    }

    // Concept-level reads, delegated to FusekiConceptQueries.

    public Model fetchExternalDomainMembers(String conceptIri) {
        return conceptQueries.fetchExternalDomainMembers(conceptIri);
    }

    public Map<String, ForeignMemberCount> countExternalDomainMembers(String ownGraphName,
                                                                     Collection<String> conceptIris) {
        return conceptQueries.countExternalDomainMembers(ownGraphName, conceptIris);
    }

    public List<String> findRelatedConceptUris(String conceptUri, String graphName) {
        return conceptQueries.findRelatedConceptUris(conceptUri, graphName);
    }

    public Model fetchConceptLabels(List<String> conceptIris) {
        return conceptQueries.fetchConceptLabels(conceptIris);
    }

    public ConceptHierarchyLinks fetchConceptHierarchy(String conceptIri, String graphName) {
        return conceptQueries.fetchConceptHierarchy(conceptIri, graphName);
    }

    public Map<String, ResolvedConceptDto> fetchConceptResolutions(List<String> conceptIris) {
        return conceptQueries.fetchConceptResolutions(conceptIris);
    }

    // Text search, delegated to FusekiTextSearch.

    public List<Map<String, String>> searchByText(String query, List<String> visibleGraphNames, int limit) {
        return textSearch.searchByText(query, visibleGraphNames, limit);
    }

    public List<Map<String, String>> searchByText(String query, List<String> visibleGraphNames, int limit,
                                                   ConceptType conceptTypeFilter) {
        return textSearch.searchByText(query, visibleGraphNames, limit, conceptTypeFilter);
    }

    public Set<String> filterByRelationTypes(List<String> conceptIris, List<RelationType> relationTypes) {
        return textSearch.filterByRelationTypes(conceptIris, relationTypes);
    }
}
