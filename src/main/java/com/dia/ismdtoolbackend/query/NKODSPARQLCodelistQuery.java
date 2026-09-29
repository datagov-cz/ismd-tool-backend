package com.dia.ismdtoolbackend.query;

import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * SPARQL queries for the NKOD codelist picker.
 */
public final class NKODSPARQLCodelistQuery {

    private NKODSPARQLCodelistQuery() {
    }

    /** Prefix of the OFN codelist specification a codelist dataset conforms to. */
    public static final String OFN_CISELNIKY = "https://ofn.gov.cz/číselníky";

    /** Path segment of the RPP identifier that carries the codelist number. */
    public static final String RPP_CODELIST_ID_SEGMENT = "mdzastresujicids/";

    /**
     * Lists every codelist dataset: series (current version's distribution) and standalone
     * datasets. One row per dataset per {@code .jsonld} download URL, sorted by Czech title.
     */
    public static String buildListQuery() {
        return """
                PREFIX dcat: <http://www.w3.org/ns/dcat#>
                PREFIX dcterms: <http://purl.org/dc/terms/>
                SELECT ?dataset ?title ?downloadUrl
                       (SAMPLE(?pub) AS ?publisher) (SAMPLE(?desc) AS ?description)
                       (SAMPLE(?rppId) AS ?rppIdentifier) (MAX(?start) AS ?validFrom)
                WHERE {
                  {
                    ?dataset a dcat:DatasetSeries ; dcterms:title ?title .
                    FILTER(LANG(?title) = "cs")
                    FILTER EXISTS { ?dataset dcterms:conformsTo ?c .
                                    FILTER(STRSTARTS(STR(?c), "%1$s")) }
                    OPTIONAL { ?ds dcat:inSeries ?dataset ; dcat:distribution ?d .
                               ?d dcat:downloadURL ?downloadUrl .
                               FILTER NOT EXISTS { ?ds dcterms:temporal ?t . ?t dcat:endDate ?ed . }
                               FILTER(STRENDS(STR(?downloadUrl), ".jsonld"))
                               OPTIONAL { ?ds dcterms:temporal/dcat:startDate ?start } }
                  } UNION {
                    ?dataset a dcat:Dataset ; dcterms:title ?title .
                    FILTER(LANG(?title) = "cs")
                    FILTER EXISTS { ?dataset dcterms:conformsTo ?c .
                                    FILTER(STRSTARTS(STR(?c), "%1$s")) }
                    FILTER NOT EXISTS { ?dataset a dcat:DatasetSeries }
                    FILTER NOT EXISTS { ?dataset dcat:inSeries ?anySeries }
                    OPTIONAL { ?dataset dcat:distribution ?d .
                               ?d dcat:downloadURL ?downloadUrl .
                               FILTER(STRENDS(STR(?downloadUrl), ".jsonld")) }
                  }
                  OPTIONAL { ?dataset dcterms:publisher ?pub }
                  OPTIONAL { ?dataset dcterms:description ?desc . FILTER(LANG(?desc) = "cs") }
                  OPTIONAL { ?dataset dcterms:identifier ?rppId .
                             FILTER(CONTAINS(STR(?rppId), "%2$s")) }
                }
                GROUP BY ?dataset ?title ?downloadUrl
                ORDER BY ?title
                """.formatted(OFN_CISELNIKY, RPP_CODELIST_ID_SEGMENT);
    }

    /**
     * Czech names of the given publishers, one row per publisher. IRIs that are not safe to
     * interpolate are skipped; returns null when none remain.
     */
    public static String buildPublisherNamesQuery(Collection<String> publisherIris) {
        if (publisherIris == null) {
            return null;
        }
        String values = publisherIris.stream()
                .filter(SparqlIriValidator::isSafeHttpIri)
                .distinct()
                .sorted()
                .map(iri -> "<" + iri + ">")
                .collect(Collectors.joining(" "));
        if (values.isEmpty()) {
            return null;
        }
        return """
                PREFIX foaf: <http://xmlns.com/foaf/0.1/>
                SELECT ?publisher (SAMPLE(?n) AS ?name) WHERE {
                  VALUES ?publisher { %s }
                  ?publisher foaf:name ?n .
                  FILTER(LANG(?n) = "cs")
                }
                GROUP BY ?publisher
                """.formatted(values);
    }
}
