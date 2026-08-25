package com.dia.ismdtoolbackend.query;

import org.apache.jena.query.QueryFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

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
    void lawByNumberYear_parsesAndUsesExactEqualityNotContains() {
        String q = EsbirkaSPARQLQuery.buildLawByNumberYearQuery("49", 1997);
        assertDoesNotThrow(() -> QueryFactory.create(q));
        assertTrue(q.contains("číslo-předpisu"), "must filter on číslo-předpisu (with č intact)");
        assertTrue(q.contains("rok-předpisu"));
        assertTrue(q.contains("STR(?cislo) = "), "exact equality on number, not CONTAINS");
        assertTrue(q.contains("STR(?rok) = "), "exact equality on year");
        assertFalse(q.contains("CONTAINS"), "must NOT use substring match (would hit 149/1997 etc.)");
        // Values inlined as escaped literals via PSS.
        assertTrue(q.contains("\"49\""));
        assertTrue(q.contains("\"1997\""));
        assertTrue(q.contains("LIMIT 1"));
    }

    @Test
    void lawByNumberYear_numberInjectionIsEscaped() {
        String q = EsbirkaSPARQLQuery.buildLawByNumberYearQuery("49\" ; DROP", 1997);
        assertDoesNotThrow(() -> QueryFactory.create(q));
        assertFalse(q.contains("49\" ; DROP"), "bare malicious string must not appear verbatim");
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
    void versionContent_parsesAndContainsTreePredicatesPlusObsah() {
        String q = EsbirkaSPARQLQuery.buildVersionContentQuery(VERSION_IRI);
        assertDoesNotThrow(() -> QueryFactory.create(q));
        // Same structural backbone as the lean fragment-tree query...
        assertTrue(q.contains("má-fragment-znění"));
        assertTrue(q.contains("má-předka"));
        assertTrue(q.contains("citace-označení-fragmentu-znění-právního-aktu"));
        assertTrue(q.contains("pořadí-fragmentu-znění-právního-aktu"));
        assertTrue(q.contains("ORDER BY ?order"));
        assertTrue(q.contains("<" + VERSION_IRI + ">"),
                "versionIri must be inlined as <iri> via ParameterizedSparqlString.setIri");
        // ...plus the HTML body.
        assertTrue(q.contains("?obsah"), "obsah must be projected");
        assertTrue(q.contains("obsahuje-fragment"));
        assertTrue(q.contains("text-fragmentu"));
    }

    @Test
    void versionContent_obsahJoinIsOptional() {
        // Critical correctness invariant: structural fragments (Část/Hlava/…) carry no
        // text body. A non-OPTIONAL obsah join would silently drop them and break the tree.
        String q = EsbirkaSPARQLQuery.buildVersionContentQuery(VERSION_IRI);
        int optionalCount = q.split("(?i)OPTIONAL").length - 1;
        assertTrue(optionalCount >= 1, "obsah join MUST be OPTIONAL; found " + optionalCount);
        // The obsah chain must sit inside an OPTIONAL block.
        String afterOptional = q.substring(q.toUpperCase().indexOf("OPTIONAL"));
        assertTrue(afterOptional.contains("obsahuje-fragment"),
                "the obsahuje-fragment/text-fragmentu chain must be wrapped in OPTIONAL");
    }

    /**
     * Regression: upstream dropped citace-označení-fragmentu-znění-právního-aktu (0 triples
     * dataset-wide, 2026-08-24). While required, the join returned zero rows and every law
     * rendered blank with HTTP 200. Only pořadí may be required.
     */
    @Test
    void fragmentQueries_requireOnlyPoradi() {
        for (String q : List.of(
                EsbirkaSPARQLQuery.buildVersionContentQuery(VERSION_IRI),
                EsbirkaSPARQLQuery.buildFragmentTreeQuery(VERSION_IRI))) {
            assertDoesNotThrow(() -> QueryFactory.create(q));
            for (String fragile : List.of(
                    "citace-označení-fragmentu-znění-právního-aktu",
                    "má-předka",
                    "obsahuje-fragment")) {
                if (!q.contains(fragile)) {
                    continue; // not projected by this query (the lean tree query has no obsah)
                }
                assertTrue(inOptionalBlock(q, fragile),
                        fragile + " MUST sit inside an OPTIONAL block: a required join that "
                                + "upstream stops populating returns zero rows and the law "
                                + "renders blank. Query:\n" + q);
            }
        }
    }

    @Test
    void resolveFragmentQuery_citaceIsOptional() {
        String fragmentIri = VERSION_IRI + "/dokument/norma/cast_1/par_2/pism_d";
        String q = EsbirkaSPARQLQuery.buildResolveFragmentQuery(fragmentIri, VERSION_IRI, LAW_IRI);
        assertDoesNotThrow(() -> QueryFactory.create(q));
        assertTrue(inOptionalBlock(q, "citace-označení-fragmentu-znění-právního-aktu"),
                "citace MUST be OPTIONAL; upstream no longer publishes it and a required "
                        + "join silently kills every fragment resolution. Query:\n" + q);
    }

    /**
     * True when every occurrence of {@code needle} sits inside an {@code OPTIONAL { ... }}
     * block, determined by brace-depth relative to the enclosing WHERE.
     */
    private static boolean inOptionalBlock(String query, String needle) {
        int from = 0;
        while (true) {
            int at = query.indexOf(needle, from);
            if (at < 0) {
                return from > 0; // all occurrences checked; false if needle absent entirely
            }
            String before = query.substring(0, at);
            int lastOptional = before.toUpperCase().lastIndexOf("OPTIONAL");
            if (lastOptional < 0) {
                return false;
            }
            // The needle is inside that OPTIONAL only if its block has not yet closed.
            String between = before.substring(lastOptional);
            long opens = between.chars().filter(c -> c == '{').count();
            long closes = between.chars().filter(c -> c == '}').count();
            if (opens <= closes) {
                return false;
            }
            from = at + needle.length();
        }
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
