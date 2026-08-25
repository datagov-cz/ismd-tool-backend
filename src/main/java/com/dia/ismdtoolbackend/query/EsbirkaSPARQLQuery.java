package com.dia.ismdtoolbackend.query;

import org.apache.jena.query.ParameterizedSparqlString;

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
     * citace is ASCII-only so plain LCASE works. When q is blank, omit the FILTER.
     * Default order: rok desc, cislo asc (G11). With q: citace lex asc (predictable).
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
        }
        sb.append("}\n");
        if (hasFilter) {
            sb.append("ORDER BY ?citace\n");
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
     * <p>Only pořadí may be required. citace is OPTIONAL: upstream dropped
     * citace-označení-fragmentu-znění-právního-aktu (0 triples dataset-wide as of 2026-08-24),
     * and a required join returns zero rows — the whole law renders empty. Callers derive the
     * citation from IRI path segments when it is absent.
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
     * Whole-version content query: every fragment of a version with its parent edge,
     * citation, lex-sortable order key, AND its rendered HTML body (obsah) in a single
     * round-trip. Used to deliver the full law text to the FE for in-document browsing
     * without per-fragment {@code /resolve} calls.
     *
     * <p>Only {@code pořadí} may be required. Every other join is OPTIONAL, because a
     * required join that upstream stops populating returns zero rows and the law renders
     * blank (HTTP 200, {@code fragments: []}) rather than erroring:
     * <ul>
     *   <li>{@code obsah} — structural fragments (Část/Hlava/Díl/Oddíl) carry no text body
     *       (~13% of fragments for sampled versions).</li>
     *   <li>{@code citace} — upstream dropped citace-označení-fragmentu-znění-právního-aktu
     *       entirely (0 triples dataset-wide as of 2026-08-24); the citation is derived from
     *       IRI path segments when absent.</li>
     *   <li>{@code parent} — document roots ({@code /dokument/prefix}) have no má-předka.</li>
     * </ul>
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
