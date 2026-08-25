package com.dia.ismdtoolbackend.query;

import org.apache.jena.query.ParameterizedSparqlString;

import java.util.List;

public class EsbirkaSPARQLQuery {

    // Predicates verified 2026-04-30 against https://opendata.eselpoint.gov.cz/sparql:
    //   Law      type:        datový/sbírka/pojem/právní-akt
    //   Law      citation:    datový/sbírka/pojem/citace-právního-aktu        ("187/2006 Sb.")
    //   Law      number:      datový/sbírka/pojem/číslo-předpisu              (xsd:string)
    //   Law      year:        datový/sbírka/pojem/rok-předpisu                (xsd:gYear)
    //   Law      sbírka:      datový/sbírka/pojem/patří-do-sbírky             ("sb"/"ul0"/"ul1"/"sm")
    //   Law      latestVer:   datový/sbírka/pojem/má-poslední-znění
    //   Law      anyVer:      datový/sbírka/pojem/má-znění
    //   Version  type:        datový/sbírka/pojem/znění-právního-aktu
    //   Version  validFrom:   datový/sbírka/pojem/účinnost-znění-od           (xsd:date)
    //   Version  validTo:     datový/sbírka/pojem/účinnost-znění-do           (xsd:date)
    //   Version  versionType: datový/sbírka/pojem/má-typ-znění-právního-aktu
    //   Version  fragment:    datový/sbírka/pojem/má-fragment-znění
    //   Fragment type:        datový/sbírka/pojem/označení-fragmentu-znění-právního-aktu
    //   Fragment citation:    datový/sbírka/pojem/citace-označení-fragmentu-znění-právního-aktu
    //   Fragment parent:      datový/sbírka/pojem/má-předka
    //   Fragment order:       datový/sbírka/pojem/pořadí-fragmentu-znění-právního-aktu  (hex string, lex-sortable)
    //
    // Fragment kinds (verified 2026-05-04 against sample version 187/2006/2026-04-01):
    //   par, odst, pism, bod, ppc, dil, hlava, oddil, cast, frag,
    //   plus structural parents: dokument/{norma,prefix,postfix,poznamkypodcarou}.

    private static final String NS = "https://slovník.gov.cz/datový/sbírka/pojem/";

    /**
     * Search laws by citation substring (server-side CONTAINS on citace-právního-aktu).
     * Blank q omits the FILTER and orders rok desc, číslo asc.
     *
     * <p>With q, rows are ranked by where the needle matched: 0 = číslo equals it,
     * 1 = citace starts with it, 2 = anything else; then rok desc, citace.
     */
    public static String buildLawSearchQuery(String q, int limit) {
        boolean hasFilter = q != null && !q.isBlank();
        StringBuilder sb = new StringBuilder();
        sb.append("PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n");
        sb.append("SELECT ?akt ?citace ?cislo ?rok ?sbirka WHERE {\n");
        sb.append("  ?akt a <").append(NS).append("právní-akt> ;\n");
        sb.append("       <").append(NS).append("citace-právního-aktu> ?citace ;\n");
        sb.append("       <").append(NS).append("číslo-předpisu> ?cislo ;\n");
        sb.append("       <").append(NS).append("rok-předpisu> ?rok ;\n");
        sb.append("       <").append(NS).append("patří-do-sbírky> ?sbirka .\n");
        if (hasFilter) {
            sb.append("  FILTER(CONTAINS(LCASE(STR(?citace)), LCASE(?qNeedle)))\n");
            sb.append("  BIND(IF(LCASE(STR(?cislo)) = LCASE(?qNeedle), 0,\n");
            sb.append("       IF(STRSTARTS(LCASE(STR(?citace)), LCASE(?qNeedle)), 1, 2)) AS ?rank)\n");
        }
        sb.append("}\n");
        if (hasFilter) {
            sb.append("ORDER BY ?rank DESC(?rok) ?citace\n");
        } else {
            sb.append("ORDER BY DESC(?rok) ?cislo\n");
        }
        sb.append("LIMIT ").append(limit);

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText(sb.toString());
        if (hasFilter) {
            pss.setLiteral("qNeedle", q);
        }
        return pss.toString();
    }

