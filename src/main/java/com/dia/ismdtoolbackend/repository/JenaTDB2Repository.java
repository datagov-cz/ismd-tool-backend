package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.config.DomainApplicationProfile;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import com.dia.ismdtoolbackend.utility.sparql.FusekiSparqlExecutor;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.rdfconnection.RDFConnectionRemote;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Repository;

import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.RelationType;

import java.net.http.HttpClient;
import java.util.*;
import java.util.concurrent.Semaphore;

import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE;
import static com.dia.constants.VocabularyConstants.TRIDA;
import static com.dia.constants.VocabularyConstants.VLASTNOST;
import static com.dia.constants.VocabularyConstants.VZTAH;

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
    private final Environment environment;
    private final FusekiSparqlExecutor executor;

    /**
     * Tracks whether the Fuseki text index is wired and answering queries.
     * Probed at startup (see {@link #init()}); read by {@link #searchByText(String, List, int)}
     * to short-circuit with an empty result set when the index is absent instead of
     * running a SPARQL that would return garbage (all resources in the graph).
     */
    private volatile boolean textIndexAvailable = false;

    @Value("${jena.fuseki.url}")
    private String fusekiEndpoint;

    public JenaTDB2Repository(HttpClient fusekiHttpClient, Semaphore fusekiSemaphore,
                              int fusekiSemaphoreTimeout, Environment environment) {
        this.fusekiHttpClient = fusekiHttpClient;
        this.fusekiSemaphore = fusekiSemaphore;
        this.fusekiSemaphoreTimeout = fusekiSemaphoreTimeout;
        this.environment = environment;
        // Use a method reference rather than a field-captured factory so test subclasses
        // that override createConnection() (see JenaTDB2RepositorySearchTest) get their
        // override invoked, and so reflection-set fusekiEndpoint changes flow through
        // (see JenaTDB2RepositorySemaphoreTest).
        this.executor = new FusekiSparqlExecutor(fusekiSemaphore, fusekiSemaphoreTimeout, this::createConnection);
    }

    /**
     * Test-only: bypass the startup probe and declare the text index available so
     * unit tests can exercise {@link #searchByText} without a live Fuseki. Production
     * code reaches this state via {@link #probeTextIndex()}.
     */
    void setTextIndexAvailableForTest(boolean available) {
        this.textIndexAvailable = available;
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


    @PostConstruct
    public void init() {
        log.info("Initializing Fuseki connection to: {}", fusekiEndpoint);

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
                    fusekiEndpoint, e.getMessage());
        }

        probeTextIndex();
    }

    /**
     * Probes whether Fuseki is wrapped with a Jena text (Lucene) dataset.
     * <p>
     * When the text module is missing, {@code ?s text:query "anything"} does not error —
     * Fuseki silently logs {@code TextQueryPF: No text index} and returns a result set with
     * one row where {@code ?s} is unbound. Downstream {@code OPTIONAL} joins against that
     * unbound variable then enumerate every subject in the graph, so the caller sees
     * plausible-looking but meaningless results. This probe detects the unbound-row shape
     * and flips {@link #textIndexAvailable}; {@link #searchByText} checks the flag before
     * issuing the real query.
     * <p>
     * Uses a sentinel token unlikely to appear in indexed labels so a working index returns
     * zero rows (not rows with {@code ?s} bound), making the three-way distinction reliable:
     * <ul>
     *   <li>zero rows → module wired, term not found (healthy)</li>
     *   <li>≥ 1 row with {@code ?s} bound → module wired, term found (also healthy)</li>
     *   <li>exactly 1 row with {@code ?s} unbound → module missing (broken)</li>
     * </ul>
     * In non-test profiles this throws if the index is missing, so a misconfigured
     * deployment fails fast at boot instead of silently corrupting search results.
     */
    private void probeTextIndex() {
        String probeQuery =
                "PREFIX text: <http://jena.apache.org/text#> " +
                "SELECT ?probeSubject WHERE { ?probeSubject text:query \"__ismd_text_index_probe__\" } LIMIT 1";

        // Distinguish three outcomes so we can fail fast on definite misconfiguration
        // without crashing on a transient Fuseki outage at boot:
        //   - probe ran, module wired   → textIndexAvailable = true
        //   - probe ran, module missing → throw in non-test profiles (definite misconfig)
        //   - probe couldn't run        → log warn, leave textIndexAvailable = false
        //                                 and let searchByText short-circuit until a
        //                                 future call succeeds (no boot crash on a
        //                                 transient network blip).
        boolean moduleDefinitelyMissing = false;
        boolean probeRan = false;
        try (RDFConnection conn = createConnection();
             QueryExecution qExec = conn.query(probeQuery)) {
            ResultSet rs = qExec.execSelect();
            probeRan = true;
            if (rs.hasNext()) {
                QuerySolution sol = rs.next();
                if (sol.get("probeSubject") == null) {
                    moduleDefinitelyMissing = true;
                }
            }
            textIndexAvailable = !moduleDefinitelyMissing;
        } catch (Exception e) {
            textIndexAvailable = false;
            log.warn("Text index probe could not run against Fuseki at {}: {}. " +
                    "Text search will be unavailable until Fuseki is reachable.",
                    fusekiEndpoint, e.getMessage());
        }

        if (textIndexAvailable) {
            log.info("Fuseki text index probe successful — text search is available.");
            return;
        }

        if (!probeRan) {
            // Couldn't reach Fuseki at all — already warned above. Don't escalate to a
            // boot-blocking exception, because a transient outage shouldn't kill the app.
            return;
        }

        String message = "Fuseki text index is not configured. Text search will return no results. " +
                "Ensure the Fuseki container is built from docker/fuseki/Dockerfile so the jena-text " +
                "module is loaded and the dataset is wrapped with a TextDataset (see fuseki-config.ttl).";

        if (DomainApplicationProfile.isActive(environment, DomainApplicationProfile.TEST)) {
            log.warn("{} (test profile — continuing without text search)", message);
        } else {
            log.error(message);
            throw new IllegalStateException(message);
        }
    }

    public String saveConcept(Resource conceptResource, String graphName) {
        if (graphName == null || graphName.trim().isEmpty()) {
            throw new IllegalArgumentException("Graph name is required - concepts cannot be saved to the default graph");
        }

        return executor.execute(
                "saving concept to graph " + graphName,
                "Nepodařilo se uložit pojem do databáze",
                conn -> {
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

        executor.executeVoid(
                "deleting concept " + conceptUri + " from graph " + graphName,
                "Nepodařilo se odstranit pojem z TDB2",
                conn -> {
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
                    conn.update(pss.toString());
                    log.info("Successfully deleted concept from TDB2 graph {}: {}",
                            graphName, conceptUri);
                });
    }

    public void deleteConceptsFromGraph(List<String> conceptUris, String graphName) {
        if (conceptUris == null || conceptUris.isEmpty()) {
            throw new IllegalArgumentException("Concept URI cannot be null or empty");
        }

        executor.executeVoid(
                "deleting " + conceptUris.size() + " concepts from graph " + graphName,
                "Nepodařilo se odstranit pojem z TDB2",
                conn -> {
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
                        conn.update(pss.toString());
                        log.info("Successfully deleted concept from TDB2 graph {}: {}",
                                graphName, conceptUri);
                    }
                });
    }

    public void saveOntologyModel(String graphName, Model model) {
        executor.executeVoid(
                "saving ontology model to graph " + graphName,
                "Nepodařilo se uložit slovník do databáze",
                conn -> {
                    log.info("=== SAVING ONTOLOGY ===");
                    log.info("Graph name: {}", graphName);
                    log.info("Model size: {} statements", model.size());
                    // PUT replaces the graph; LOAD only appends, which silently
                    // drops deletions made in-memory by the editor.
                    conn.put(graphName, model);
                    log.info("Successfully saved ontology model to TDB2 with graph name: {}", graphName);
                });
    }

    public void putOntologyModel(String graphName, Model model) {
        executor.executeVoid(
                "uploading ontology model to graph " + graphName,
                "Failed to upload to TDB2",
                conn -> {
                    log.info("=== UPLOADING ONTOLOGY ===");
                    log.info("Graph name: {}", graphName);
                    log.info("Model size: {} statements", model.size());
                    conn.put(graphName, model);
                });
    }

    public void deleteGraph(String graphName) {
        executor.executeVoid(
                "deleting graph " + graphName,
                "Failed to delete graph",
                conn -> {
                    log.info("=== DELETING ONTOLOGY ===");
                    log.info("Graph name: {}", graphName);
                    conn.delete(graphName);
                    log.info("Successfully deleted graph: {}", graphName);
                });
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

    public List<String> findRelatedConceptUris(String conceptUri, String graphName) {
        return executor.execute(
                "finding related concepts for " + conceptUri + " in graph " + graphName,
                "Failed to find related concepts",
                conn -> {
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
     * <p>
     * Searches across skos:prefLabel, skos:altLabel, dcterms:description, and skos:definition.
     * Also emits the resource's rdf:type URIs (pipe-separated) so callers can distinguish
     * concepts (skos:Concept) from ontologies / concept schemes (skos:ConceptScheme,
     * owl:Ontology) — both can carry searchable labels in the same graph.
     *
     * @param query             the search term
     * @param visibleGraphNames graphs the user has access to
     * @param limit             maximum number of results to return
     * @return list of result maps with keys: resourceIri, graphName, prefLabel, prefLabelLang,
     *         altLabel, description, definition, types
     */
    public List<Map<String, String>> searchByText(String query, List<String> visibleGraphNames, int limit) {
        return searchByText(query, visibleGraphNames, limit, null);
    }

    /**
     * Role-narrowed variant of {@link #searchByText(String, List, int)} — when
     * {@code conceptTypeFilter} is non-null, restricts results to resources that
     * carry the matching OFN role marker ({@code slovníky:třída/vlastnost/vztah}).
     * Used to back the {@code ?type=CLASS|PROPERTY|RELATIONSHIP} search filter.
     */
    public List<Map<String, String>> searchByText(String query, List<String> visibleGraphNames, int limit,
                                                   ConceptType conceptTypeFilter) {
        if (visibleGraphNames == null || visibleGraphNames.isEmpty()) {
            return List.of();
        }

        // Short-circuit when the text index is missing: the real SPARQL would otherwise
        // return garbage (every resource in each matched graph via the OPTIONAL joins).
        if (!textIndexAvailable) {
            log.warn("Skipping Fuseki text search — text index is not available. " +
                    "See startup logs for configuration guidance.");
            return List.of();
        }

        return executor.execute(
                "Fuseki text search for '" + query + "' across " + visibleGraphNames.size() + " graphs",
                "Failed to execute text search in Fuseki",
                conn -> {
                    StringBuilder valuesClause = new StringBuilder();
                    for (String graphName : visibleGraphNames) {
                        valuesClause.append("<").append(graphName).append("> ");
                    }
                    // Sanitize and build Lucene query term with wildcard for prefix matching.
                    // The value is embedded inside a SPARQL string literal ('...'), so after
                    // Lucene-escaping we must also escape the resulting backslashes for the
                    // outer SPARQL lexer — otherwise a Lucene escape like "\-" is rejected
                    // as an invalid SPARQL string escape sequence.
                    String sanitizedQuery = escapeForSparqlString(sanitizeLuceneQuery(query));
                    // Role narrowing: derived from static OFN/OWL constants — no injection surface.
                    // Accept either the OFN role IRI (ISMD-internal data carries both) or the
                    // matching OWL type (so externally-imported owl:Class/ObjectProperty/
                    // DatatypeProperty concepts also satisfy the filter).
                    String typeFilterClause = conceptTypeFilter == null ? ""
                            : "    FILTER(EXISTS { ?resource a <" + ofnRoleIri(conceptTypeFilter) + "> }"
                                    + " || EXISTS { ?resource a <" + owlTypeIri(conceptTypeFilter) + "> }) ";
                    // text:query inside GRAPH — requires Jena 5.4+ where the property
                    // function is correctly wired through the TextDataset assembler.
                    //
                    // GROUP_CONCAT collapses the multiple rdf:type triples each resource has
                    // (concepts carry skos:Concept + ofn:pojem + owl:DatatypeProperty/…;
                    // ontologies carry skos:ConceptScheme + owl:Ontology + ofn:slovník) into
                    // one row per resource so LIMIT counts resources, not type triples.
                    String sparql = "PREFIX text: <http://jena.apache.org/text#> " +
                            "PREFIX skos: <http://www.w3.org/2004/02/skos/core#> " +
                            "PREFIX dcterms: <http://purl.org/dc/terms/> " +
                            "SELECT ?resource ?g " +
                            "       (SAMPLE(?prefLabelS) AS ?prefLabel) " +
                            "       (SAMPLE(?prefLabelLangS) AS ?prefLabelLang) " +
                            "       (SAMPLE(?altLabelS) AS ?altLabel) " +
                            "       (SAMPLE(?descriptionS) AS ?description) " +
                            "       (SAMPLE(?definitionS) AS ?definition) " +
                            "       (GROUP_CONCAT(DISTINCT STR(?typeS); separator=\"|\") AS ?types) " +
                            "WHERE { " +
                            "  VALUES ?g { " + valuesClause + "} " +
                            "  GRAPH ?g { " +
                            "    ?resource text:query (skos:prefLabel skos:altLabel dcterms:description skos:definition '" + sanitizedQuery + "*') . " +
                            typeFilterClause +
                            "    OPTIONAL { ?resource skos:prefLabel ?prefLabelS . BIND(LANG(?prefLabelS) AS ?prefLabelLangS) } " +
                            "    OPTIONAL { ?resource skos:altLabel ?altLabelS } " +
                            "    OPTIONAL { ?resource dcterms:description ?descriptionS } " +
                            "    OPTIONAL { ?resource skos:definition ?definitionS } " +
                            "    OPTIONAL { ?resource a ?typeS } " +
                            "  } " +
                            "} GROUP BY ?resource ?g LIMIT " + limit;
                    log.debug("Fuseki text search SPARQL: {}", sparql);
                    List<Map<String, String>> results = new ArrayList<>();
                    try (QueryExecution qExec = conn.query(sparql)) {
                        ResultSet rs = qExec.execSelect();
                        while (rs.hasNext()) {
                            QuerySolution sol = rs.next();
                            Map<String, String> row = new HashMap<>();
                            row.put("resourceIri", sol.getResource("resource") != null ? sol.getResource("resource").getURI() : null);
                            row.put("graphName", sol.getResource("g") != null ? sol.getResource("g").getURI() : null);
                            if (sol.getLiteral("prefLabel") != null) row.put("prefLabel", sol.getLiteral("prefLabel").getString());
                            if (sol.getLiteral("prefLabelLang") != null) row.put("prefLabelLang", sol.getLiteral("prefLabelLang").getString());
                            if (sol.getLiteral("altLabel") != null) row.put("altLabel", sol.getLiteral("altLabel").getString());
                            if (sol.getLiteral("description") != null) row.put("description", sol.getLiteral("description").getString());
                            if (sol.getLiteral("definition") != null) row.put("definition", sol.getLiteral("definition").getString());
                            if (sol.getLiteral("types") != null) row.put("types", sol.getLiteral("types").getString());
                            results.add(row);
                        }
                    }
                    log.debug("Fuseki text search for '{}' across {} graphs returned {} results",
                            query, visibleGraphNames.size(), results.size());
                    return results;
                });
    }

    /**
     * Sanitizes user input for use in a Lucene query string.
     * <p>
     * The Fuseki text index uses StandardTokenizer, which already splits on
     * punctuation — so Lucene meta-characters in the user's query have no useful
     * semantic role. Instead of escaping them (which would leave stray literal
     * tokens like "\-" that match nothing), we replace them with spaces and let
     * the tokenizer handle the rest. Runs of whitespace are then collapsed, and
     * leading/trailing whitespace trimmed, so the caller can safely append a
     * prefix wildcard to the last token.
     * <p>
     * Lucene meta-characters handled: + - && || ! ( ) { } [ ] ^ " ~ * ? : \ /
     * We keep * out of the replacement since the caller appends it for prefix
     * matching — but any * embedded in the user's input is still stripped.
     */
    private static String sanitizeLuceneQuery(String input) {
        if (input == null) return "";
        return input
                .replaceAll("([+\\-!(){}\\[\\]^\"~*?:\\\\/]|&&|\\|\\|)", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase();
    }

    /**
     * Escapes a string so it can be safely embedded inside a SPARQL single-quoted
     * string literal. SPARQL only permits a fixed set of escape sequences in string
     * literals; any other backslash sequence is a lexer error. Backslashes must be
     * escaped first so we don't double-process the ones we introduce for quotes.
     */
    private static String escapeForSparqlString(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                .replace("'", "\\'");
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

        return executor.execute(
                "fetching concept labels for " + safeConceptIris.size() + " IRIs",
                "Failed to fetch concept labels from Fuseki",
                conn -> {
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
    }

    /**
     * Filters concept IRIs by relation types. Returns the subset of IRIs that participate
     * in at least one of the specified relation types.
     */
    public Set<String> filterByRelationTypes(List<String> conceptIris, List<RelationType> relationTypes) {
        if (conceptIris == null || conceptIris.isEmpty() || relationTypes == null || relationTypes.isEmpty()) {
            return Set.of();
        }

        return executor.execute(
                "filtering " + conceptIris.size() + " concepts by relation types " + relationTypes,
                "Failed to filter concepts by relation types",
                conn -> {
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

    private static String ofnRoleIri(ConceptType type) {
        return switch (type) {
            case TRIDA -> OFN_NAMESPACE + TRIDA;
            case VLASTNOST -> OFN_NAMESPACE + VLASTNOST;
            case VZTAH -> OFN_NAMESPACE + VZTAH;
        };
    }

    private static String owlTypeIri(ConceptType type) {
        return switch (type) {
            case TRIDA -> "http://www.w3.org/2002/07/owl#Class";
            case VLASTNOST -> "http://www.w3.org/2002/07/owl#DatatypeProperty";
            case VZTAH -> "http://www.w3.org/2002/07/owl#ObjectProperty";
        };
    }
}
