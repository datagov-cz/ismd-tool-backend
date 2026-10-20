package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.utility.sparql.FusekiSparqlExecutor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.modify.request.QuadDataAcc;
import org.apache.jena.sparql.modify.request.UpdateDataDelete;
import org.apache.jena.sparql.modify.request.UpdateDataInsert;
import org.apache.jena.update.UpdateFactory;
import org.apache.jena.update.UpdateRequest;

import java.util.List;

/** Graph and concept writes against Fuseki. Reached through {@link JenaTDB2Repository}. */
@Slf4j
final class FusekiGraphWriter {

    private final FusekiSparqlExecutor executor;

    FusekiGraphWriter(FusekiSparqlExecutor executor) {
        this.executor = executor;
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
                        if (JenaTDB2Repository.conceptNotFoundInGraph(conn, conceptUri, graphName)) {
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
}