    /**
     * Step 1 of the grouped search: distinct předpis numbers prefix-matching the needle, each
     * with its dataset-wide act count, capped at {@code limit} <em>groups</em>.
     *
     * <p>Ordered by číslo length then číslo, so the shortest (most exact) numbers come first.
     */
    public static String buildLawNumberGroupsQuery(String q, int limit) {
        boolean hasFilter = q != null && !q.isBlank();
        StringBuilder sb = new StringBuilder();
        // COUNT(DISTINCT ?akt), not COUNT(*): an act with several patří-do-sbírky or citace
        // values yields several solutions.
        sb.append("SELECT ?cislo (COUNT(DISTINCT ?akt) AS ?pocet) WHERE {\n");
        sb.append("  ?akt a <").append(NS).append("právní-akt> ;\n");
        sb.append("       <").append(NS).append("citace-právního-aktu> ?citace ;\n");
        sb.append("       <").append(NS).append("číslo-předpisu> ?cislo .\n");
        if (hasFilter) {
            sb.append("  FILTER(STRSTARTS(LCASE(STR(?cislo)), LCASE(?qNeedle)))\n");
        }
        sb.append("}\n");
        sb.append("GROUP BY ?cislo\n");
        sb.append("ORDER BY STRLEN(STR(?cislo)) ?cislo\n");
        sb.append("LIMIT ").append(limit);

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText(sb.toString());
        if (hasFilter) {
            pss.setLiteral("qNeedle", q);
        }
        return pss.toString();
    }

    /**
     * Step 2 of the grouped search: every act whose číslo is one of {@code cisla}, newest rok
     * first, capped at {@code rowLimit} rows. Group size is unbounded, so the cap keeps the
     * newest acts of each; the dataset-wide totals come from step 1's aggregate.
     *
     * <p>Matched via {@code FILTER(STR(?cislo) IN (…))}, not {@code VALUES}: číslo-předpisu is
     * a typed {@code "49"^^xsd:string} that Virtuoso will not equate with a plain literal, so a
     * {@code VALUES} block matches zero rows. Same workaround as
     * {@link #buildLawByNumberYearQuery}.
     */
    public static String buildLawsByNumbersQuery(List<String> cisla, int rowLimit) {
        StringBuilder needles = new StringBuilder();
        for (int i = 0; i < cisla.size(); i++) {
            if (i > 0) {
                needles.append(", ");
            }
            needles.append("?c").append(i);
        }
        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText("""
                SELECT ?akt ?citace ?cislo ?rok ?sbirka WHERE {
                  ?akt a <%1$správní-akt> ;
                       <%1$scitace-právního-aktu> ?citace ;
                       <%1$sčíslo-předpisu> ?cislo ;
                       <%1$srok-předpisu> ?rok ;
                       <%1$spatří-do-sbírky> ?sbirka .
                  FILTER(STR(?cislo) IN (%2$s))
                }
                ORDER BY DESC(?rok) ?citace
                LIMIT %3$d
                """.formatted(NS, needles, rowLimit));
        for (int i = 0; i < cisla.size(); i++) {
            pss.setLiteral("c" + i, cisla.get(i));
        }
        return pss.toString();
    }

    /**
     * Exact law lookup by predpis number + year (e.g. "49" + 1997 → 49/1997 Sb.).
     * Uses equality on číslo-předpisu / rok-předpisu, NOT a citation substring match —
     * a CONTAINS("49/1997") would wrongly match "149/1997", "249/1997", etc.
     * číslo is xsd:string; rok is xsd:gYear — both compared via STR() for robustness.
     */
    public static String buildLawByNumberYearQuery(String number, int year) {
        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText("""
                SELECT ?akt ?citace ?cislo ?rok ?sbirka WHERE {
                  ?akt a <%1$správní-akt> ;
                       <%1$scitace-právního-aktu> ?citace ;
                       <%1$sčíslo-předpisu> ?cislo ;
                       <%1$srok-předpisu> ?rok ;
                       <%1$spatří-do-sbírky> ?sbirka .
                  FILTER(STR(?cislo) = ?numNeedle && STR(?rok) = ?yearNeedle)
                }
                LIMIT 1
                """.formatted(NS));
        pss.setLiteral("numNeedle", number);
        pss.setLiteral("yearNeedle", String.valueOf(year));
        return pss.toString();
    }

    /**
     * Versions for a given law, ordered newest-first by účinnost-znění-od.
     * Latest flagged via equality with má-poslední-znění.
     * lawIri must be pre-validated by SparqlIriValidator.isEsbirkaEliIri at the controller boundary.
     */
    public static String buildVersionListQuery(String lawIri) {
        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText("""
                PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

                SELECT ?zneni ?ucinnostOd ?ucinnostDo ?typ ((?zneni = ?posledniZneni) AS ?isLatest)
                WHERE {
                  ?inputAkt <%1$smá-poslední-znění> ?posledniZneni ;
                            <%1$smá-znění>           ?zneni .
                  OPTIONAL { ?zneni <%1$súčinnost-znění-od> ?ucinnostOd }
                  OPTIONAL { ?zneni <%1$súčinnost-znění-do> ?ucinnostDo }
                  OPTIONAL { ?zneni <%1$smá-typ-znění-právního-aktu> ?typ }
                }
                ORDER BY DESC(?ucinnostOd)
                """.formatted(NS));
        pss.setIri("inputAkt", lawIri);
        return pss.toString();
    }

