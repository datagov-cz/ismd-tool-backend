package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.config.DomainApplicationProfile;
import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import com.dia.ismdtoolbackend.utility.sparql.FusekiSparqlExecutor;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.rdfconnection.RDFConnectionRemote;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.modify.request.QuadDataAcc;
import org.apache.jena.sparql.modify.request.UpdateDataDelete;
import org.apache.jena.sparql.modify.request.UpdateDataInsert;
import org.apache.jena.update.UpdateFactory;
import org.apache.jena.update.UpdateRequest;
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

    private static final String OWL_OBJECT_PROPERTY = "http://www.w3.org/2002/07/owl#ObjectProperty";

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

    // protected so test subclasses in other packages (e.g. an in-memory-dataset-backed repo for the
    // outbox relay tests) can override the connection source.
    protected RDFConnection createConnection() {
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

        if (DomainApplicationProfile.isActive(environment, DomainApplicationProfile.JUNIT)) {
            log.warn("{} (junit profile — continuing without text search)", message);
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

    /**
     * Applies a concept-scoped delta to a graph: remove the {@code removeModel} triples, add the
     * {@code addModel} triples, as ONE SPARQL update request — Fuseki's single-request atomic unit.
     * Used by the outbox relay to apply an {@code UPSERT_CONCEPT} row. {@code conceptIri} is the
     * concept the delta is rooted at (the outbox row's aggregate).
     *
     * <p><b>Two strategies, picked by whether the delta touches blank nodes:</b>
     * <ul>
     *   <li><b>No blank nodes (the common case — labels, definitions, field edits, renames):</b>
     *       identity-based {@code DELETE DATA; INSERT DATA} of exactly the changed triples. Minimal
     *       diff, so concurrent deltas to <em>different</em> concepts in one graph never collide.</li>
     *   <li><b>Blank nodes present (digital objects, code lists):</b> blank nodes can't be matched by
     *       identity across the serialize/parse boundary ({@code DELETE DATA} treats a bnode label as
     *       fresh, matching nothing). So we (a) {@code DELETE DATA} the non-blank removed triples,
     *       (b) {@code DELETE} the concept's one-hop blank substructures by PATTERN, rooted at the
     *       concept, and (c) {@code INSERT DATA} the new model (fresh bnodes are fine on insert). The
     *       pattern delete is bounded to {@code <concept> ?p ?bn . ?bn ?q ?o} with {@code isBlank(?bn)}
     *       — one hop, blank-only — so it can never reach another concept's data. (Verified: the
     *       editor/creator only ever produce one-level blank structures; no lists/nesting.)</li>
     * </ul>
     *
     * <p><b>Idempotent on re-apply</b> in both strategies: identity DELETE/INSERT DATA re-runs are
     * no-ops; the pattern path deletes ALL the concept's blank children before re-inserting, so a
     * double-apply nets exactly one copy (this is why the blank path deletes by pattern, not by the
     * just-inserted bnode identity).
     */
    public void applyConceptDelta(String conceptIri, String graphName, Model removeModel, Model addModel) {
        Node graph = NodeFactory.createURI(graphName);
        boolean removeHasBlank = hasBlankNode(removeModel);
        boolean addHasBlank = hasBlankNode(addModel);

        UpdateRequest request = new UpdateRequest();

        // (a) Always: identity-delete the non-blank removed triples.
        QuadDataAcc removeNonBlank = quadDataNonBlank(graph, removeModel);
        if (!removeNonBlank.getQuads().isEmpty()) {
            request.add(new UpdateDataDelete(removeNonBlank));
        }
        // (b) If either side involves blank nodes, pattern-delete the concept's one-hop blank
        //     substructures (clears the OLD bnode structures that DATA-delete can't match, AND any
        //     previously-inserted copy on a re-apply).
        if (removeHasBlank || addHasBlank) {
            blankSubstructureDelete(graphName, conceptIri).getOperations().forEach(request::add);
        }
        // (c) Insert the additions (blank nodes minted fresh — valid on insert).
        if (addModel != null && !addModel.isEmpty()) {
            request.add(new UpdateDataInsert(quadDataAll(graph, addModel)));
        }

        if (request.getOperations().isEmpty()) {
            log.debug("applyConceptDelta no-op (empty delta) for concept {} in graph {}", conceptIri, graphName);
            return;
        }
        executor.executeVoid(
                "applying concept delta for " + conceptIri + " to graph " + graphName,
                "Nepodařilo se aplikovat změnu pojmu do TDB2",
                conn -> conn.update(request));
    }

    private boolean hasBlankNode(Model model) {
        if (model == null || model.isEmpty()) {
            return false;
        }
        StmtIterator it = model.listStatements();
        try {
            while (it.hasNext()) {
                Statement stmt = it.next();
                if (stmt.getSubject().isAnon() || stmt.getObject().isAnon()) {
                    return true;
                }
            }
        } finally {
            it.close();
        }
        return false;
    }

    /** Quads for the model's NON-blank triples only (blank ones are handled by the pattern delete). */
    private QuadDataAcc quadDataNonBlank(Node graph, Model model) {
        QuadDataAcc acc = new QuadDataAcc();
        if (model == null) {
            return acc;
        }
        StmtIterator it = model.listStatements();
        try {
            while (it.hasNext()) {
                Statement stmt = it.next();
                if (stmt.getSubject().isAnon() || stmt.getObject().isAnon()) {
                    continue;
                }
                acc.addQuad(Quad.create(graph, stmt.getSubject().asNode(),
                        stmt.getPredicate().asNode(), stmt.getObject().asNode()));
            }
        } finally {
            it.close();
        }
        return acc;
    }

    /** Quads for ALL triples in the model (used for INSERT DATA, where blank nodes are valid). */
    private QuadDataAcc quadDataAll(Node graph, Model model) {
        QuadDataAcc acc = new QuadDataAcc();
        StmtIterator it = model.listStatements();
        try {
            while (it.hasNext()) {
                Statement stmt = it.next();
                acc.addQuad(Quad.create(graph, stmt.getSubject().asNode(),
                        stmt.getPredicate().asNode(), stmt.getObject().asNode()));
            }
        } finally {
            it.close();
        }
        return acc;
    }

    /**
     * A {@code DELETE { GRAPH g { ?bn ?q ?o } } WHERE { GRAPH g { &lt;concept&gt; ?p ?bn . ?bn ?q ?o .
     * FILTER isBlank(?bn) } }} — removes the concept's one-hop blank substructures (and the edges to
     * them) without naming the blank nodes. Bounded to nodes directly hung off the concept, so it
     * never reaches another concept.
     */
    private UpdateRequest blankSubstructureDelete(String graphName, String conceptIri) {
        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText(
                "DELETE { GRAPH ?g { ?concept ?p ?bn . ?bn ?q ?o } } " +
                "WHERE  { GRAPH ?g { ?concept ?p ?bn . ?bn ?q ?o . FILTER(isBlank(?bn)) } }");
        pss.setIri("g", graphName);
        pss.setIri("concept", conceptIri);
        return UpdateFactory.create(pss.toString());
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

    /**
     * Returns the triples describing every property/relationship across ALL local
     * graphs whose {@code rdfs:domain} <em>or</em> {@code rdfs:range} is {@code conceptIri}
     * — i.e. the members that point <em>at</em> this concept. Merged into the concept's
     * own graph by the detail pipeline so the class-detail read
     * ({@link com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor#extractConceptPropertiesFromModel})
     * sees members that live in a different vocabulary graph than the class.
     *
     * <p>The class-detail read fetches only the class's own named graph, so a
     * cross-vocabulary {@code member rdfs:domain class} (or {@code rdfs:range}) edge
     * (the member living in graph A, the class in graph B) was invisible from the
     * class side even though the member's own detail showed it. This closes that
     * asymmetry. Range is matched too: a vztah whose range is this class would
     * otherwise never surface on the class detail.
     *
     * <p>Returns exactly the triples the extractor reads off each member resource:
     * {@code rdf:type}, {@code skos:prefLabel}, {@code rdfs:domain}, {@code rdfs:range}.
     * The member's actual domain/range edges are constructed (not the match edge), so a
     * range-matched member still carries its own domain, and vice versa.
     */
    public Model fetchExternalDomainMembers(String conceptIri) {
        if (!SparqlIriValidator.isSafeHttpIri(conceptIri)) {
            log.warn("Skipping fetchExternalDomainMembers for unsafe concept IRI");
            return ModelFactory.createDefaultModel();
        }
        return executor.execute(
                "fetching cross-graph domain/range members for " + conceptIri,
                "Failed to fetch cross-graph domain/range members",
                conn -> {
                    ParameterizedSparqlString pss = new ParameterizedSparqlString();
                    pss.append("PREFIX rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> ");
                    pss.append("PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> ");
                    pss.append("PREFIX skos: <http://www.w3.org/2004/02/skos/core#> ");
                    pss.append("CONSTRUCT { ");
                    pss.append("  ?member rdf:type ?type . ");
                    pss.append("  ?member rdfs:domain ?domain . ");
                    pss.append("  ?member skos:prefLabel ?label . ");
                    pss.append("  ?member rdfs:range ?range . ");
                    pss.append("} WHERE { GRAPH ?g { ");
                    pss.append("  { ?member rdfs:domain ?concept } UNION { ?member rdfs:range ?concept } ");
                    pss.append("  ?member rdf:type ?type . ");
                    pss.append("  OPTIONAL { ?member skos:prefLabel ?label } ");
                    pss.append("  OPTIONAL { ?member rdfs:domain ?domain } ");
                    pss.append("  OPTIONAL { ?member rdfs:range ?range } ");
                    pss.append("} }");
                    pss.setIri("concept", conceptIri);
                    try (QueryExecution qExec = conn.query(pss.asQuery())) {
                        Model result = qExec.execConstruct();
                        log.debug("Fetched {} cross-graph domain/range-member triples for {}",
                                result.size(), conceptIri);
                        return result;
                    }
                });
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
     * Batched ISMD concept-reference resolver. For each input IRI present in the
     * local store, returns its {@code skos:inScheme} (ontology IRI) and the
     * scheme's multilingual {@code dcterms:description}. IRIs not in any local
     * graph are simply absent from the returned map — the caller falls back to
     * NKD (or to the plain IRI on the FE).
     *
     * <p>One CONSTRUCT for the whole batch = one Fuseki semaphore permit. With
     * only 10 permits total, per-IRI parallelism would serialize and quickly
     * saturate the pool; batching keeps the worst-case path cheap.
     *
     * <p>Prefix-filtered: only matches the scheme whose IRI is a string prefix
     * of the concept IRI. Local graphs sometimes contain stray
     * {@code skos:inScheme} triples that point a foreign concept at the wrong
     * scheme — without this filter the union-graph query returns those
     * non-deterministically.
     */
    public Map<String, ResolvedConceptDto> fetchConceptResolutions(List<String> conceptIris) {
        if (conceptIris == null || conceptIris.isEmpty()) {
            return Map.of();
        }

        List<String> safeConceptIris = conceptIris.stream()
                .filter(SparqlIriValidator::isSafeHttpIri)
                .toList();
        if (safeConceptIris.size() != conceptIris.size()) {
            log.warn("Dropped {} invalid concept IRI(s) from fetchConceptResolutions",
                    conceptIris.size() - safeConceptIris.size());
        }
        if (safeConceptIris.isEmpty()) {
            return Map.of();
        }

        return executor.execute(
                "fetching concept resolutions for " + safeConceptIris.size() + " IRIs",
                "Failed to fetch concept resolutions from Fuseki",
                conn -> {
                    ParameterizedSparqlString pss = new ParameterizedSparqlString();
                    pss.append("PREFIX skos: <http://www.w3.org/2004/02/skos/core#> ");
                    pss.append("PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> ");
                    pss.append("PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> ");
                    pss.append("CONSTRUCT { ");
                    pss.append("  ?concept skos:inScheme ?scheme . ");
                    pss.append("  ?concept skos:prefLabel ?conceptLabel . ");
                    pss.append("  ?concept rdf:type ?type . ");
                    pss.append("  ?concept rdfs:domain ?domain . ");
                    pss.append("  ?concept rdfs:range ?range . ");
                    pss.append("  ?scheme skos:prefLabel ?schemeLabel . ");
                    pss.append("} WHERE { VALUES ?concept { ");
                    for (String iri : safeConceptIris) {
                        pss.appendIri(iri);
                        pss.append(" ");
                    }
                    pss.append("} GRAPH ?g { ");
                    pss.append(OWNED_CONCEPT_PATTERN);
                    pss.append("  OPTIONAL { ?concept skos:prefLabel ?conceptLabel . } ");
                    pss.append("  OPTIONAL { ?concept rdf:type ?type . } ");
                    pss.append("  OPTIONAL { ?concept rdfs:domain ?domain . } ");
                    pss.append("  OPTIONAL { ?concept rdfs:range ?range . } ");
                    pss.append("  OPTIONAL { ?scheme skos:prefLabel ?schemeLabel . } ");
                    pss.append("} }");
                    try (QueryExecution qExec = conn.query(pss.asQuery())) {
                        Model result = qExec.execConstruct();
                        Map<String, ResolvedConceptDto> resolutions =
                                projectResolutions(result, SearchSource.ISMD);
                        log.debug("Resolved {} of {} requested concept IRI(s) against ISMD",
                                resolutions.size(), safeConceptIris.size());
                        return resolutions;
                    }
                });
    }

    /**
     * Iterates the result Model once and projects rows into a map keyed by concept
     * IRI. Same shape used by both ISMD ({@link #fetchConceptResolutions}) and the
     * NKD client — co-located here as a static helper so the two callers stay in
     * sync on parsing rules.
     */
    public static Map<String, ResolvedConceptDto> projectResolutions(Model model, SearchSource source) {
        if (model == null || model.isEmpty()) {
            return Map.of();
        }
        Property inScheme = model.createProperty("http://www.w3.org/2004/02/skos/core#inScheme");
        Property prefLabel = model.createProperty("http://www.w3.org/2004/02/skos/core#prefLabel");
        Property rdfType = model.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
        Property rdfsDomain = model.createProperty("http://www.w3.org/2000/01/rdf-schema#domain");
        Property rdfsRange = model.createProperty("http://www.w3.org/2000/01/rdf-schema#range");
        // A relationship is typed by the OFN role IRI (…/vztah) in ISMD-authored
        // graphs, but published NKD vocabularies type relationships only as
        // owl:ObjectProperty. Accept either — a hit on one is enough.
        Set<String> relationshipTypeIris = Set.of(OFN_NAMESPACE + VZTAH, OWL_OBJECT_PROPERTY);

        Map<String, ResolvedConceptDto> out = new HashMap<>();
        StmtIterator inSchemeStmts = model.listStatements(null, inScheme, (RDFNode) null);
        try {
            while (inSchemeStmts.hasNext()) {
                Statement stmt = inSchemeStmts.next();
                if (!stmt.getSubject().isURIResource() || !stmt.getObject().isURIResource()) {
                    continue;
                }
                String conceptIri = stmt.getSubject().getURI();
                if (out.containsKey(conceptIri)) {
                    continue;
                }
                Resource concept = stmt.getSubject().asResource();
                Resource scheme = stmt.getObject().asResource();
                Map<String, String> conceptLabels = collectMultilingual(concept, prefLabel);
                Map<String, String> schemeLabels = collectMultilingual(scheme, prefLabel);

                // Domain/range are only meaningful for relationships; carry the raw
                // target IRIs as iri-only stub DTOs for the resolver's second hop to
                // expand. Stubs never reach the cache or the wire — the resolver
                // replaces them with fully-resolved DTOs (or null) before caching.
                ResolvedConceptDto domainStub = null;
                ResolvedConceptDto rangeStub = null;
                if (hasAnyType(concept, rdfType, relationshipTypeIris)) {
                    domainStub = resourceStub(concept, rdfsDomain);
                    rangeStub = resourceStub(concept, rdfsRange);
                }

                out.put(conceptIri, ResolvedConceptDto.builder()
                        .iri(conceptIri)
                        .conceptName(conceptLabels.isEmpty() ? null : conceptLabels)
                        .ontologyIri(scheme.getURI())
                        .ontologyName(schemeLabels.isEmpty() ? null : schemeLabels)
                        .source(source)
                        .resolvedDomain(domainStub)
                        .resolvedRange(rangeStub)
                        .build());
            }
        } finally {
            inSchemeStmts.close();
        }
        return out;
    }

    private static boolean hasAnyType(Resource concept, Property rdfType, Set<String> typeIris) {
        StmtIterator types = concept.listProperties(rdfType);
        try {
            while (types.hasNext()) {
                RDFNode node = types.next().getObject();
                if (node.isURIResource() && typeIris.contains(node.asResource().getURI())) {
                    return true;
                }
            }
        } finally {
            types.close();
        }
        return false;
    }

    /** Returns an iri-only {@link ResolvedConceptDto} stub for the URI object of {@code property}, or null. */
    private static ResolvedConceptDto resourceStub(Resource concept, Property property) {
        Statement stmt = concept.getProperty(property);
        if (stmt == null || !stmt.getObject().isURIResource()) {
            return null;
        }
        return ResolvedConceptDto.builder().iri(stmt.getObject().asResource().getURI()).build();
    }

    private static Map<String, String> collectMultilingual(Resource subject, Property property) {
        Map<String, String> values = new LinkedHashMap<>();
        StmtIterator stmts = subject.listProperties(property);
        try {
            while (stmts.hasNext()) {
                RDFNode node = stmts.next().getObject();
                if (!node.isLiteral()) continue;
                Literal lit = node.asLiteral();
                String text = lit.getString();
                if (text == null || text.isEmpty()) continue;
                String lang = lit.getLanguage();
                String key = (lang == null || lang.isEmpty()) ? "" : lang;
                values.putIfAbsent(key, text);
            }
        } finally {
            stmts.close();
        }
        return values;
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
            case VZTAH -> OWL_OBJECT_PROPERTY;
        };
    }
}
