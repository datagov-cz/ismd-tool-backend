package com.dia.ismdtoolbackend.outbox;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

/**
 * One place for the N-Triples (de)serialization the outbox row payload uses, so the writer
 * (serialize, T4) and the relay (parse + re-serialize for SPARQL, T5) cannot drift apart.
 *
 * <p>N-Triples is chosen deliberately: it is line-based, order-independent, and has no prefix
 * state, so a serialized statement set round-trips exactly and two payloads are comparable.
 */
public final class OutboxTriples {

    private OutboxTriples() {
    }

    /** Serializes a statement set to N-Triples. Empty input → empty string (never null). */
    static String toNTriples(Collection<Statement> statements) {
        if (statements == null || statements.isEmpty()) {
            return "";
        }
        Model model = ModelFactory.createDefaultModel();
        model.add(statements.toArray(new Statement[0]));
        StringWriter out = new StringWriter();
        RDFDataMgr.write(out, model, Lang.NTRIPLES);
        model.close();
        return out.toString();
    }

    /** Parses N-Triples back into a Model. Blank/null → empty model. */
    public static Model parse(String nTriples) {
        Model model = ModelFactory.createDefaultModel();
        if (nTriples == null || nTriples.isBlank()) {
            return model;
        }
        RDFDataMgr.read(model, new ByteArrayInputStream(nTriples.getBytes(StandardCharsets.UTF_8)),
                Lang.NTRIPLES);
        return model;
    }
}
