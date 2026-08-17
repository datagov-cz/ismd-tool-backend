package com.dia.ismdtoolbackend.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NKDSPARQLBrowseQueryTest {

    private static final String ONTOLOGY = "https://example.org/ontology/1";

    @Test
    void buildOntologyConceptsQuery_scopesToTheOntologyAndProjectsSlimFields() {
        String query = NKDSPARQLBrowseQuery.buildOntologyConceptsQuery(ONTOLOGY, "cs");

        assertTrue(query.contains("skos:inScheme <" + ONTOLOGY + ">"),
                "concepts must be scoped to the requested ontology");
        assertTrue(query.contains("?concept"));
        assertTrue(query.contains("?label"));
    }

    @Test
    void buildOntologyConceptsQuery_projectsAllThreeRoleMarkers() {
        String query = NKDSPARQLBrowseQuery.buildOntologyConceptsQuery(ONTOLOGY, "cs");

        // Role is read from independent OPTIONAL binds, each accepting the OFN tag or the OWL type,
        // so an unfiltered listing still populates conceptType.
        assertTrue(query.contains("?roleTrida"));
        assertTrue(query.contains("?roleVlastnost"));
        assertTrue(query.contains("?roleVztah"));
        assertTrue(query.contains("http://www.w3.org/2002/07/owl#Class"));
        assertTrue(query.contains("http://www.w3.org/2002/07/owl#DatatypeProperty"));
        assertTrue(query.contains("http://www.w3.org/2002/07/owl#ObjectProperty"));
    }

    @Test
    void buildOntologyConceptsQuery_isASelectNotAConstruct() {
        String query = NKDSPARQLBrowseQuery.buildOntologyConceptsQuery(ONTOLOGY, "cs");

        // The point of this query: read three fields, not the whole concept graph.
        assertTrue(query.contains("SELECT"));
        assertFalse(query.contains("CONSTRUCT"));
    }

    @Test
    void buildOntologyConceptsQuery_isDeterministicallyOrdered() {
        String query = NKDSPARQLBrowseQuery.buildOntologyConceptsQuery(ONTOLOGY, "cs");

        assertTrue(query.contains("ORDER BY"), "listing must be stable across calls");
    }

    @Test
    void buildOntologyConceptsQuery_sanitizesLangTag() {
        String query = NKDSPARQLBrowseQuery.buildOntologyConceptsQuery(ONTOLOGY, "cs\" || injected");

        // The tag is stripped to [A-Za-z0-9-], so the payload's characters may survive but the quote
        // that would close the literal cannot. Assert on the emitted literal itself.
        assertTrue(query.contains("FILTER(LANG(?prefLabel) = \"csinjected\")"),
                "lang tag must be reduced to safe characters inside the literal");
        assertFalse(query.contains("cs\""),
                "a lang tag must not be able to close the string literal it sits in");
    }

    @Test
    void buildOntologyConceptsQuery_rejectsUnsafeIri() {
        assertThrows(IllegalArgumentException.class,
                () -> NKDSPARQLBrowseQuery.buildOntologyConceptsQuery("not-an-iri> } INSERT {", "cs"));
    }
}
