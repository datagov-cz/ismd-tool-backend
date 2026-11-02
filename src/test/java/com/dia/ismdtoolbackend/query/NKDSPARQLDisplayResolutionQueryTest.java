package com.dia.ismdtoolbackend.query;

import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The display-resolution CONSTRUCT run against an in-memory graph; it uses no Virtuoso-only functions. */
class NKDSPARQLDisplayResolutionQueryTest {

    private static final String NS = "https://slovník.gov.cz/agendový/104/";

    private final Model nkd = ModelFactory.createDefaultModel();
    private final Resource scheme = nkd.createResource(NS + "glosář")
            .addProperty(SKOS.prefLabel, "Adresy", "cs");
    private final Resource relation = nkd.createResource(NS + "pojem/má-adresu")
            .addProperty(RDF.type, OWL2.ObjectProperty)
            .addProperty(SKOS.prefLabel, "má adresu", "cs")
            .addProperty(SKOS.inScheme, scheme)
            .addProperty(RDFS.domain, nkd.createResource(NS + "pojem/osoba"))
            .addProperty(RDFS.range, nkd.createResource(NS + "pojem/adresa"));
    private final Resource untyped = nkd.createResource(NS + "pojem/bez-typu")
            .addProperty(SKOS.prefLabel, "bez typu", "cs");
    private final Resource notAsked = nkd.createResource(NS + "pojem/jiný")
            .addProperty(RDF.type, SKOS.Concept)
            .addProperty(SKOS.prefLabel, "jiný", "cs");

    private Model run(List<String> iris) {
        try (QueryExecution qe = QueryExecutionFactory.create(
                NKDSPARQLConstructQuery.buildDisplayResolutionConstructQuery(iris), nkd)) {
            return qe.execConstruct();
        }
    }

    @Test
    void returnsTypeLabelSchemeAndEndpointsOfTheAskedConcept() {
        Model result = run(List.of(relation.getURI()));

        Model expected = ModelFactory.createDefaultModel();
        expected.add(relation, RDF.type, OWL2.ObjectProperty)
                .add(relation, SKOS.prefLabel, "má adresu", "cs")
                .add(relation, SKOS.inScheme, scheme)
                .add(scheme, SKOS.prefLabel, "Adresy", "cs")
                .add(relation, RDFS.domain, expected.createResource(NS + "pojem/osoba"))
                .add(relation, RDFS.range, expected.createResource(NS + "pojem/adresa"));
        assertThat(result.isIsomorphicWith(expected)).as("constructed:%n%s", result).isTrue();
    }

    @Test
    void conceptWithoutATypeIsNotResolved() {
        assertThat(run(List.of(untyped.getURI())).isEmpty()).isTrue();
    }

    @Test
    void onlyTheAskedConceptsAreReturned() {
        Model result = run(List.of(relation.getURI(), untyped.getURI()));

        assertThat(result.listSubjects().toList()).extracting(Resource::getURI)
                .containsExactlyInAnyOrder(relation.getURI(), scheme.getURI())
                .doesNotContain(notAsked.getURI());
    }

    @Test
    void conceptWithoutSchemeOrLabelStillResolvesByItsType() {
        Model result = run(List.of(notAsked.getURI()));

        assertThat(result.contains(notAsked, RDF.type, SKOS.Concept)).isTrue();
        assertThat(result.contains(notAsked, SKOS.inScheme)).isFalse();
    }
}
