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
     */
    public static String buildFragmentTreeQuery(String versionIri) {
        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText("""
                PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

                SELECT ?fragment ?parent ?citace ?order
                WHERE {
                  ?inputZneni <%1$smá-fragment-znění> ?fragment .
                  ?fragment <%1$smá-předka> ?parent ;
                            <%1$scitace-označení-fragmentu-znění-právního-aktu> ?citace ;
                            <%1$spořadí-fragmentu-znění-právního-aktu> ?order .
                }
                ORDER BY ?order
                """.formatted(NS));
        pss.setIri("inputZneni", versionIri);
        return pss.toString();
    }

    private EsbirkaSPARQLQuery() {}
}
