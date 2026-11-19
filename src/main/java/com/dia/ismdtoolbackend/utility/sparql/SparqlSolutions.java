package com.dia.ismdtoolbackend.utility.sparql;

import org.apache.jena.query.QuerySolution;
import org.apache.jena.rdf.model.Literal;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Null-safe accessors for Jena {@link QuerySolution} bindings, used by SPARQL
 * SELECT result mappers across the codebase. Each helper returns {@code null}
 * (or the documented default) when the variable is unbound, has the wrong
 * RDF node kind, or fails to parse.
 */
public final class SparqlSolutions {

    private SparqlSolutions() {
    }

    public static String resourceUri(QuerySolution sol, String var) {
        return (sol.contains(var) && sol.get(var).isResource()) ? sol.getResource(var).getURI() : null;
    }

    public static String literalString(QuerySolution sol, String var) {
        return (sol.contains(var) && sol.get(var).isLiteral()) ? sol.getLiteral(var).getString() : null;
    }

    public static Integer literalInt(QuerySolution sol, String var) {
        if (!sol.contains(var) || !sol.get(var).isLiteral()) {
            return null;
        }
        try {
            return Integer.parseInt(sol.getLiteral(var).getString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static LocalDate literalDate(QuerySolution sol, String var) {
        String s = literalString(sol, var);
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Read a boolean projection, tolerating stores that render it as a number — Virtuoso
     * returns {@code ((?a = ?b) AS ?flag)} as {@code "1"^^xsd:integer}. Tries xsd:boolean,
     * then a numeric read (non-zero = true), then the lexical form.
     */
    public static boolean literalBool(QuerySolution sol, String var) {
        if (!sol.contains(var) || !sol.get(var).isLiteral()) {
            return false;
        }
        Literal lit = sol.getLiteral(var);
        try {
            return lit.getBoolean();
        } catch (Exception ignored) {
            // not an xsd:boolean — fall through
        }
        try {
            return lit.getInt() != 0;
        } catch (Exception ignored) {
            // not numeric either — fall through
        }
        String lexical = lit.getLexicalForm().trim();
        return "true".equalsIgnoreCase(lexical) || "1".equals(lexical);
    }

    /**
     * Decodes percent-escapes so the IRI is in the raw-UTF-8 form the stores hold.
     *
     * <p>Idempotent for an already-raw IRI, which is what makes it safe to apply
     * unconditionally: an IRI with no {@code %} is returned unchanged.
     *
     * <p>Decodes once only. A double-encoded IRI is left partly encoded rather than
     * unwrapped to something the caller never sent — repeated decoding would also corrupt any
     * IRI whose own path legitimately contains a {@code %}-escape.
     *
     * <p>{@code +} is preserved. {@link URLDecoder} is a form-data decoder and would otherwise
     * turn it into a space; in an IRI path a {@code +} is a literal plus.
     */
    public static String toRawUtf8(String iri) {
        if (iri == null || iri.indexOf('%') < 0) {
            return iri;
        }
        try {
            return URLDecoder.decode(iri.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Malformed escape (e.g. a bare "%" or "%zz"): the IRI is not percent-encoded in
            // any meaningful sense, so match it as given rather than rejecting it here. The
            // safety check still runs downstream.
            return iri;
        }
    }
}