package com.dia.ismdtoolbackend.utility.sparql;

import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SparqlSolutions#literalBool} must tolerate a numeric rendering of a boolean.
 *
 * <p>Virtuoso returns a projected comparison — {@code ((?a = ?b) AS ?flag)} — as
 * {@code "1"^^xsd:integer}, not {@code "true"^^xsd:boolean}. Reading it with
 * {@code Literal.getBoolean()} alone throws and was swallowed as false, which made
 * e-Sbírka's {@code isLatest} false for every version.
 */
class SparqlSolutionsBoolTest {

    private static QuerySolution solutionOf(String projection) {
        Model m = ModelFactory.createDefaultModel();
        String q = "SELECT (" + projection + " AS ?flag) WHERE { }";
        try (QueryExecution qe = QueryExecutionFactory.create(QueryFactory.create(q), m)) {
            ResultSet rs = qe.execSelect();
            return rs.next();
        }
    }

    @Test
    void readsXsdBooleanTrue() {
        assertTrue(SparqlSolutions.literalBool(solutionOf("\"true\"^^<http://www.w3.org/2001/XMLSchema#boolean>"), "flag"));
    }

    @Test
    void readsXsdBooleanFalse() {
        assertFalse(SparqlSolutions.literalBool(solutionOf("\"false\"^^<http://www.w3.org/2001/XMLSchema#boolean>"), "flag"));
    }

    @Test
    void readsVirtuosoIntegerOneAsTrue() {
        // The real e-Sbírka shape — this is what silently broke isLatest.
        assertTrue(SparqlSolutions.literalBool(solutionOf("\"1\"^^<http://www.w3.org/2001/XMLSchema#integer>"), "flag"));
    }

    @Test
    void readsVirtuosoIntegerZeroAsFalse() {
        assertFalse(SparqlSolutions.literalBool(solutionOf("\"0\"^^<http://www.w3.org/2001/XMLSchema#integer>"), "flag"));
    }

    @Test
    void readsPlainStringForms() {
        assertTrue(SparqlSolutions.literalBool(solutionOf("\"true\""), "flag"));
        assertTrue(SparqlSolutions.literalBool(solutionOf("\"1\""), "flag"));
        assertFalse(SparqlSolutions.literalBool(solutionOf("\"nonsense\""), "flag"));
    }

    @Test
    void unboundOrNonLiteralIsFalse() {
        Model m = ModelFactory.createDefaultModel();
        try (QueryExecution qe = QueryExecutionFactory.create(
                QueryFactory.create("SELECT ?missing WHERE { }"), m)) {
            QuerySolution sol = qe.execSelect().next();
            assertFalse(SparqlSolutions.literalBool(sol, "missing"));
        }
    }
}
