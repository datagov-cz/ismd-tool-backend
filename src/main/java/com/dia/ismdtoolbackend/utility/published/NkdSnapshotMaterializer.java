package com.dia.ismdtoolbackend.utility.published;

import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Produces the RDF triple set that materializes a published NKD concept as a "local copy" inside an
 * owning concept's graph. <strong>Pure function: no TDB2 I/O, no outbox.</strong> The caller
 * ({@code NkdSnapshotService}) folds the returned set into the <em>owning concept's</em> outbox
 * change set (C1), so the copy rides the owner aggregate and applies atomically with the link triple.
 *
 * <p>Two guarantees, both load-bearing for the reconciler (see
 * {@code .planning/nkd-local-copy-snapshot-PLAN.md}):
 * <ul>
 *   <li><strong>M3 — never owned.</strong> The materialized subject keeps NKD's own
 *       {@code skos:inScheme}. The invariant {@link #assertNotOwnedBy} enforces is that no materialized
 *       subject carries an {@code inScheme} that prefix-matches the owner's scheme — so the subject
 *       never satisfies {@code OWNED_CONCEPT_PATTERN} and is never flagged {@code RDF_ORPHAN}. (NKD
 *       IRIs are foreign and never prefix-match a local scheme; a no-{@code inScheme} concept is also
 *       safe because the pattern needs the triple to exist — but we assert rather than assume.)</li>
 *   <li><strong>M2 — provenance.</strong> A single {@link #NKD_SNAPSHOT_OF} marker triple
 *       ({@code <nkdIri> ismd:nkdSnapshotOf <ownerIri>}) lets the graph view distinguish a
 *       materialized external copy from an owned concept and makes removal deterministic. It is part
 *       of the materialized set, so it round-trips through N-Triples and is cleaned up with the copy.
 *       The marker alone does not satisfy {@code OWNED_CONCEPT_PATTERN} (no {@code inScheme}).</li>
 * </ul>
 */
@Component
@Slf4j
public class NkdSnapshotMaterializer {

    private static final String SKOS_IN_SCHEME = "http://www.w3.org/2004/02/skos/core#inScheme";

    /**
     * Internal ISMD predicate tagging a materialized NKD copy with the concept that owns it. Declared
     * here (it exists nowhere upstream); the {@code ismd} namespace is deliberately our own, distinct
     * from {@code slovník.gov.cz}, so a materialized subject can never become "owned" via this triple.
     */
    public static final String NKD_SNAPSHOT_OF =
            "https://ismd.dia.gov.cz/internal/pojem/nkd-snapshot-of";

    /**
     * Builds the triple set materializing {@code nkdIri} (whose raw NKD RDF is {@code rawNkdModel})
     * into the graph owned by {@code ownerScheme}, tagged as a copy of {@code ownerIri}.
     *
     * @param rawNkdModel the raw NKD CONSTRUCT result (see {@code NkdSparqlClient.fetchPublishedConceptRaw})
     * @param nkdIri      the published NKD concept IRI (the materialized subject)
     * @param ownerIri    the local concept that links to it (provenance target)
     * @param ownerScheme the owner's scheme IRI — used to assert the M3 not-owned invariant
     * @return a fresh, modifiable {@link Set} of statements to add to the owner's change set
     * @throws OntologyException if a materialized subject would be owned by {@code ownerScheme} (M3 violation)
     */
    public Set<Statement> materialize(Model rawNkdModel, String nkdIri, String ownerIri, String ownerScheme) {
        if (rawNkdModel == null || rawNkdModel.isEmpty()) {
            log.debug("No raw NKD triples to materialize for {}", nkdIri);
            return new HashSet<>();
        }

        Set<Statement> statements = new HashSet<>(rawNkdModel.listStatements().toSet());

        // M2: provenance marker — <nkdIri> ismd:nkdSnapshotOf <ownerIri>.
        Resource subject = rawNkdModel.getResource(nkdIri);
        Property snapshotOf = rawNkdModel.createProperty(NKD_SNAPSHOT_OF);
        Resource owner = rawNkdModel.getResource(ownerIri);
        statements.add(rawNkdModel.createStatement(subject, snapshotOf, owner));

        // M3: enforce the not-owned invariant before the set leaves this method.
        assertNotOwnedBy(statements, ownerScheme, nkdIri);

        log.debug("Materialized {} triples (incl. provenance) for NKD copy {} under owner {}",
                statements.size(), nkdIri, ownerIri);
        return statements;
    }

    /**
     * M3 guard: fails if any materialized subject would be enumerated as <em>owned</em> by the
     * reconciler once it lands in the owner's graph.
     *
     * <p>This mirrors {@code JenaTDB2Repository.OWNED_CONCEPT_PATTERN} exactly — a subject is owned iff
     * it declares {@code skos:inScheme ?scheme} <strong>and</strong> {@code STRSTARTS(subject, ?scheme)}
     * — but evaluated against the owner's scheme specifically, because that is the only graph this copy
     * is written into. A foreign NKD subject (e.g. {@code slovník.gov.cz/…}) never string-prefix-matches
     * a local owner scheme, so it passes; a subject minted under the owner's own scheme is caught
     * regardless of which scheme it declares. (No false positive on path-relative slovník.gov.cz schemes:
     * the check is subject-vs-owner-scheme, not scheme-vs-scheme.)
     *
     * <p>The owner-scheme comparison is the safety boundary; the declared {@code inScheme} only matters
     * in that the reconciler requires the triple to exist — a no-{@code inScheme} subject is safe (m3)
     * and produces no violation here.
     */
    void assertNotOwnedBy(Collection<Statement> statements, String ownerScheme, String nkdIri) {
        if (ownerScheme == null || ownerScheme.isBlank()) {
            return;
        }
        for (Statement stmt : statements) {
            if (!SKOS_IN_SCHEME.equals(stmt.getPredicate().getURI())) {
                continue;
            }
            if (!stmt.getSubject().isURIResource()) {
                continue;
            }
            String subjectIri = stmt.getSubject().getURI();
            // Reconciler ownership = subject declares an inScheme (this statement) AND its IRI
            // prefix-matches a scheme the owner graph owns. The owner scheme is that scheme, so the
            // only thing that makes the copy owned-in-the-owner-graph is subject STRSTARTS ownerScheme.
            if (subjectIri.startsWith(ownerScheme)) {
                throw new OntologyException(
                        "Refusing to materialize NKD copy " + nkdIri + ": subject " + subjectIri
                                + " prefix-matches the owner scheme " + ownerScheme
                                + ", so the reconciler would treat it as owned (RDF_ORPHAN drift).");
            }
        }
    }

    /**
     * Serializes a materialized statement set to N-Triples for the
     * {@code NkdConceptSnapshotEntity.materializedTriples} column (M1 — the exact delete-set source of
     * truth). Same line-based, order-independent encoding the outbox uses, so the stored set and the
     * outbox payload compare exactly. Empty input → empty string (never null).
     */
    public String toNTriples(Collection<Statement> statements) {
        if (statements == null || statements.isEmpty()) {
            return "";
        }
        Model model = ModelFactory.createDefaultModel();
        model.add(statements.toArray(new Statement[0]));
        StringWriter out = new StringWriter();
        RDFDataMgr.write(out, model, Lang.NTRIPLES);
        return out.toString();
    }

    /** Parses a stored {@code materializedTriples} value back into a statement set. Blank → empty. */
    public Set<Statement> parse(String nTriples) {
        Model model = ModelFactory.createDefaultModel();
        if (nTriples == null || nTriples.isBlank()) {
            return new HashSet<>();
        }
        RDFDataMgr.read(model, new ByteArrayInputStream(nTriples.getBytes(StandardCharsets.UTF_8)),
                Lang.NTRIPLES);
        Set<Statement> statements = new HashSet<>();
        StmtIterator it = model.listStatements();
        try {
            while (it.hasNext()) {
                statements.add(it.next());
            }
        } finally {
            it.close();
        }
        return statements;
    }
}
