package com.dia.ismdtoolbackend.query;

import org.apache.jena.query.QueryFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsbirkaSPARQLQueryTest {

    private static final String LAW_IRI =
            "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187";
    private static final String VERSION_IRI =
            "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187/2026-04-01";

    @Test
    void lawSearch_emptyQ_parsesAndUsesRokDescOrder() {
        String q = EsbirkaSPARQLQuery.buildLawSearchQuery(null, 20);
        assertDoesNotThrow(() -> QueryFactory.create(q));
        assertTrue(q.contains("https://slovník.gov.cz/datový/sbírka/pojem/právní-akt"));
        assertTrue(q.contains("citace-právního-aktu"));
        assertTrue(q.contains("ORDER BY DESC(?rok) ?cislo"), "empty-q must order by rok desc, cislo asc");
        assertTrue(q.contains("LIMIT 20"));
        assertFalse(q.contains("FILTER"), "empty-q must NOT include FILTER block");
        assertFalse(q.contains("CONTAINS"));
    }

    @Test
    void lawSearch_blankQ_treatedAsEmpty() {
        String q = EsbirkaSPARQLQuery.buildLawSearchQuery("   ", 20);
        assertFalse(q.contains("FILTER"));
    }

    @Test
    void lawSearch_withQ_parsesAndAddsFilterAndCitaceOrder() {
        String q = EsbirkaSPARQLQuery.buildLawSearchQuery("187/2006", 50);
        assertDoesNotThrow(() -> QueryFactory.create(q));
        assertTrue(q.contains("FILTER(CONTAINS(LCASE(STR(?citace)), LCASE("));
        // Jena escapes the literal — verify the value is present (not as a raw substring exposed to injection).
        assertTrue(q.contains("\"187/2006\""));
        assertTrue(q.contains("ORDER BY ?citace"));
        assertFalse(q.contains("ORDER BY DESC(?rok)"));
        assertTrue(q.contains("LIMIT 50"));
    }

    @Test
    void lawSearch_quoteInjectionAttempt_isEscaped() {
        // Attempt to break out of the FILTER literal. Jena must escape this.
        String malicious = "foo\"; DROP";
        String q = EsbirkaSPARQLQuery.buildLawSearchQuery(malicious, 20);
        assertDoesNotThrow(() -> QueryFactory.create(q),
                "Injection attempt must still produce a parseable SPARQL query");
        // Raw bare quote must NOT appear unescaped in the rendered query.
        assertFalse(q.contains("foo\"; DROP"),
                "Bare unescaped malicious string must not appear verbatim in query");
    }

    @Test
    void versionList_parsesAndContainsKeyPredicates() {
        String q = EsbirkaSPARQLQuery.buildVersionListQuery(LAW_IRI);
        assertDoesNotThrow(() -> QueryFactory.create(q));
        assertTrue(q.contains("má-poslední-znění"));
        assertTrue(q.contains("má-znění"));
        assertTrue(q.contains("účinnost-znění-od"));
        assertTrue(q.contains("účinnost-znění-do"));
        assertTrue(q.contains("má-typ-znění-právního-aktu"));
        assertTrue(q.contains("(?zneni = ?posledniZneni) AS ?isLatest"));
        assertTrue(q.contains("ORDER BY DESC(?ucinnostOd)"));
        assertTrue(q.contains("<" + LAW_IRI + ">"),
                "lawIri must be inlined as <iri> via ParameterizedSparqlString.setIri");
    }

    @Test
    void fragmentTree_parsesAndContainsKeyPredicates() {
        String q = EsbirkaSPARQLQuery.buildFragmentTreeQuery(VERSION_IRI);
        assertDoesNotThrow(() -> QueryFactory.create(q));
        assertTrue(q.contains("má-fragment-znění"));
        assertTrue(q.contains("má-předka"));
        assertTrue(q.contains("citace-označení-fragmentu-znění-právního-aktu"));
        assertTrue(q.contains("pořadí-fragmentu-znění-právního-aktu"));
        assertTrue(q.contains("ORDER BY ?order"));
        assertTrue(q.contains("<" + VERSION_IRI + ">"),
                "versionIri must be inlined as <iri> via ParameterizedSparqlString.setIri");
    }

    @Test
    void buildResolveFragmentQuery_bindsAllThreeIris() {
        String fragmentIri = VERSION_IRI + "/dokument/norma/cast_1/par_2/pism_d";
        String q = EsbirkaSPARQLQuery.buildResolveFragmentQuery(fragmentIri, VERSION_IRI, LAW_IRI);

        assertDoesNotThrow(() -> QueryFactory.create(q));
        assertTrue(q.contains("citace-označení-fragmentu-znění-právního-aktu"));
        assertTrue(q.contains("má-poslední-znění"));
        assertTrue(q.contains("účinnost-znění-do"));
        assertTrue(q.contains("(?zneni = ?posledniZneni) AS ?isLatest"));
        assertTrue(q.contains("LIMIT 1"));
        assertTrue(q.contains("<" + fragmentIri + ">"), "fragmentIri must be inlined");
        assertTrue(q.contains("<" + VERSION_IRI + ">"), "versionIri must be inlined");
        assertTrue(q.contains("<" + LAW_IRI + ">"), "lawIri must be inlined");
    }

    @Test
    void buildResolveFragmentQuery_projectsObsahViaOptionalChain() {
        String fragmentIri = VERSION_IRI + "/dokument/norma/cast_1/par_2/pism_d";
        String q = EsbirkaSPARQLQuery.buildResolveFragmentQuery(fragmentIri, VERSION_IRI, LAW_IRI);

        assertTrue(q.contains("?obsah"), "obsah must be in SELECT/WHERE");
        assertTrue(q.contains("obsahuje-fragment"),
                "obsah is fetched via obsahuje-fragment/text-fragmentu chain");
        assertTrue(q.contains("text-fragmentu"),
                "obsah is fetched via obsahuje-fragment/text-fragmentu chain");
        int optionalCount = q.split("(?i)OPTIONAL").length - 1;
        assertTrue(optionalCount >= 2,
                "Both ucinnost-znění-do and obsah must be OPTIONAL; found " + optionalCount);
    }
}
