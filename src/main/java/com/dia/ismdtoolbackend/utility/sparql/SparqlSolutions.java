package com.dia.ismdtoolbackend.utility.sparql;

import org.apache.jena.query.QuerySolution;
import org.apache.jena.rdf.model.Literal;

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
     * Read a boolean projection, tolerating stores that render it as a number.
     *
     * <p>Virtuoso returns a projected comparison such as {@code ((?a = ?b) AS ?flag)} as
     * {@code "1"^^xsd:integer}, not {@code "true"^^xsd:boolean}. {@code getBoolean()} throws
     * on that, and the exception was swallowed as {@code false} — so e-Sbírka's
     * {@code isLatest} was false for every version, silently. Falls back to a numeric read
     * (non-zero = true), then to parsing the lexical form.
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
}