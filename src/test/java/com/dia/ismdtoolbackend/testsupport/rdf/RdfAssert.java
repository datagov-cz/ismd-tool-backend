package com.dia.ismdtoolbackend.testsupport.rdf;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Statement;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Triple-level assertions. Failure messages list the triples that differ, not two whole models.
 */
public final class RdfAssert {

    private RdfAssert() {}

    /** Asserts {@code actual} holds exactly the triples of {@code expectedTurtle} (blank nodes matched by structure). */
    public static void assertIsomorphic(String expectedTurtle, Model actual) {
        assertIsomorphic(Ttl.parse(expectedTurtle), actual);
    }

    public static void assertIsomorphic(Model expected, Model actual) {
        if (expected.isIsomorphicWith(actual)) {
            return;
        }
        throw new AssertionError("Models differ."
                + "\nMissing (expected, not present):\n" + render(expected.difference(actual))
                + "\nUnexpected (present, not expected):\n" + render(actual.difference(expected)));
    }

    /** Asserts every triple of {@code turtle} is in {@code actual}. The snippet must not use blank nodes. */
    public static void assertContainsTriples(Model actual, String turtle) {
        Model expected = Ttl.parse(turtle);
        List<Statement> missing = expected.listStatements().filterDrop(actual::contains).toList();
        assertThat(missing).as("triples missing from model:\n%s", Ttl.write(actual)).isEmpty();
    }

    /** Asserts no triple of {@code turtle} is in {@code actual}. The snippet must not use blank nodes. */
    public static void assertContainsNoneOf(Model actual, String turtle) {
        Model unwanted = Ttl.parse(turtle);
        List<Statement> present = unwanted.listStatements().filterKeep(actual::contains).toList();
        assertThat(present).as("triples that should be absent").isEmpty();
    }

    private static String render(Model model) {
        if (model.isEmpty()) {
            return "  (none)";
        }
        StringBuilder sb = new StringBuilder();
        model.listStatements().forEach(s -> sb.append("  ").append(s.asTriple()).append('\n'));
        return sb.toString();
    }
}
