package com.dia.ismdtoolbackend.utility.sparql;

import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SparqlSolutions#literalBool} must tolerate a numeric rendering of a boolean —
 * Virtuoso returns a projected {@code ((?a = ?b) AS ?flag)} as {@code "1"^^xsd:integer},
 * on which {@code Literal.getBoolean()} alone throws.
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
        assertThat(SparqlSolutions.literalBool(solutionOf("\"true\"^^<http://www.w3.org/2001/XMLSchema#boolean>"), "flag"))
                .isTrue();
    }

    @Test
    void readsXsdBooleanFalse() {
        assertThat(SparqlSolutions.literalBool(solutionOf("\"false\"^^<http://www.w3.org/2001/XMLSchema#boolean>"), "flag"))
                .isFalse();
    }

    @Test
    void readsVirtuosoIntegerOneAsTrue() {
        // The real e-Sbírka shape — this is what silently broke isLatest.
        assertThat(SparqlSolutions.literalBool(solutionOf("\"1\"^^<http://www.w3.org/2001/XMLSchema#integer>"), "flag"))
                .isTrue();
    }

    @Test
    void readsVirtuosoIntegerZeroAsFalse() {
        assertThat(SparqlSolutions.literalBool(solutionOf("\"0\"^^<http://www.w3.org/2001/XMLSchema#integer>"), "flag"))
                .isFalse();
    }

    @Test
    void readsPlainStringForms() {
        assertThat(SparqlSolutions.literalBool(solutionOf("\"true\""), "flag")).isTrue();
        assertThat(SparqlSolutions.literalBool(solutionOf("\"1\""), "flag")).isTrue();
        assertThat(SparqlSolutions.literalBool(solutionOf("\"nonsense\""), "flag")).isFalse();
    }

    @Test
    void unboundOrNonLiteralIsFalse() {
        Model m = ModelFactory.createDefaultModel();
        try (QueryExecution qe = QueryExecutionFactory.create(
                QueryFactory.create("SELECT ?missing WHERE { }"), m)) {
            QuerySolution sol = qe.execSelect().next();
            assertThat(SparqlSolutions.literalBool(sol, "missing")).isFalse();
        }
    }
}
