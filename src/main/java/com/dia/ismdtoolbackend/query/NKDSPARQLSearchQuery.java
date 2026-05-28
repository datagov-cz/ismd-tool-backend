package com.dia.ismdtoolbackend.query;

import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.RelationType;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import java.util.List;
import java.util.stream.Collectors;

import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE;
import static com.dia.constants.VocabularyConstants.TRIDA;
import static com.dia.constants.VocabularyConstants.VLASTNOST;
import static com.dia.constants.VocabularyConstants.VZTAH;

public class NKDSPARQLSearchQuery {

    private static final String PREFIXES = """
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX owl: <http://www.w3.org/2002/07/owl#>
            PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
            PREFIX dcterms: <http://purl.org/dc/terms/>
            PREFIX bif: <bif:>
            """;

    private static final String OWL_CLASS = "http://www.w3.org/2002/07/owl#Class";
    private static final String OWL_OBJECT_PROPERTY = "http://www.w3.org/2002/07/owl#ObjectProperty";
    private static final String OWL_DATATYPE_PROPERTY = "http://www.w3.org/2002/07/owl#DatatypeProperty";

    /**
     * Builds a SPARQL SELECT query to search ontologies in NKD by text.
     * Searches across skos:prefLabel, dcterms:title, dcterms:description using bif:contains.
     */
    public static String buildOntologySearchQuery(String searchTerm, String lang, int limit, int offset) {
        String sanitizedTerm = sanitizeSearchTerm(searchTerm);
        String bifContains = "'\"" + sanitizedTerm + "*\"'";
        String safeLang = sanitizeLang(lang);

        return PREFIXES + """
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
                """.formatted(bifContains, bifContains, bifContains, safeLang, limit, offset);
    }

