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
     * citace is ASCII-only so plain LCASE works. When q is blank, omit the FILTER.
     *
     * <p>Default order (blank q): rok desc, cislo asc (G11).
     *
     * <p>With q, results are ranked by <em>where</em> the needle matched, because a bare
     * {@code CONTAINS} on the whole citation makes "49" match "49/1997", "490/2001" and
     * "1/2049" alike — and a lexical {@code ORDER BY ?citace} then buries the exact číslo
     * match under year- and prefix-matches. Rank tiers:
     * <ol start="0">
     *   <li>číslo equals the needle ("49" → 49/1997, 49/2026) — newest year first</li>
     *   <li>citace starts with the needle ("49/1997" → the exact act, ahead of 149/1997;
     *       also "49" → 490/2001, since a citation always begins with its číslo)</li>
     *   <li>anything else — the needle matched only the year or mid-číslo ("1/2049")</li>
     * </ol>
     * Tier 1 exists because a needle carrying the year ("49/1997") never equals or prefixes
     * ?cislo — which holds "49" alone — so without it the exact law sorts <em>behind</em>
     * 149/1997, 249/1997 and 349/1997, all of which also contain "49/1997". A separate
     * "číslo starts with the needle" tier would be dead code: the citation is
     * {@code <číslo>/<rok> Sb.}, so a číslo-prefix match is always a citation-prefix match.
     *
     * <p><strong>Ranking only helps when few acts share the číslo.</strong> Czech acts
     * renumber yearly, so "49" has ~120 tier-0 matches and a limit of 20 is filled by tier 0
     * alone — 49/2026 … 49/2007, with 49/1997 still off the page. Ranking fixes the
     * <em>fully-qualified</em> query ("49/1997" lands first); for a bare number the real fix
     * is {@code /api/eli/law/search/grouped}, which surfaces the ambiguity instead of hiding
     * it behind a truncated list.
     *
     * <p>Ranking MUST happen inside the query: {@code LIMIT} is applied after {@code ORDER BY},
     * so ranking client-side would truncate the best matches before they are ever ranked.
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
     * Step 1 of the grouped search: the distinct předpis numbers matching the needle, with
     * a true act count each, capped at {@code limit} <em>groups</em>.
     *
     * <p>Row-capping cannot work here. {@link #buildLawSearchQuery}'s {@code CONTAINS} on the
     * whole citation makes "49" match 2 217 acts of which only 120 are numbered 49 (the rest
     * matched a year — "1/2049"), and even číslo-scoped, a short needle is unbounded: "1"
     * prefix-matches 12 037 acts across 1, 10-19, 100-199, 1000+. Any row cap would truncate
     * mid-group and reproduce the very bug grouping fixes. Aggregating server-side caps the
     * <em>groups</em> instead, so cost tracks the answer, not the noise (0.35 s worst case).
     *
     * <p>Prefix, not substring: nobody searching "49" means "1490". {@code STRSTARTS} is both
     * the correct semantic and the cheaper one.
     *
     * <p>Ordered by číslo length then číslo, so the shortest (most exact) numbers come first —
     * "49" before "490" before "4900".
     */
    public static String buildLawNumberGroupsQuery(String q, int limit) {
        boolean hasFilter = q != null && !q.isBlank();
        StringBuilder sb = new StringBuilder();
        // Only ?akt and ?cislo are needed: the other predicates were joined purely to be
        // discarded, and with COUNT(*) (solutions, not subjects) an act carrying two
        // patří-do-sbírky or citace values would be counted twice in its group total.
        // COUNT(DISTINCT ?akt) counts acts.
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
     * Step 2 of the grouped search: every act whose číslo is one of {@code cisla}, newest
     * rok first.
     *
     * <p>Scoped to the numbers step 1 already chose. Note this bounds the result by
     * <em>group count × group size</em>, and group size is itself unbounded — every low číslo
     * carries ~120 acts, so 50 groups is ~6 000 rows. Hence the explicit {@code rowLimit}:
     * without it a broad needle streams thousands of rows across the wire, each mapped to a
     * DTO and serialised into one response. Per-group truncation is representable without
     * lying because {@code LawSearchGroupDto.count} carries the dataset-wide total from
     * step 1's aggregate, independent of how many acts are fetched for display.
     *
     * <p>{@code ORDER BY DESC(?rok)} is kept so the cap keeps the <em>newest</em> acts rather
     * than an arbitrary slice; the číslo-ordering terms are deliberately absent, since the
     * service re-buckets rows by číslo and re-sorts each bucket, which would discard them.
     *
     * <p><strong>Matched via {@code FILTER(STR(?cislo) IN (…))}, deliberately not a
     * {@code VALUES} block.</strong> e-Sbírka stores {@code číslo-předpisu} as an explicitly
     * typed {@code "49"^^xsd:string}, and this Virtuoso does not equate that with the plain
     * literal {@code "49"} — a {@code VALUES} of plain literals matches <em>zero</em> rows.
     * Binding the typed form does not help either: SPARQL 1.1 defines the two as the same
     * term, so Jena renders {@code "49"^^xsd:string} back down to {@code "49"} and the
     * mismatch returns. Comparing {@code STR(?cislo)} sidesteps the datatype entirely
     * (~0.2 s), and is the same workaround {@link #buildLawByNumberYearQuery} already uses.
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
     * <p><strong>Only {@code pořadí} may be required.</strong> {@code citace} and
     * {@code má-předka} are OPTIONAL because whole versions exist without them: verified live
     * 2026-08-23, version {@code …/1997/49/2025-11-01} has 2 508 fragments and <em>zero</em>
     * citace values, so requiring citace returned 0 rows and the entire law rendered blank.
     * Even where citace mostly exists it is missing on ~13% of fragments (296/2198 on
     * 187/2006), 288 of which carry real text. Missing parents are handled by
     * {@code assembleTree}'s path-walk re-parenting, so a null {@code ?parent} is safe.
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
     * <p>The obsah join ({@code obsahuje-fragment/text-fragmentu}) MUST stay OPTIONAL:
     * structural fragments (Část/Hlava/Díl/Oddíl) carry no text body, and a non-optional
     * join silently drops them — breaking the navigable tree. (Verified: ~13% of fragments
     * have no body for sampled versions.) The same applies to {@code citace} and
     * {@code má-předka}: version {@code …/1997/49/2025-11-01} has 2 508 fragments and zero
     * citace values, so requiring it rendered the whole law blank (verified live 2026-08-23).
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
        // Note: we cannot bind inputZneni via PSS because we also need to compare it
        // structurally inside the SELECT projection (BIND). Instead, the version IRI
        // is inlined as a VALUES row, leaving ?zneni as a real variable usable in
        // (?zneni = ?posledniZneni) AS ?isLatest.
        //
        // ?obsah is OPTIONAL because structural fragments (Část/Hlava/...) carry
        // no text body — their resolution should still return citation and
        // version metadata.
        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText("""
                SELECT ?citace ?ucinnostDo ?obsah ((?zneni = ?posledniZneni) AS ?isLatest)
                WHERE {
                  VALUES ?zneni { ?inputZneni }
                  ?inputFragment <%1$scitace-označení-fragmentu-znění-právního-aktu> ?citace .
                  ?inputAkt <%1$smá-poslední-znění> ?posledniZneni .
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
