package com.dia.ismdtoolbackend.query;

import com.dia.ismdtoolbackend.enums.RelationType;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
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
        String bifContains = "'\"" + sanitizedTerm + "*\"'";
        String safeLang = sanitizeLang(lang);

        StringBuilder query = new StringBuilder(PREFIXES);
        query.append("""
                SELECT DISTINCT ?resource ?label ?labelLang ?title ?description ?ontologyIri ?modified WHERE {
                  ?resource a owl:Ontology .

                  {
                    ?resource skos:prefLabel ?matchField .
                    ?matchField bif:contains %s .
                  } UNION {
                    ?resource dcterms:title ?matchField .
                    ?matchField bif:contains %s .
                  } UNION {
                    ?resource dcterms:description ?matchField .
                    ?matchField bif:contains %s .
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
                  OPTIONAL { ?resource dcterms:modified ?modified }

                  BIND(?resource AS ?ontologyIri)
                }
                LIMIT %d OFFSET %d
                """.formatted(bifContains, bifContains, bifContains, safeLang, limit, offset));

        return query.toString();
    }

    /**
     * Returns the count-only variant of {@link #buildOntologySearchQuery} —
     * same WHERE clause, no pagination, single-row {@code ?total}.
     */
    public static String buildOntologySearchCountQuery(String searchTerm) {
        String sanitizedTerm = sanitizeSearchTerm(searchTerm);
        String bifContains = "'\"" + sanitizedTerm + "*\"'";

        return PREFIXES + """
                SELECT (COUNT(DISTINCT ?resource) AS ?total) WHERE {
                  ?resource a owl:Ontology .
                  {
                    ?resource skos:prefLabel ?matchField .
                    ?matchField bif:contains %s .
                  } UNION {
                    ?resource dcterms:title ?matchField .
                    ?matchField bif:contains %s .
                  } UNION {
                    ?resource dcterms:description ?matchField .
                    ?matchField bif:contains %s .
                  }
                }
                """.formatted(bifContains, bifContains, bifContains);
    }

    /**
     * Builds a SPARQL SELECT query to search concepts in NKD by text.
     * Searches across skos:prefLabel, skos:altLabel, dcterms:description, skos:definition using bif:contains.
     * Optionally filters by ontology IRIs and relation types.
     */
    public static String buildConceptSearchQuery(String searchTerm, String lang, int limit, int offset,
                                                  List<String> ontologyIris, List<RelationType> relationTypes) {
        String sanitizedTerm = sanitizeSearchTerm(searchTerm);
        String safeLang = sanitizeLang(lang);
        StringBuilder query = new StringBuilder(PREFIXES);

        query.append("""
                SELECT DISTINCT ?resource ?label ?labelLang ?altName ?description ?definition ?ontology ?modified WHERE {
                  ?resource a skos:Concept .
                  ?resource skos:inScheme ?ontology .

                """);

        // Ontology IRI filter using VALUES clause
        if (ontologyIris != null && !ontologyIris.isEmpty()) {
            List<String> validIris = ontologyIris.stream()
                    .filter(SparqlIriValidator::isSafeHttpIri)
                    .toList();
            if (!validIris.isEmpty()) {
                query.append("  VALUES ?ontology { ");
                for (String iri : validIris) {
                    query.append("<").append(iri).append("> ");
                }
                query.append("}\n\n");
            }
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
                  OPTIONAL { ?resource dcterms:modified ?modified }
                }
                LIMIT %d OFFSET %d
                """.formatted(safeLang, limit, offset));

        return query.toString();
    }

    /**
     * Returns the count-only variant of {@link #buildConceptSearchQuery}.
     * Honours the same ontology / relation-type filters so the count matches
     * the page query exactly.
     */
    public static String buildConceptSearchCountQuery(String searchTerm,
                                                       List<String> ontologyIris,
                                                       List<RelationType> relationTypes) {
        String sanitizedTerm = sanitizeSearchTerm(searchTerm);
        StringBuilder query = new StringBuilder(PREFIXES);

        query.append("""
                SELECT (COUNT(DISTINCT ?resource) AS ?total) WHERE {
                  ?resource a skos:Concept .
                  ?resource skos:inScheme ?ontology .

                """);

        if (ontologyIris != null && !ontologyIris.isEmpty()) {
            List<String> validIris = ontologyIris.stream()
                    .filter(SparqlIriValidator::isSafeHttpIri)
                    .toList();
            if (!validIris.isEmpty()) {
                query.append("  VALUES ?ontology { ");
                for (String iri : validIris) {
                    query.append("<").append(iri).append("> ");
                }
                query.append("}\n\n");
            }
        }

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

        if (relationTypes != null && !relationTypes.isEmpty()) {
            if (relationTypes.size() == 1) {
                query.append("  ").append(buildRelationFilter(relationTypes.get(0))).append("\n");
            } else {
                String combined = relationTypes.stream()
                        .map(rt -> "EXISTS " + getRelationPattern(rt))
                        .collect(Collectors.joining("\n         || "));
                query.append("  FILTER(\n         ").append(combined).append("\n       )\n");
            }
        }

        query.append("}\n");
        return query.toString();
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
     * Strips anything that isn't a BCP-47-ish lang tag character. The lang tag
     * lands inside a SPARQL string literal in {@code FILTER(LANG(?x) = "…")},
     * so a quote injection would close the literal — keep this strict. Mirrors
     * the same hardening already applied in {@link NKDSPARQLBrowseQuery}.
     */
    static String sanitizeLang(String lang) {
        if (lang == null) return "cs";
        String stripped = lang.replaceAll("[^A-Za-z0-9-]", "");
        return stripped.isEmpty() ? "cs" : stripped;
    }

    private NKDSPARQLSearchQuery() {
    }
}
