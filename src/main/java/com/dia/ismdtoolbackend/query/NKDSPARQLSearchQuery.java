package com.dia.ismdtoolbackend.query;

import com.dia.ismdtoolbackend.enums.RelationType;
import org.apache.jena.query.ParameterizedSparqlString;

import java.util.List;
import java.util.stream.Collectors;

public class NKDSPARQLSearchQuery {

    private static final String PREFIXES = """
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX owl: <http://www.w3.org/2002/07/owl#>
            PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
            PREFIX dcterms: <http://purl.org/dc/terms/>
            PREFIX bif: <bif:>
            """;

    /**
     * Builds a SPARQL SELECT query to search ontologies in NKD by text.
     * Searches across skos:prefLabel, dcterms:title, dcterms:description using bif:contains.
     */
    public static String buildOntologySearchQuery(String searchTerm, String lang, int limit, int offset) {
        String sanitizedTerm = sanitizeSearchTerm(searchTerm);

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText(PREFIXES + """
                SELECT DISTINCT ?resource ?label ?labelLang ?title ?description ?ontologyIri WHERE {
                  ?resource a owl:Ontology .

                  {
                    ?resource skos:prefLabel ?matchField .
                    ?matchField bif:contains '"%s*"' .
                  } UNION {
                    ?resource dcterms:title ?matchField .
                    ?matchField bif:contains '"%s*"' .
                  } UNION {
                    ?resource dcterms:description ?matchField .
                    ?matchField bif:contains '"%s*"' .
                  }

                  OPTIONAL {
                    ?resource skos:prefLabel ?prefLabel .
                    FILTER(LANG(?prefLabel) = "%s")
                  }
                  OPTIONAL {
                    ?resource skos:prefLabel ?anyLabel .
                  }
                  BIND(COALESCE(?prefLabel, ?anyLabel) AS ?label)
                  BIND(LANG(COALESCE(?prefLabel, ?anyLabel)) AS ?labelLang)

                  OPTIONAL { ?resource dcterms:title ?title }
                  OPTIONAL { ?resource dcterms:description ?description }

                  BIND(?resource AS ?ontologyIri)
                }
                LIMIT %d OFFSET %d
                """.formatted(sanitizedTerm, sanitizedTerm, sanitizedTerm, lang, limit, offset));

        return pss.toString();
    }

    /**
     * Builds a SPARQL SELECT query to search concepts in NKD by text.
     * Searches across skos:prefLabel, skos:altLabel, dcterms:description, skos:definition using bif:contains.
     * Optionally filters by ontology IRIs and relation types.
     */
    public static String buildConceptSearchQuery(String searchTerm, String lang, int limit, int offset,
                                                  List<String> ontologyIris, List<RelationType> relationTypes) {
        String sanitizedTerm = sanitizeSearchTerm(searchTerm);
        StringBuilder query = new StringBuilder(PREFIXES);

        query.append("""
                SELECT DISTINCT ?resource ?label ?labelLang ?altName ?description ?definition ?ontology WHERE {
                  ?resource a skos:Concept .
                  ?resource skos:inScheme ?ontology .

                """);

        // Ontology IRI filter using VALUES clause
        if (ontologyIris != null && !ontologyIris.isEmpty()) {
            query.append("  VALUES ?ontology { ");
            for (String iri : ontologyIris) {
                query.append("<").append(escapeIri(iri)).append("> ");
            }
            query.append("}\n\n");
        }

        // Text matching via bif:contains
        query.append("""
                  {
                    ?resource skos:prefLabel ?matchField .
                    ?matchField bif:contains '"%s*"' .
                  } UNION {
                    ?resource skos:altLabel ?matchField .
                    ?matchField bif:contains '"%s*"' .
                  } UNION {
                    ?resource dcterms:description ?matchField .
                    ?matchField bif:contains '"%s*"' .
                  } UNION {
                    ?resource skos:definition ?matchField .
                    ?matchField bif:contains '"%s*"' .
                  }

                """.formatted(sanitizedTerm, sanitizedTerm, sanitizedTerm, sanitizedTerm));

        // Relation type filters
        if (relationTypes != null && !relationTypes.isEmpty()) {
            if (relationTypes.size() == 1) {
                query.append("  ").append(buildRelationFilter(relationTypes.get(0))).append("\n\n");
            } else {
                String combined = relationTypes.stream()
                        .map(rt -> "EXISTS " + getRelationPattern(rt))
                        .collect(Collectors.joining("\n         || "));
                query.append("  FILTER(\n         ").append(combined).append("\n       )\n\n");
            }
        }

        // Label selection with language preference and fallback
        query.append("""
                  OPTIONAL {
                    ?resource skos:prefLabel ?prefLabel .
                    FILTER(LANG(?prefLabel) = "%s")
                  }
                  OPTIONAL {
                    ?resource skos:prefLabel ?anyLabel .
                  }
                  BIND(COALESCE(?prefLabel, ?anyLabel) AS ?label)
                  BIND(LANG(COALESCE(?prefLabel, ?anyLabel)) AS ?labelLang)

                  OPTIONAL { ?resource skos:altLabel ?altName }
                  OPTIONAL { ?resource dcterms:description ?description }
                  OPTIONAL { ?resource skos:definition ?definition }
                }
                LIMIT %d OFFSET %d
                """.formatted(lang, limit, offset));

        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText(query.toString());
        return pss.toString();
    }

    private static String buildRelationFilter(RelationType type) {
        return "FILTER EXISTS " + getRelationPattern(type);
    }

    private static String getRelationPattern(RelationType type) {
        return switch (type) {
            case SUBCLASS -> "{ ?resource rdfs:subClassOf ?x }";
            case SUPERCLASS -> "{ ?x rdfs:subClassOf ?resource }";
            case EXACT_MATCH -> "{ ?resource skos:exactMatch ?x }";
            case PROPERTY_OF -> "{ ?resource rdfs:domain ?x }";
            case RELATIONSHIP_OF -> "{ ?resource rdfs:range ?x }";
        };
    }

    /**
     * Sanitizes the search term for use in bif:contains.
     * Removes characters that could break the SPARQL query or enable injection.
     */
    static String sanitizeSearchTerm(String term) {
        if (term == null) {
            return "";
        }
        // Remove quotes, backslashes, and other SPARQL-dangerous characters
        return term.replaceAll("[\"'\\\\<>{}|^`]", "").trim();
    }

    /**
     * Escapes an IRI for safe inclusion in a SPARQL query.
     */
    private static String escapeIri(String iri) {
        if (iri == null) {
            return "";
        }
        return iri.replaceAll("[<>\"{}|^`\\\\]", "");
    }

    private NKDSPARQLSearchQuery() {
    }
}
