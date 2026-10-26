package com.dia.ismdtoolbackend.query;

import org.apache.jena.query.QueryFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class EsbirkaSPARQLQueryTest {

    private static final String LAW_IRI =
            "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187";
    private static final String VERSION_IRI =
            "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187/2026-04-01";

    @Test
    void lawsByNumbers_matchesOnStrNotValues_forVirtuosoDatatypeQuirk() {
        // číslo-předpisu is a typed "49"^^xsd:string that Virtuoso will not equate with the
        // plain literal "49", so a VALUES block matches zero rows.
        String q = EsbirkaSPARQLQuery.buildLawsByNumbersQuery(java.util.List.of("49", "490"), null, 600);
        assertThatCode(() -> QueryFactory.create(q)).doesNotThrowAnyException();
        assertThat(q).as("must compare via STR(), not VALUES").contains("FILTER(STR(?cislo) IN (");
        assertThat(q).as("a VALUES block silently returns nothing here").doesNotContain("VALUES ?cislo");
        assertThat(q).contains("\"49\"");
        assertThat(q).contains("\"490\"");
        assertThat(q).as("act fetch must carry a row cap").contains("LIMIT 600");
        // Newest-first so the cap keeps recent acts rather than an arbitrary slice.
        assertThat(q).contains("ORDER BY DESC(?rok)");
        // The service re-buckets by číslo, so server-side číslo ordering would be discarded.
        assertThat(q).as("no redundant server-side číslo ordering").doesNotContain("STRLEN");
    }

    @Test
    void lawNumberGroups_aggregatesAndCapsGroups() {
        String q = EsbirkaSPARQLQuery.buildLawNumberGroupsQuery("49", null, 20);
        assertThatCode(() -> QueryFactory.create(q)).doesNotThrowAnyException();
        // COUNT(*) counts solutions, so an act with two patří-do-sbírky or citace values
        // would inflate its group total.
        assertThat(q).as("must count acts, not solutions").contains("COUNT(DISTINCT ?akt)");
        assertThat(q).as("unused join must not be in the aggregate").doesNotContain("rok-předpisu");
        assertThat(q).as("unused join must not be in the aggregate").doesNotContain("patří-do-sbírky");
        assertThat(q).contains("GROUP BY ?cislo");
        // Prefix, not substring: "49" must not match 1490.
        assertThat(q).contains("STRSTARTS(LCASE(STR(?cislo))");
        assertThat(q).doesNotContain("CONTAINS(LCASE(STR(?cislo))");
        // The cap applies to groups — LIMIT sits after GROUP BY.
        assertThat(q.indexOf("GROUP BY")).isLessThan(q.indexOf("LIMIT"));
    }

    @Test
    void lawNumberGroups_blankQOmitsFilter() {
        String q = EsbirkaSPARQLQuery.buildLawNumberGroupsQuery("  ", null, 20);
        assertThatCode(() -> QueryFactory.create(q)).doesNotThrowAnyException();
        assertThat(q).doesNotContain("FILTER");
    }

    @Test
    void lawNumberGroups_yearNarrowsTheAggregateOnTheLexicalGYear() {
        // rok-předpisu is xsd:gYear; STR() reaches its "1997" lexical form. Prefix, not
        // equality — the FE searches per keystroke, so "49/19" must return the 1900s.
        String q = EsbirkaSPARQLQuery.buildLawNumberGroupsQuery("49", "1997", 20);
        assertThatCode(() -> QueryFactory.create(q)).doesNotThrowAnyException();
        assertThat(q).as("the year join is required to filter on it").contains("rok-předpisu");
        assertThat(q).contains("STRSTARTS(STR(?rok)");
        assertThat(q).contains("\"1997\"");
        // Both halves filter: dropping the číslo half would group every act of 1997.
        assertThat(q).contains("STRSTARTS(LCASE(STR(?cislo))");
        // The count must still be year-scoped, so the aggregate — not a later step — filters.
        assertThat(q.indexOf("STRSTARTS(STR(?rok)")).isLessThan(q.indexOf("GROUP BY"));
    }

    @Test
    void lawNumberGroups_omitsTheYearJoinWhenNoYearIsGiven() {
        // A bare "49" must not pay for a join it does not filter on.
        String q = EsbirkaSPARQLQuery.buildLawNumberGroupsQuery("49", null, 20);
        assertThat(q).as("unused join must not be in the aggregate").doesNotContain("rok-předpisu");
        assertThat(q).doesNotContain("?rok");
    }

    @Test
    void lawsByNumbers_repeatsTheYearNarrowingFromStepOne() {
        // Step 1's counts are year-scoped. Without the same filter here the group would
        // display acts the count excludes — and bury the year the user actually asked for.
        String q = EsbirkaSPARQLQuery.buildLawsByNumbersQuery(java.util.List.of("49"), "1997", 600);
        assertThatCode(() -> QueryFactory.create(q)).doesNotThrowAnyException();
        assertThat(q).contains("STRSTARTS(STR(?rok)");
        assertThat(q).contains("\"1997\"");
        assertThat(q).as("číslo matching is unchanged").contains("FILTER(STR(?cislo) IN (");
    }

    @Test
    void lawsByNumbers_blankYearAddsNoFilter() {
        String q = EsbirkaSPARQLQuery.buildLawsByNumbersQuery(java.util.List.of("49"), "  ", 600);
        assertThatCode(() -> QueryFactory.create(q)).doesNotThrowAnyException();
        assertThat(q).doesNotContain("STRSTARTS(STR(?rok)");
    }


    @Test
    void fragmentQueries_onlyPoradiIsRequired_citaceAndParentAreOptional() {
        // Whole versions exist with no citace at all, so requiring it returns zero rows and
        // renders the law blank; obsah is likewise absent on structural fragments.
        for (String q : new String[]{
                EsbirkaSPARQLQuery.buildVersionContentQuery(VERSION_IRI)}) {
            assertThatCode(() -> QueryFactory.create(q)).doesNotThrowAnyException();
            assertThat(q)
                    .as("citace must be OPTIONAL — a required join blanks entire versions")
                    .contains("OPTIONAL { ?fragment <https://slovn\u00EDk.gov.cz/datov\u00FD/sb\u00EDrka/pojem/citace-ozna\u010Den\u00ED-fragmentu-zn\u011Bn\u00ED-pr\u00E1vn\u00EDho-aktu> ?citace }");
            assertThat(q)
                    .as("má-předka must be OPTIONAL — assembleTree re-parents by path walk")
                    .contains("OPTIONAL { ?fragment <https://slovn\u00EDk.gov.cz/datov\u00FD/sb\u00EDrka/pojem/m\u00E1-p\u0159edka> ?parent }");
            // pořadí is the one genuinely required predicate: it is the document order key.
            assertThat(q)
                    .as("pořadí stays required")
                    .contains("?fragment <https://slovn\u00EDk.gov.cz/datov\u00FD/sb\u00EDrka/pojem/po\u0159ad\u00ED-fragmentu-zn\u011Bn\u00ED-pr\u00E1vn\u00EDho-aktu> ?order .");
        }
    }

    @Test
    void lawByNumberYear_parsesAndUsesExactEqualityNotContains() {
        String q = EsbirkaSPARQLQuery.buildLawByNumberYearQuery("49", 1997);
        assertThatCode(() -> QueryFactory.create(q)).doesNotThrowAnyException();
        assertThat(q).as("must filter on číslo-předpisu (with č intact)").contains("číslo-předpisu");
        assertThat(q).contains("rok-předpisu");
        assertThat(q).as("exact equality on number, not CONTAINS").contains("STR(?cislo) = ");
        assertThat(q).as("exact equality on year").contains("STR(?rok) = ");
        assertThat(q).as("must NOT use substring match (would hit 149/1997 etc.)").doesNotContain("CONTAINS");
        // Values inlined as escaped literals via PSS.
        assertThat(q).contains("\"49\"");
        assertThat(q).contains("\"1997\"");
        assertThat(q).contains("LIMIT 1");
    }

    @Test
    void lawByNumberYear_numberInjectionIsEscaped() {
        String q = EsbirkaSPARQLQuery.buildLawByNumberYearQuery("49\" ; DROP", 1997);
        assertThatCode(() -> QueryFactory.create(q)).doesNotThrowAnyException();
        assertThat(q).as("bare malicious string must not appear verbatim").doesNotContain("49\" ; DROP");
    }

    @Test
    void versionList_parsesAndContainsKeyPredicates() {
        String q = EsbirkaSPARQLQuery.buildVersionListQuery(LAW_IRI);
        assertThatCode(() -> QueryFactory.create(q)).doesNotThrowAnyException();
        assertThat(q).contains("má-poslední-znění");
        assertThat(q).contains("má-znění");
        assertThat(q).contains("účinnost-znění-od");
        assertThat(q).contains("účinnost-znění-do");
        assertThat(q).contains("má-typ-znění-právního-aktu");
        assertThat(q).contains("(?zneni = ?posledniZneni) AS ?isLatest");
        assertThat(q).contains("ORDER BY DESC(?ucinnostOd)");
        assertThat(q)
                .as("lawIri must be inlined as <iri> via ParameterizedSparqlString.setIri")
                .contains("<" + LAW_IRI + ">");
    }

    @Test
    void versionContent_parsesAndContainsTreePredicatesPlusObsah() {
        String q = EsbirkaSPARQLQuery.buildVersionContentQuery(VERSION_IRI);
        assertThatCode(() -> QueryFactory.create(q)).doesNotThrowAnyException();
        // Tree backbone...
        assertThat(q).contains("má-fragment-znění");
        assertThat(q).contains("má-předka");
        assertThat(q).contains("citace-označení-fragmentu-znění-právního-aktu");
        assertThat(q).contains("pořadí-fragmentu-znění-právního-aktu");
        assertThat(q).contains("ORDER BY ?order");
        assertThat(q)
                .as("versionIri must be inlined as <iri> via ParameterizedSparqlString.setIri")
                .contains("<" + VERSION_IRI + ">");
        // ...plus the HTML body.
        assertThat(q).as("obsah must be projected").contains("?obsah");
        assertThat(q).contains("obsahuje-fragment");
        assertThat(q).contains("text-fragmentu");
    }

    @Test
    void versionContent_obsahJoinIsOptional() {
        // Critical correctness invariant: structural fragments (Část/Hlava/…) carry no
        // text body. A non-OPTIONAL obsah join would silently drop them and break the tree.
        String q = EsbirkaSPARQLQuery.buildVersionContentQuery(VERSION_IRI);
        int optionalCount = q.split("(?i)OPTIONAL").length - 1;
        assertThat(optionalCount).as("obsah join MUST be OPTIONAL; found " + optionalCount).isGreaterThanOrEqualTo(1);
        // The obsah chain must sit inside an OPTIONAL block.
        String afterOptional = q.substring(q.toUpperCase().indexOf("OPTIONAL"));
        assertThat(afterOptional)
                .as("the obsahuje-fragment/text-fragmentu chain must be wrapped in OPTIONAL")
                .contains("obsahuje-fragment");
    }

    /**
     * Regression: upstream dropped citace-označení-fragmentu-znění-právního-aktu (0 triples
     * dataset-wide, 2026-08-24). While required, the join returned zero rows and every law
     * rendered blank with HTTP 200. Only pořadí may be required.
     */
    @Test
    void fragmentQueries_requireOnlyPoradi() {
        for (String q : List.of(
                EsbirkaSPARQLQuery.buildVersionContentQuery(VERSION_IRI))) {
            assertThatCode(() -> QueryFactory.create(q)).doesNotThrowAnyException();
            for (String fragile : List.of(
                    "citace-označení-fragmentu-znění-právního-aktu",
                    "má-předka",
                    "obsahuje-fragment")) {
                if (!q.contains(fragile)) {
                    continue; // not projected by this query
                }
                assertThat(inOptionalBlock(q, fragile))
                        .as(fragile + " MUST sit inside an OPTIONAL block: a required join that "
                                + "upstream stops populating returns zero rows and the law "
                                + "renders blank. Query:\n" + q)
                        .isTrue();
            }
        }
    }

    @Test
    void resolveFragmentQuery_citaceIsOptional() {
        String fragmentIri = VERSION_IRI + "/dokument/norma/cast_1/par_2/pism_d";
        String q = EsbirkaSPARQLQuery.buildResolveFragmentQuery(fragmentIri, VERSION_IRI, LAW_IRI);
        assertThatCode(() -> QueryFactory.create(q)).doesNotThrowAnyException();
        assertThat(inOptionalBlock(q, "citace-označení-fragmentu-znění-právního-aktu"))
                .as("citace MUST be OPTIONAL; upstream no longer publishes it and a required "
                        + "join silently kills every fragment resolution. Query:\n" + q)
                .isTrue();
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

        assertThatCode(() -> QueryFactory.create(q)).doesNotThrowAnyException();
        assertThat(q).contains("citace-označení-fragmentu-znění-právního-aktu");
        assertThat(q).contains("má-poslední-znění");
        assertThat(q).contains("účinnost-znění-do");
        assertThat(q).contains("(?zneni = ?posledniZneni) AS ?isLatest");
        assertThat(q).contains("LIMIT 1");
        assertThat(q).as("fragmentIri must be inlined").contains("<" + fragmentIri + ">");
        assertThat(q).as("versionIri must be inlined").contains("<" + VERSION_IRI + ">");
        assertThat(q).as("lawIri must be inlined").contains("<" + LAW_IRI + ">");
    }

    @Test
    void buildResolveFragmentQuery_projectsObsahViaOptionalChain() {
        String fragmentIri = VERSION_IRI + "/dokument/norma/cast_1/par_2/pism_d";
        String q = EsbirkaSPARQLQuery.buildResolveFragmentQuery(fragmentIri, VERSION_IRI, LAW_IRI);

        assertThat(q).as("obsah must be in SELECT/WHERE").contains("?obsah");
        assertThat(q).as("obsah is fetched via obsahuje-fragment/text-fragmentu chain").contains("obsahuje-fragment");
        assertThat(q).as("obsah is fetched via obsahuje-fragment/text-fragmentu chain").contains("text-fragmentu");
        int optionalCount = q.split("(?i)OPTIONAL").length - 1;
        assertThat(optionalCount)
                .as("Both ucinnost-znění-do and obsah must be OPTIONAL; found " + optionalCount)
                .isGreaterThanOrEqualTo(2);
    }
}
