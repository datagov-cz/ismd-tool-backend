package com.dia.ismdtoolbackend.query;

import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;

/**
 * SPARQL queries against the NKOD catalogue (dcat:Dataset), for issue #123.
 *
 * <h2>Every query is GRAPH-scoped, and must stay that way</h2>
 * Catalogue triples live only inside named graphs. {@code ?ds a dcat:Dataset} matches
 * (the union default graph covers it), but an unscoped {@code <iri> ?p ?o} returns
 * <em>nothing</em> — which reads as a broken query rather than a missing graph.
 *
 * <h2>No full-text index here</h2>
 * {@code bif:contains} returns zero rows on this endpoint, even for a plain ASCII term.
 * That is not the accent-sensitivity of the NKD Virtuoso — there is no usable FT index at
 * all. Search is therefore served from the harvested in-memory snapshot rather than by a
 * query; see {@code NkodDatasetSnapshotHolder}.
 *
 * <h2>Sorted deep pagination is impossible</h2>
 * {@code ORDER BY} combined with {@code OFFSET} fails with HTTP 500 once offset+limit
 * exceeds 10000 ({@code Virtuoso 22023 Error SR353}). The cap is on the sort, not the
 * offset. This is the second reason listing is served from a harvest: paging the
 * unfiltered catalogue alphabetically cannot be done server-side.
 */
public final class NKODSPARQLDatasetQuery {

    private NKODSPARQLDatasetQuery() {
    }

    private static final String PREFIXES = """
            PREFIX dcat: <http://www.w3.org/ns/dcat#>
            PREFIX dcterms: <http://purl.org/dc/terms/>
            """;

    /** OFN "datové rozhraní": the annotation linking a dataset to the concepts it describes. */
    public static final String TYKA_SE_POJMU =
            "https://slovník.gov.cz/veřejný-sektor/pojem/týká-se-pojmu";

    /**
     * Harvests the whole catalogue: one row per dataset per title/description language.
     *
     * <p>Intentionally unsorted — see the SR353 note above; ordering is applied in Java once
     * harvested. Measured at ~1.7s / 2.1MB gzipped for ~30k datasets, with gzip supplied by
     * {@code HttpSparqlExecutor}.
     */
    public static String buildHarvestQuery(int maxRows) {
        return PREFIXES + """
                SELECT ?ds ?nazev ?nazevLang ?popis ?popisLang WHERE {
                  GRAPH ?g {
                    ?ds a dcat:Dataset .
                    ?ds dcterms:title ?nazev .
                    OPTIONAL { ?ds dcterms:description ?popis }
                  }
                  BIND(LANG(?nazev) AS ?nazevLang)
                  BIND(LANG(?popis) AS ?popisLang)
                }
                LIMIT %d
                """.formatted(maxRows);
    }

    /**
     * Full detail of one dataset: labels, descriptions, and the concepts it is annotated with
     * via {@code týká-se-pojmu}.
     *
     * <p>Returns null when {@code datasetIri} is not a safe HTTP IRI, so callers surface a
     * 400 rather than interpolating unvalidated input. Note the IRI is publisher-hosted
     * (e.g. {@code data.md.gov.cz/...}) and must <em>not</em> be validated against a
     * {@code data.gov.cz} prefix.
     */
    public static String buildDatasetDetailQuery(String datasetIri) {
        if (!SparqlIriValidator.isSafeHttpIri(datasetIri)) {
            return null;
        }
        return PREFIXES + """
                SELECT ?nazev ?nazevLang ?popis ?popisLang ?pojem WHERE {
                  GRAPH ?g {
                    <%1$s> a dcat:Dataset .
                    OPTIONAL { <%1$s> dcterms:title ?nazev }
                    OPTIONAL { <%1$s> dcterms:description ?popis }
                    OPTIONAL { <%1$s> <%2$s> ?pojem }
                  }
                  BIND(LANG(?nazev) AS ?nazevLang)
                  BIND(LANG(?popis) AS ?popisLang)
                }
                """.formatted(datasetIri, TYKA_SE_POJMU);
    }

    /**
     * The distributions of one dataset: the link to offer, its format, and whether it is a
     * service rather than a file.
     *
     * <p>Kept separate from {@link #buildDatasetDetailQuery} on purpose. Distributions and
     * {@code týká-se-pojmu} are both multi-valued, so folding them into one flat SELECT
     * cross-products them.
     *
     * <p>Both URL variants are projected because neither alone is sufficient.
     */
    public static String buildDatasetDistributionsQuery(String datasetIri) {
        if (!SparqlIriValidator.isSafeHttpIri(datasetIri)) {
            return null;
        }
        return PREFIXES + """
                SELECT ?dist ?nazev ?nazevLang ?pristupoveUrl ?stahovaciUrl
                       ?format ?mediaTyp ?sluzba WHERE {
                  GRAPH ?g {
                    <%1$s> dcat:distribution ?dist .
                    OPTIONAL { ?dist dcterms:title ?nazev }
                    OPTIONAL { ?dist dcat:accessURL ?pristupoveUrl }
                    OPTIONAL { ?dist dcat:downloadURL ?stahovaciUrl }
                    OPTIONAL { ?dist dcterms:format ?format }
                    OPTIONAL { ?dist dcat:mediaType ?mediaTyp }
                    OPTIONAL { ?dist dcat:accessService ?sluzba }
                  }
                  BIND(LANG(?nazev) AS ?nazevLang)
                }
                """.formatted(datasetIri);
    }

    /**
     * Existence probe, so a dataset with no optional fields is still distinguishable from one
     * that is absent — the detail query's OPTIONALs would otherwise make both look identical.
     */
    public static String buildDatasetExistsQuery(String datasetIri) {
        if (!SparqlIriValidator.isSafeHttpIri(datasetIri)) {
            return null;
        }
        return PREFIXES + """
                ASK { GRAPH ?g { <%s> a dcat:Dataset } }
                """.formatted(datasetIri);
    }
}