    /**
     * All fragments of a given version with parent edge and lex-sortable order key.
     * Top-level fragments have parent = <versionIri>/dokument/norma.
     * versionIri must be pre-validated by SparqlIriValidator.isEsbirkaEliIri at the controller boundary.
     *
     * <p>Only {@code pořadí} may be required — a required join upstream stops populating
     * returns zero rows and renders the law blank. {@code citace} is absent dataset-wide and
     * callers derive it from IRI path segments; document roots carry no {@code má-předka}.
     */
    public static String buildFragmentTreeQuery(String versionIri) {
        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText("""
                PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

                SELECT ?fragment ?parent ?citace ?order
                WHERE {
                  ?inputZneni <%1$smá-fragment-znění> ?fragment .
                  ?fragment <%1$spořadí-fragmentu-znění-právního-aktu> ?order .
                  OPTIONAL { ?fragment <%1$smá-předka> ?parent }
                  OPTIONAL { ?fragment <%1$scitace-označení-fragmentu-znění-právního-aktu> ?citace }
                }
                ORDER BY ?order
                """.formatted(NS));
        pss.setIri("inputZneni", versionIri);
        return pss.toString();
    }

    /**
     * Whole-version content: every fragment of a version with its parent edge, citation,
     * lex-sortable order key and rendered HTML body (obsah) in one round-trip, for delivering
     * the full law text without per-fragment {@code /resolve} calls.
     *
     * <p>Only {@code pořadí} may be required — a required join upstream stops populating
     * returns zero rows and renders the law blank. {@code obsah} is absent on structural
     * fragments, {@code citace} dataset-wide (derived from IRI path segments instead), and
     * {@code parent} on document roots.
     *
     * <p>versionIri must be pre-validated by SparqlIriValidator.isEsbirkaEliIri at the
     * controller boundary.
     */
    public static String buildVersionContentQuery(String versionIri) {
        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText("""
                PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

                SELECT ?fragment ?parent ?citace ?order ?obsah
                WHERE {
                  ?inputZneni <%1$smá-fragment-znění> ?fragment .
                  ?fragment <%1$spořadí-fragmentu-znění-právního-aktu> ?order .
                  OPTIONAL { ?fragment <%1$smá-předka> ?parent }
                  OPTIONAL { ?fragment <%1$scitace-označení-fragmentu-znění-právního-aktu> ?citace }
                  OPTIONAL { ?fragment <%1$sobsahuje-fragment>/<%1$stext-fragmentu> ?obsah }
                }
                ORDER BY ?order
                """.formatted(NS));
        pss.setIri("inputZneni", versionIri);
        return pss.toString();
    }

    /**
     * Resolve a single fragment IRI to its display citation + parent version's
     * end-of-validity date + is-latest flag. Caller passes pre-derived parent
     * versionIri and lawIri (from {@link com.dia.ismdtoolbackend.utility.eli.EsbirkaEliParser})
     * so we avoid a {@code má-předka+} property-path traversal.
     *
     * <p>All three IRIs MUST be canonical-host IRIs and pre-validated via
     * {@link com.dia.ismdtoolbackend.utility.security.SparqlIriValidator#isEsbirkaEliIri(String)}.
     */
    public static String buildResolveFragmentQuery(String fragmentIri, String versionIri, String lawIri) {
        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText("""
                SELECT ?citace ?ucinnostDo ?obsah ((?zneni = ?posledniZneni) AS ?isLatest)
                WHERE {
                  VALUES ?zneni { ?inputZneni }
                  ?inputAkt <%1$smá-poslední-znění> ?posledniZneni .
                  OPTIONAL { ?inputFragment <%1$scitace-označení-fragmentu-znění-právního-aktu> ?citace }
                  OPTIONAL { ?zneni <%1$súčinnost-znění-do> ?ucinnostDo }
                  OPTIONAL { ?inputFragment <%1$sobsahuje-fragment>/<%1$stext-fragmentu> ?obsah }
                }
                LIMIT 1
                """.formatted(NS));
        pss.setIri("inputFragment", fragmentIri);
        pss.setIri("inputZneni", versionIri);
        pss.setIri("inputAkt", lawIri);
        return pss.toString();
    }

    private EsbirkaSPARQLQuery() {}
}
