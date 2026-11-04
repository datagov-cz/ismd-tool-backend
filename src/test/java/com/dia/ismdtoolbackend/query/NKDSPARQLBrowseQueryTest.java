package com.dia.ismdtoolbackend.query;

import com.dia.ismdtoolbackend.ofn.OfnIris;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

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
    void buildOntologyConceptsQuery_roleComesFromTheOwlKindOnly() {
        Model data = ModelFactory.createDefaultModel();
        Resource scheme = data.createResource(ONTOLOGY);
        Map<String, Resource> types = Map.of(
                "class", OWL2.Class, "datatype", OWL2.DatatypeProperty, "object", OWL2.ObjectProperty,
                "markerOnly", data.createResource(OfnIris.MARKER_TRIDA));
        types.forEach((name, type) -> data.createResource("https://example.org/pojem/" + name)
                .addProperty(SKOS.inScheme, scheme).addProperty(RDF.type, type));

        Map<String, List<String>> boundRoles = new HashMap<>();
        try (QueryExecution exec = QueryExecutionFactory.create(
                NKDSPARQLBrowseQuery.buildOntologyConceptsQuery(ONTOLOGY, "cs"), data)) {
            exec.execSelect().forEachRemaining(row -> boundRoles.put(
                    row.getResource("concept").getLocalName(),
                    Stream.of("roleTrida", "roleVlastnost", "roleVztah").filter(row::contains).toList()));
        }

        assertThat(boundRoles).containsOnly(
                entry("class", List.of("roleTrida")),
                entry("datatype", List.of("roleVlastnost")),
                entry("object", List.of("roleVztah")),
                entry("markerOnly", List.of()));
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
