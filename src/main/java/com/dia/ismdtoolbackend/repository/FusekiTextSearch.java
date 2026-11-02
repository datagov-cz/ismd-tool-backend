package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.config.DomainApplicationProfile;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.RelationType;
import com.dia.ismdtoolbackend.utility.sparql.FusekiSparqlExecutor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdfconnection.RDFConnection;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE;
import static com.dia.constants.VocabularyConstants.TRIDA;
import static com.dia.constants.VocabularyConstants.VLASTNOST;
import static com.dia.constants.VocabularyConstants.VZTAH;

/** Text-index search and relation filtering against Fuseki. Reached through {@link JenaTDB2Repository}. */
@Slf4j
final class FusekiTextSearch {

    private final FusekiSparqlExecutor executor;
    private final Supplier<RDFConnection> connectionFactory;
    private final Environment environment;

    /**
     * Tracks whether the Fuseki text index is wired and answering queries.
     * Probed at startup (see {@link #probe(String)}); read by {@link #searchByText(String, List, int)}
     * to short-circuit with an empty result set when the index is absent instead of
     * running a SPARQL that would return garbage (all resources in the graph).
     */
    private volatile boolean textIndexAvailable = false;

    FusekiTextSearch(FusekiSparqlExecutor executor, Supplier<RDFConnection> connectionFactory,
                     Environment environment) {
        this.executor = executor;
        this.connectionFactory = connectionFactory;
        this.environment = environment;
    }

    boolean isAvailable() {
        return textIndexAvailable;
    }

    void setAvailable(boolean available) {
        this.textIndexAvailable = available;
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
    void probe(String fusekiEndpoint) {
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
        try (RDFConnection conn = connectionFactory.get();
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
                    String typeFilterClause = conceptTypeFilter == null ? "" : roleFilterClause(conceptTypeFilter);
                    // text:query inside GRAPH — requires Jena 5.4+ where the property
                    // function is correctly wired through the TextDataset assembler.
                    //
                    // GROUP_CONCAT collapses the multiple rdf:type triples each resource has
                    // (concepts carry skos:Concept + ofn:pojem + owl:DatatypeProperty/…;
                    // ontologies carry skos:ConceptScheme + owl:Ontology + ofn:slovník) into
                    // one row per resource so LIMIT counts resources, not type triples.
                    //
                    // ORDER BY makes the LIMIT slice deterministic. Without it the engine may
                    // return any subset, so a resource present in the index can vanish from
                    // results run to run. Publish state is not in the graph (it lives in PG),
                    // so the caller re-ranks after merge — this ordering only guarantees the
                    // truncation is stable and repeatable.
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
                            "} GROUP BY ?resource ?g ORDER BY ?resource ?g LIMIT " + limit;
                    log.debug("Fuseki text search SPARQL: {}", sparql);
                    List<Map<String, String>> results = new ArrayList<>();
                    try (QueryExecution qExec = conn.query(sparql)) {
                        ResultSet rs = qExec.execSelect();
                        while (rs.hasNext()) {
                            QuerySolution sol = rs.next();
                            Map<String, String> row = new HashMap<>();
                            row.put("resourceIri", sol.getResource("resource") != null ? sol.getResource("resource").getURI() : null);
                            // ?g comes from VALUES, so it is always bound.
                            row.put("graphName", sol.getResource("g").getURI());
                            if (sol.getLiteral("prefLabel") != null) row.put("prefLabel", sol.getLiteral("prefLabel").getString());
                            if (sol.getLiteral("prefLabelLang") != null) row.put("prefLabelLang", sol.getLiteral("prefLabelLang").getString());
                            if (sol.getLiteral("altLabel") != null) row.put("altLabel", sol.getLiteral("altLabel").getString());
                            if (sol.getLiteral("description") != null) row.put("description", sol.getLiteral("description").getString());
                            if (sol.getLiteral("definition") != null) row.put("definition", sol.getLiteral("definition").getString());
                            // An untyped resource leaves ?types unbound (STR of an unbound ?typeS is an error).
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
        return input.replace("\\", "\\\\")
                .replace("'", "\\'");
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
                            // ?concept comes from VALUES, so it is always an IRI.
                            matchingIris.add(sol.getResource("concept").getURI());
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

    // KONCEPT is the generic base type every concept carries, so it is not a
    // role narrowing — SearchType.toConceptType() never yields it here.
    private static String roleFilterClause(ConceptType type) {
        return switch (type) {
            case TRIDA -> roleFilterClause(OFN_NAMESPACE + TRIDA, "http://www.w3.org/2002/07/owl#Class");
            case VLASTNOST -> roleFilterClause(OFN_NAMESPACE + VLASTNOST,
                    "http://www.w3.org/2002/07/owl#DatatypeProperty");
            case VZTAH -> roleFilterClause(OFN_NAMESPACE + VZTAH, ConceptResolutionProjection.OWL_OBJECT_PROPERTY);
            case KONCEPT -> throw new IllegalArgumentException("KONCEPT is not a role-narrowing type");
        };
    }

    private static String roleFilterClause(String ofnRoleIri, String owlTypeIri) {
        return "    FILTER(EXISTS { ?resource a <" + ofnRoleIri + "> }"
                + " || EXISTS { ?resource a <" + owlTypeIri + "> }) ";
    }
}
