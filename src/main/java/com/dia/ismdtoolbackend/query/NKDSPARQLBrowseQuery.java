package com.dia.ismdtoolbackend.query;

import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;

import java.util.List;

import static com.dia.constants.VocabularyConstants.CAS_NS;
import static com.dia.constants.VocabularyConstants.DATUM;
import static com.dia.constants.VocabularyConstants.DATUM_A_CAS;
import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE;
import static com.dia.constants.VocabularyConstants.OKAMZIK_POSLEDNI_ZMENY;
import static com.dia.constants.VocabularyConstants.OKAMZIK_VYTVORENI;

/**
 * SPARQL queries for browsing NKD ontologies (no text-search filter).
 * Distinct from {@link NKDSPARQLSearchQuery} which requires a {@code bif:contains}
 * term — these power the catalog "list all" view.
 */
public class NKDSPARQLBrowseQuery {

    private static final String PREFIXES = """
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX owl: <http://www.w3.org/2002/07/owl#>
            PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
            PREFIX dcterms: <http://purl.org/dc/terms/>
            """;

    /**
     * Returns ontology IRIs alphabetically by {@code skos:prefLabel} in the given
     * sort language, falling back to any-language label so unlabelled-in-{@code sortLang}
     * ontologies still appear (deterministically, after the labelled ones).
     */
    public static String buildListOntologyIrisQuery(String sortLang, int limit, int offset) {
        String safeLang = sanitizeLang(sortLang);
        return PREFIXES + """
                SELECT ?ontology WHERE {
                  ?ontology a owl:Ontology .
                  OPTIONAL {
                    ?ontology skos:prefLabel ?sortLabel .
                    FILTER(LANG(?sortLabel) = "%s")
                  }
                  OPTIONAL { ?ontology skos:prefLabel ?anyLabel }
                  BIND(COALESCE(?sortLabel, ?anyLabel, STR(?ontology)) AS ?orderKey)
                }
                ORDER BY ?orderKey ?ontology
                LIMIT %d OFFSET %d
                """.formatted(safeLang, limit, offset);
    }

    public static String buildCountOntologiesQuery() {
        return PREFIXES + """
                SELECT (COUNT(DISTINCT ?ontology) AS ?total) WHERE {
                  ?ontology a owl:Ontology .
                }
                """;
    }

    public static String buildCountConceptsQuery() {
        return PREFIXES + """
                SELECT (COUNT(DISTINCT ?concept) AS ?total) WHERE {
                  ?concept a skos:Concept .
                  ?concept skos:inScheme ?ontology .
                  ?ontology a owl:Ontology .
                }
                """;
    }

    /**
     * Returns concept-count per ontology for a fixed set of ontology IRIs.
     * One round-trip for the whole page via {@code VALUES} + {@code GROUP BY}.
     * Ontologies with zero concepts are omitted from the result (caller must
     * default missing entries to 0).
     */
    public static String buildConceptCountsForOntologiesQuery(List<String> ontologyIris) {
        StringBuilder values = new StringBuilder();
        for (String iri : ontologyIris) {
            // Defence-in-depth — caller already validates, but never embed unchecked
            // input directly into a SPARQL string.
            if (!SparqlIriValidator.isSafeHttpIri(iri)) {
                throw new IllegalArgumentException("Unsafe IRI for SPARQL VALUES: " + iri);
            }
            values.append("<").append(iri).append("> ");
        }
        return PREFIXES + """
                SELECT ?ontology (COUNT(DISTINCT ?concept) AS ?cnt) WHERE {
                  VALUES ?ontology { %s}
                  ?concept skos:inScheme ?ontology .
                }
                GROUP BY ?ontology
                """.formatted(values.toString());
    }

    /**
     * Batched list-item metadata for a fixed page of ontology IRIs — one round-trip
     * instead of a per-IRI full-graph CONSTRUCT. Returns the exact fields the catalog
     * list row needs (name, multilingual description, creation/modification dates),
     * matching the shapes the full OFN extractor produces:
     *
     * <ul>
     *   <li>{@code ?label} = {@code rdfs:label}, {@code ?prefLabel} = {@code skos:prefLabel}
     *       — caller takes label else prefLabel as the single name value (keyed "cs"), per
     *       {@code ModelAnalyzer.extractModelName} + {@code createMultilingualMap}.</li>
     *   <li>{@code ?descLang}/{@code ?desc} = one row per {@code dcterms:description} literal
     *       (lang tag + value) → caller assembles the multilingual map.</li>
     *   <li>Dates are a TWO-HOP read through the OFN "okamžik" instant resource: prefer
     *       {@code čas:datum-a-čas} (dateTime), fall back to {@code čas:datum} (date) —
     *       matching {@code ModelAnalyzer.extractTemporalValue}.</li>
     * </ul>
     *
     * <p>Ontologies absent from NKD simply produce no rows (caller skips them, matching the
     * old loop's "vanished between list and fetch" behaviour). Description is multi-valued,
     * so a single ontology may span several rows.
     */
    public static String buildListItemMetadataQuery(List<String> ontologyIris) {
        StringBuilder values = new StringBuilder();
        for (String iri : ontologyIris) {
            if (!SparqlIriValidator.isSafeHttpIri(iri)) {
                throw new IllegalArgumentException("Unsafe IRI for SPARQL VALUES: " + iri);
            }
            values.append("<").append(iri).append("> ");
        }

        String okamzikVytvoreni = OFN_NAMESPACE + OKAMZIK_VYTVORENI;
        String okamzikZmeny = OFN_NAMESPACE + OKAMZIK_POSLEDNI_ZMENY;
        String datumACas = CAS_NS + DATUM_A_CAS;
        String datum = CAS_NS + DATUM;
        return PREFIXES + """
                SELECT ?ontology ?label ?prefLabel ?descLang ?desc ?cDateTime ?cDate ?mDateTime ?mDate WHERE {
                  VALUES ?ontology { %s}
                  OPTIONAL { ?ontology rdfs:label ?label }
                  OPTIONAL { ?ontology skos:prefLabel ?prefLabel }
                  OPTIONAL {
                    ?ontology dcterms:description ?desc .
                    BIND(LANG(?desc) AS ?descLang)
                  }
                  OPTIONAL {
                    ?ontology <%s> ?cInst .
                    OPTIONAL { ?cInst <%s> ?cDateTime }
                    OPTIONAL { ?cInst <%s> ?cDate }
                  }
                  OPTIONAL {
                    ?ontology <%s> ?mInst .
                    OPTIONAL { ?mInst <%s> ?mDateTime }
                    OPTIONAL { ?mInst <%s> ?mDate }
                  }
                }
                """.formatted(values.toString(),
                okamzikVytvoreni, datumACas, datum,
                okamzikZmeny, datumACas, datum);
    }

    /**
     * Strips anything that isn't a BCP-47-ish lang tag character. Lang tags
     * appear inside a SPARQL string literal in {@code FILTER(LANG(?x) = "…")},
     * so a quote injection would close the literal — keep this strict.
     */
    private static String sanitizeLang(String lang) {
        return NKDSPARQLSearchQuery.sanitizeLang(lang);
    }

    private NKDSPARQLBrowseQuery() {
    }
}
