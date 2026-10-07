package com.dia.ismdtoolbackend.query;

import org.apache.jena.query.QueryFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Guards the concept → datasets reverse lookup.
 *
 * <p>The predicate {@code týká-se-pojmu} carries no triples in the live catalogue (probed on
 * both {@code data.gov.cz} and {@code pod-test.dia.gov.cz} on 2026-09-29), so there is no live
 * data to assert against and the query's shape is all that can be pinned down here.
 */
class NKODSPARQLDatasetQueryTest {

    private static final String CONCEPT =
            "https://slovník.gov.cz/generický/pojem/číslo";

    @Test
    void datasetsByConceptQueryParses() {
        String query = NKODSPARQLDatasetQuery.buildDatasetsByConceptQuery(CONCEPT, 100);

        assertThatCode(() -> QueryFactory.create(query)).doesNotThrowAnyException();
    }

    /**
     * Catalogue triples live only inside named graphs — an unscoped pattern returns zero rows
     * at HTTP 200, which reads as "no datasets" rather than as a broken query.
     */
    @Test
    void datasetsByConceptQueryIsGraphScoped() {
        String query = NKODSPARQLDatasetQuery.buildDatasetsByConceptQuery(CONCEPT, 100);

        assertThat(query).contains("GRAPH ?g");
    }

    /**
     * The concept must be the <em>object</em> of the annotation, with the dataset as subject.
     * Inverting the two parses and runs, and silently returns nothing.
     */
    @Test
    void datasetsByConceptMatchesConceptAsObject() {
        String query = NKODSPARQLDatasetQuery.buildDatasetsByConceptQuery(CONCEPT, 100);

        assertThat(query).contains(
                "?ds <%s> <%s> .".formatted(NKODSPARQLDatasetQuery.TYKA_SE_POJMU, CONCEPT));
    }

    /**
     * Title and description must stay OPTIONAL: a dataset with no title still counts as linked,
     * and requiring the title would drop it from the concept's list entirely.
     */
    @Test
    void datasetsByConceptKeepsLabelsOptional() {
        String query = NKODSPARQLDatasetQuery.buildDatasetsByConceptQuery(CONCEPT, 100);

        assertThat(query)
                .contains("OPTIONAL { ?ds dcterms:title ?nazev }")
                .contains("OPTIONAL { ?ds dcterms:description ?popis }");
    }

    @Test
    void datasetsByConceptAppliesRowLimit() {
        assertThat(NKODSPARQLDatasetQuery.buildDatasetsByConceptQuery(CONCEPT, 250))
                .contains("LIMIT 250");
    }

    /** An unsafe IRI must be refused before it can close the {@code <...>} and inject SPARQL. */
    @Test
    void datasetsByConceptRejectsUnsafeIri() {
        assertThat(NKODSPARQLDatasetQuery.buildDatasetsByConceptQuery(
                "https://x/a> } UNION { ?ds ?p ?o", 100)).isNull();
        assertThat(NKODSPARQLDatasetQuery.buildDatasetsByConceptQuery("not-an-iri", 100)).isNull();
        assertThat(NKODSPARQLDatasetQuery.buildDatasetsByConceptQuery(null, 100)).isNull();
    }

    /** Raw-UTF-8 IRIs must survive into the query untouched — see {@code SparqlIrisTest}. */
    @Test
    void datasetsByConceptKeepsNonAsciiIriVerbatim() {
        assertThat(NKODSPARQLDatasetQuery.buildDatasetsByConceptQuery(CONCEPT, 100))
                .contains("<" + CONCEPT + ">");
    }
}