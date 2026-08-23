package com.dia.ismdtoolbackend.query;

import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executes {@link EsbirkaSPARQLQuery#buildLawSearchQuery} against an in-memory dataset to
 * assert the <em>actual result order</em>, not just the query text.
 *
 * <p>Guards the relevance ranking: {@code CONTAINS} on the whole citation makes "49" match
 * "49/1997", "490/2001" and "1/2049" alike, so without a rank the exact law number is buried
 * behind year-only matches. Fixture mirrors real e-Sbírka rows (verified live 2026-08-23).
 */
class EsbirkaLawSearchRankingTest {

    private static final String NS = "https://slovník.gov.cz/datový/sbírka/pojem/";
    private static final String BASE = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/";

    private Model model;

    @BeforeEach
    void setUp() {
        model = ModelFactory.createDefaultModel();
        // Real shapes that all contain "49": the law itself, longer čísla sharing the
        // prefix, a law whose YEAR contains 49, and one where 49 sits mid-číslo.
        law("49", 1997);
        law("49", 2026);
        law("149", 1997);
        law("249", 1997);
        law("349", 1997);
        law("490", 2001);
        law("1", 2049);
        law("2", 1949);
    }

    private void law(String cislo, int rok) {
        Resource akt = model.createResource(BASE + rok + "/" + cislo);
        akt.addProperty(org.apache.jena.vocabulary.RDF.type, model.createResource(NS + "právní-akt"));
        akt.addProperty(model.createProperty(NS + "citace-právního-aktu"), cislo + "/" + rok + " Sb.");
        akt.addProperty(model.createProperty(NS + "číslo-předpisu"), cislo);
        akt.addProperty(model.createProperty(NS + "rok-předpisu"), String.valueOf(rok));
        akt.addProperty(model.createProperty(NS + "patří-do-sbírky"), "sb");
    }

    private List<String> citationsFor(String q, int limit) {
        String sparql = EsbirkaSPARQLQuery.buildLawSearchQuery(q, limit);
        List<String> out = new ArrayList<>();
        try (QueryExecution qe = QueryExecutionFactory.create(QueryFactory.create(sparql), model)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                out.add(s.getLiteral("citace").getString());
            }
        }
        return out;
    }

    @Test
    void bareNumberPutsExactCisloMatchesFirstNewestYearFirst() {
        List<String> out = citationsFor("49", 20);

        // Both číslo=49 rows lead, newest year first — ahead of 149/249/349/490 and the
        // year-only matches (1/2049, 2/1949).
        assertEquals("49/2026 Sb.", out.get(0));
        assertEquals("49/1997 Sb.", out.get(1));
        assertTrue(out.indexOf("49/1997 Sb.") < out.indexOf("149/1997 Sb."),
                "exact číslo match must precede longer čísla containing it");
        assertTrue(out.indexOf("49/1997 Sb.") < out.indexOf("1/2049 Sb."),
                "číslo match must precede a law matched only on its year");
    }

    @Test
    void fullCitationPutsTheExactLawFirst() {
        // The reported symptom: "49/1997" never equals or prefixes ?cislo (which is "49"),
        // so without the citation-prefix tier the exact law sorted BEHIND 149/249/349.
        List<String> out = citationsFor("49/1997", 20);

        assertEquals("49/1997 Sb.", out.get(0), "the exact law must be the first result");
        assertTrue(out.contains("149/1997 Sb.") && out.indexOf("149/1997 Sb.") > 0);
    }

    @Test
    void cisloPrefixMatchesOutrankYearOnlyMatches() {
        List<String> out = citationsFor("49", 20);
        assertTrue(out.indexOf("490/2001 Sb.") < out.indexOf("1/2049 Sb."),
                "číslo-prefix match must outrank a year-only match");
    }

    @Test
    void yearOnlyMatchesAreStillReturned() {
        // Ranking demotes, never drops — a needle matching only the year is still a hit.
        List<String> out = citationsFor("49", 20);
        assertTrue(out.contains("1/2049 Sb."));
        assertTrue(out.contains("2/1949 Sb."));
        assertEquals(8, out.size(), "every CONTAINS match is returned, only reordered");
    }

    @Test
    void limitKeepsTheBestMatchesNotAnArbitrarySlice() {
        // LIMIT applies after ORDER BY: ranking inside the query means a tight limit
        // returns the most relevant rows. Ranking client-side would truncate first.
        List<String> out = citationsFor("49", 2);
        assertEquals(List.of("49/2026 Sb.", "49/1997 Sb."), out);
    }

    @Test
    void emptyQueryUsesRokDescCisloAscOrder() {
        List<String> out = citationsFor(null, 20);
        assertEquals("1/2049 Sb.", out.get(0), "newest rok first when no needle");
        assertEquals("49/2026 Sb.", out.get(1));
    }
}