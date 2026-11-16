package com.dia.ismdtoolbackend.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NKDSPARQLBrowseQueryTest {

    private static final String ONTOLOGY = "https://example.org/ontology/1";

    @Test
    void buildOntologyConceptsQuery_scopesToTheOntologyAndProjectsSlimFields() {
        String query = NKDSPARQLBrowseQuery.buildOntologyConceptsQuery(ONTOLOGY, "cs");

        assertThat(query)
                .as("concepts must be scoped to the requested ontology")
                .contains("skos:inScheme <" + ONTOLOGY + ">");
        assertThat(query).contains("?concept");
        assertThat(query).contains("?label");
    }

    @Test
    void buildOntologyConceptsQuery_projectsAllThreeRoleMarkers() {
        String query = NKDSPARQLBrowseQuery.buildOntologyConceptsQuery(ONTOLOGY, "cs");

        // Role is read from independent OPTIONAL binds, each accepting the OFN tag or the OWL type,
        // so an unfiltered listing still populates conceptType.
        assertThat(query).contains("?roleTrida");
        assertThat(query).contains("?roleVlastnost");
        assertThat(query).contains("?roleVztah");
        assertThat(query).contains("http://www.w3.org/2002/07/owl#Class");
        assertThat(query).contains("http://www.w3.org/2002/07/owl#DatatypeProperty");
        assertThat(query).contains("http://www.w3.org/2002/07/owl#ObjectProperty");
    }

    @Test
    void buildOntologyConceptsQuery_isASelectNotAConstruct() {
        String query = NKDSPARQLBrowseQuery.buildOntologyConceptsQuery(ONTOLOGY, "cs");

        // The point of this query: read three fields, not the whole concept graph.
        assertThat(query).contains("SELECT");
        assertThat(query).doesNotContain("CONSTRUCT");
    }

    @Test
    void buildOntologyConceptsQuery_isDeterministicallyOrdered() {
        String query = NKDSPARQLBrowseQuery.buildOntologyConceptsQuery(ONTOLOGY, "cs");

        assertThat(query).as("listing must be stable across calls").contains("ORDER BY");
    }

    @Test
    void buildOntologyConceptsQuery_sanitizesLangTag() {
        String query = NKDSPARQLBrowseQuery.buildOntologyConceptsQuery(ONTOLOGY, "cs\" || injected");

        // The tag is stripped to [A-Za-z0-9-], so the payload's characters may survive but the quote
        // that would close the literal cannot. Assert on the emitted literal itself.
        assertThat(query)
                .as("lang tag must be reduced to safe characters inside the literal")
                .contains("FILTER(LANG(?prefLabel) = \"csinjected\")");
        assertThat(query)
                .as("a lang tag must not be able to close the string literal it sits in")
                .doesNotContain("cs\"");
    }

    @Test
    void buildOntologyConceptsQuery_rejectsUnsafeIri() {
        assertThatThrownBy(() -> NKDSPARQLBrowseQuery.buildOntologyConceptsQuery("not-an-iri> } INSERT {", "cs"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
