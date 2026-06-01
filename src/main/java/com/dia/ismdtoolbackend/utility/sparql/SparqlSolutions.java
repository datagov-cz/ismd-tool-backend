package com.dia.ismdtoolbackend.utility.sparql;

import org.apache.jena.query.QuerySolution;

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

    public static boolean literalBool(QuerySolution sol, String var) {
        if (!sol.contains(var) || !sol.get(var).isLiteral()) {
            return false;
        }
        try {
            return sol.getLiteral(var).getBoolean();
        } catch (Exception e) {
            return false;
        }
    }
}