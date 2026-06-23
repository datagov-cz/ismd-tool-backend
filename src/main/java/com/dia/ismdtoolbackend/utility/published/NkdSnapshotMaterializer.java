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
 * owning concept's graph. Pure function: no TDB2 I/O, no outbox — the caller folds the returned set into
 * the owning concept's change set so the copy rides the owner aggregate.
 *
 * <p>Two invariants, both keeping the reconciler from treating the copy as an owned concept:
 * <ul>
 *   <li><strong>Never owned.</strong> The materialized subject keeps NKD's own {@code skos:inScheme}
 *       (a foreign scheme); {@link #assertNotOwnedBy} fails if any subject's {@code inScheme}
 *       prefix-matches the owner's scheme.</li>
 *   <li><strong>Provenance.</strong> A single {@link #NKD_SNAPSHOT_OF} marker triple
 *       ({@code <nkdIri> ismd:nkdSnapshotOf <ownerIri>}) distinguishes the copy from an owned concept
 *       and round-trips with the set so it is cleaned up alongside it.</li>
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
     * @param ownerIri    the local concept that links to it (origin target)
     * @param ownerScheme the owner's scheme IRI — used to assert the not-owned invariant
     * @return a fresh, modifiable {@link Set} of statements to add to the owner's change set
     * @throws OntologyException if a materialized subject would be owned by {@code ownerScheme}
     */
    public Set<Statement> materialize(Model rawNkdModel, String nkdIri, String ownerIri, String ownerScheme) {
        if (rawNkdModel == null || rawNkdModel.isEmpty()) {
            log.debug("No raw NKD triples to materialize for {}", nkdIri);
            return new HashSet<>();
        }

        Set<Statement> statements = new HashSet<>(rawNkdModel.listStatements().toSet());

        // Origin marker: <nkdIri> ismd:nkdSnapshotOf <ownerIri>.
        Resource subject = rawNkdModel.getResource(nkdIri);
        Property snapshotOf = rawNkdModel.createProperty(NKD_SNAPSHOT_OF);
        Resource owner = rawNkdModel.getResource(ownerIri);
        statements.add(rawNkdModel.createStatement(subject, snapshotOf, owner));

        assertNotOwnedBy(statements, ownerScheme, nkdIri);

        log.debug("Materialized {} triples (incl. provenance) for NKD copy {} under owner {}",
                statements.size(), nkdIri, ownerIri);
        return statements;
    }

    /**
     * Fails if any materialized {@code skos:inScheme} subject's IRI prefix-matches the owner's scheme —
     * the condition under which the reconciler would enumerate the copy as an owned concept. Foreign NKD
     * subjects never match a local owner scheme; a subject minted under the owner's scheme is caught.
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
            if (subjectIri.startsWith(ownerScheme)) {
                throw new OntologyException(
                        "Refusing to materialize NKD copy " + nkdIri + ": subject " + subjectIri
                                + " prefix-matches the owner scheme " + ownerScheme
                                + ", so the reconciler would treat it as owned.");
            }
        }
    }

    /**
     * Serializes a materialized statement set to N-Triples for the
     * {@code NkdConceptSnapshotEntity.materializedTriples} column. Same line-based, order-independent
     * encoding the outbox uses, so the stored set and the outbox payload compare exactly. Empty input →
     * empty string (never null).
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