    /**
     * Returns the count-only variant of {@link #buildOntologySearchQuery}.
     *
     * <p>The trailing {@code OPTIONAL { ?resource dcterms:description ?desc }}
     * is a Virtuoso planner workaround — without it, a lean WHERE with only
     * {@code ?resource a owl:Ontology} + a 3-way {@code bif:contains} UNION
     * silently returns zero rows for some search terms (e.g. "auto" matches
     * inside word like "automotive"/"automaticky" in descriptions, but the
     * planner picks a non-FTS path and yields 0). Adding any extra non-trivial
     * triple nudges Virtuoso onto a working plan that
     * matches the page query. The OPTIONAL is semantically a no-op for
     * {@code COUNT(DISTINCT ?resource)} so it's safe.
     *
     * <p>See also the comment in {@link #buildConceptSearchQuery} on the
     * related FILTER-EXISTS/UNION planner bug.
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
                  OPTIONAL { ?resource dcterms:description ?desc }
                }
                """.formatted(bifContains, bifContains, bifContains);
    }

    /**
     * Builds a SPARQL SELECT query to search concepts in NKD by text.
     * Searches across skos:prefLabel, skos:altLabel, dcterms:description, skos:definition using bif:contains.
     * Optionally filters by ontology IRIs and relation types.
     */
    public static String buildConceptSearchQuery(String searchTerm, String lang, int limit, int offset,
                                                  List<String> ontologyIris, List<RelationType> relationTypes,
                                                  ConceptType conceptTypeFilter) {
        String safeLang = sanitizeLang(lang);
        StringBuilder query = new StringBuilder(PREFIXES);

        // Structure: an inner subquery (?resource, ?ontology) does the heavy lifting
        // (skos:Concept type, ontology binding, role filter, ontology IRI filter,
        // text matching via bif:contains, relation-type filter) and applies LIMIT/OFFSET.
        // The outer query then attaches OPTIONAL labels/descriptions for the surviving
        // rows. This isolation is required for Virtuoso: placing the OPTIONAL/BIND
        // projection in the same group as a 4-way bif:contains UNION combined with a
        // FILTER EXISTS clause triggers a planner bug that silently returns zero rows.
        query.append("""
                SELECT DISTINCT ?resource ?label ?labelLang ?altName ?description ?definition ?ontology ?modified WHERE {
                """);

        appendResourceSubquery(query, searchTerm, ontologyIris, relationTypes, conceptTypeFilter, limit, offset);

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
                """.formatted(safeLang));

        return query.toString();
    }

    /**
     * Emits the inner {@code { SELECT DISTINCT ?resource ?ontology WHERE { ... } LIMIT/OFFSET }}
     * block that selects matching concept IRIs. Kept separate so the search query
     * can layer label/description OPTIONALs on top without interfering with the
     * planner's choice on the resource side.
     */
    private static void appendResourceSubquery(StringBuilder query, String searchTerm,
                                                List<String> ontologyIris,
                                                List<RelationType> relationTypes,
                                                ConceptType conceptTypeFilter,
                                                int limit, int offset) {
        String sanitizedTerm = sanitizeSearchTerm(searchTerm);
        query.append("  { SELECT DISTINCT ?resource ?ontology WHERE {\n")
             .append("      ?resource a skos:Concept .\n")
             .append("      ?resource skos:inScheme ?ontology .\n");

        if (conceptTypeFilter != null) {
            query.append("      ").append(buildRoleFilter(conceptTypeFilter)).append("\n");
        }

        if (ontologyIris != null && !ontologyIris.isEmpty()) {
            List<String> validIris = ontologyIris.stream()
                    .filter(SparqlIriValidator::isSafeHttpIri)
                    .toList();
            if (!validIris.isEmpty()) {
                query.append("      VALUES ?ontology { ");
                for (String iri : validIris) {
                    query.append("<").append(iri).append("> ");
                }
                query.append("}\n");
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
                query.append("      ").append(buildRelationFilter(relationTypes.get(0))).append("\n");
            } else {
                String combined = relationTypes.stream()
                        .map(rt -> "EXISTS " + getRelationPattern(rt))
                        .collect(Collectors.joining("\n             || "));
                query.append("      FILTER(\n             ").append(combined).append("\n           )\n");
            }
        }

        query.append("    } LIMIT ").append(limit).append(" OFFSET ").append(offset).append(" }\n");
    }

    /**
     * Returns the count-only variant of {@link #buildConceptSearchQuery}.
     * Honours the same ontology / relation-type filters so the count matches
     * the page query exactly.
     */
    public static String buildConceptSearchCountQuery(String searchTerm,
                                                       List<String> ontologyIris,
                                                       List<RelationType> relationTypes,
                                                       ConceptType conceptTypeFilter) {
        String sanitizedTerm = sanitizeSearchTerm(searchTerm);
        StringBuilder query = new StringBuilder(PREFIXES);

        query.append("""
                SELECT (COUNT(DISTINCT ?resource) AS ?total) WHERE {
                  ?resource a skos:Concept .
                  ?resource skos:inScheme ?ontology .

                """);

        if (conceptTypeFilter != null) {
            query.append("  ").append(buildRoleFilter(conceptTypeFilter)).append("\n\n");
        }

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

    private static String ofnRoleFragment(ConceptType type) {
        return switch (type) {
            case TRIDA -> TRIDA;
            case VLASTNOST -> VLASTNOST;
            case VZTAH -> VZTAH;
        };
    }

    /**
     * Returns the OWL type that semantically matches an OFN concept role.
     * {@link OWL2} classes are the upstream truth in OWL ontologies; the OFN role
     * tags (slovníky:třída/vlastnost/vztah) are added by ISMD on import. NKD
     * carries the OWL types verbatim but does NOT add the OFN tags — so the role
     * filter must accept either.
     */
    private static String owlTypeIri(ConceptType type) {
        return switch (type) {
            case TRIDA -> OWL_CLASS;
            case VLASTNOST -> OWL_DATATYPE_PROPERTY;
            case VZTAH -> OWL_OBJECT_PROPERTY;
        };
    }

    /**
     * SPARQL clause that matches concepts whose rdf:type set contains either the
     * OFN role IRI (ISMD-published data) or the matching OWL type (NKD data).
     */
    private static String buildRoleFilter(ConceptType type) {
        String ofnIri = OFN_NAMESPACE + ofnRoleFragment(type);
        String owlIri = owlTypeIri(type);
        return "FILTER(EXISTS { ?resource a <" + ofnIri + "> }"
                + " || EXISTS { ?resource a <" + owlIri + "> })";
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
