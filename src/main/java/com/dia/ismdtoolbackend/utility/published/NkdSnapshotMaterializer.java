package com.dia.ismdtoolbackend.utility.published;

import lombok.extern.slf4j.Slf4j;
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
 * Produces the RDF triple set that materializes a published NKD concept as a "local copy". Pure function:
 * no TDB2 I/O, no outbox. The returned set is persisted to the PG {@code materializedTriples} column — the
 * copy's sole home; it is never written to TDB2.
 *
 * <p>The materialized set carries a single {@link #NKD_SNAPSHOT_OF} provenance marker triple
 * ({@code <nkdIri> ismd:nkdSnapshotOf <ownerIri>}) identifying which concept owns the copy. NKD's own
 * {@code skos:inScheme} is dropped so the stored payload holds only the concept's own triples plus the
 * marker. The stored {@code snapshotJson} (the deviation-comparison payload) is built independently from
 * {@code fetchPublishedConceptWithScheme().detail()}.
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
     * Builds the triple set materializing {@code nkdIri} (whose raw NKD RDF is {@code rawNkdModel}),
     * tagged as a copy of {@code ownerIri}. The result is stored in the PG {@code materializedTriples}
     * column, never written to TDB2.
     *
     * @param rawNkdModel the raw NKD CONSTRUCT result (see {@code NkdSparqlClient.fetchPublishedConceptRaw})
     * @param nkdIri      the published NKD concept IRI (the materialized subject)
     * @param ownerIri    the local concept that links to it (origin target)
     * @return a fresh, modifiable {@link Set} of statements for the PG snapshot payload
     */
    public Set<Statement> materialize(Model rawNkdModel, String nkdIri, String ownerIri) {
        if (rawNkdModel == null || rawNkdModel.isEmpty()) {
            log.debug("No raw NKD triples to materialize for {}", nkdIri);
            return new HashSet<>();
        }

        // Drop NKD's own skos:inScheme — the stored payload holds the concept's own triples plus the
        // provenance marker only; the foreign scheme adds nothing to the deviation comparison.
        Set<Statement> statements = new HashSet<>();
        StmtIterator rawIt = rawNkdModel.listStatements();
        try {
            while (rawIt.hasNext()) {
                Statement s = rawIt.next();
                if (!SKOS_IN_SCHEME.equals(s.getPredicate().getURI())) {
                    statements.add(s);
                }
            }
        } finally {
            rawIt.close();
        }

        // Origin marker: <nkdIri> ismd:nkdSnapshotOf <ownerIri>.
        Resource subject = rawNkdModel.getResource(nkdIri);
        Property snapshotOf = rawNkdModel.createProperty(NKD_SNAPSHOT_OF);
        Resource owner = rawNkdModel.getResource(ownerIri);
        statements.add(rawNkdModel.createStatement(subject, snapshotOf, owner));

        log.debug("Materialized {} triples (incl. provenance) for NKD copy {} under owner {}",
                statements.size(), nkdIri, ownerIri);
        return statements;
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
