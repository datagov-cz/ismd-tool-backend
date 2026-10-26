package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.MatchedBy;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.enums.SearchType;
import com.dia.ismdtoolbackend.exception.ConflictingConceptTypeException;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Runs the Fuseki text search, classifies its rows by RDF type, and enriches rows with Fuseki labels. */
@Slf4j
@Component
@RequiredArgsConstructor
class IsmdFusekiSearch {

    private static final String SKOS_PREF_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel";
    private static final String SKOS_ALT_LABEL = "http://www.w3.org/2004/02/skos/core#altLabel";
    private static final String DCTERMS_DESCRIPTION = "http://purl.org/dc/terms/description";
    private static final String SKOS_DEFINITION = "http://www.w3.org/2004/02/skos/core#definition";
    private static final String SKOS_CONCEPT_SCHEME = "http://www.w3.org/2004/02/skos/core#ConceptScheme";
    private static final String OWL_ONTOLOGY = "http://www.w3.org/2002/07/owl#Ontology";

    private final JenaTDB2Repository jenaTDB2Repository;

    List<SearchResultDto> searchFuseki(String query, List<String> visibleGraphNames, int limit,
                                                ConceptType conceptTypeFilter) {
        if (visibleGraphNames.isEmpty()) {
            log.debug("Fuseki search skipped: no visible graph names for user");
            return List.of();
        }

        log.debug("Fuseki text search: query='{}', searching {} graphs, conceptType={}",
                query, visibleGraphNames.size(), conceptTypeFilter);

        List<Map<String, String>> rows = jenaTDB2Repository.searchByText(
                query, visibleGraphNames, limit, conceptTypeFilter);

        log.debug("Fuseki text search returned {} raw rows", rows.size());

        List<SearchResultDto> results = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String typesField = row.get("types");
            SearchType resolvedType = classifyByRdfTypes(typesField);
            // For ontology resources, the resource IRI IS the graph name — don't
            // populate ontologyIri with a self-reference (which would also overwrite
            // the PG ontology hit's null ontologyIri during merge). Concepts get the
            // graph they live in, as before.
            String ontologyIri = resolvedType == SearchType.ONTOLOGY
                    ? null
                    : row.get("graphName");
            // Enrich conceptType from rdf:types so Fuseki-only concept rows survive
            // the role-narrowing post-merge filter — PG side carries the column
            // verbatim, but Fuseki-only rows would otherwise have conceptType=null.
            ConceptType conceptType = resolvedType == SearchType.CONCEPT
                    ? conceptTypeOf(row.get("resourceIri"), typesField)
                    : null;
            results.add(SearchResultDto.builder()
                    .iri(row.get("resourceIri"))
                    .label(row.get("prefLabel"))
                    .labelLang(row.get("prefLabelLang"))
                    .altName(row.get("altLabel"))
                    .description(row.get("description"))
                    .definition(row.get("definition"))
                    .ontologyIri(ontologyIri)
                    .type(resolvedType)
                    .conceptType(conceptType)
                    .source(SearchSource.ISMD)
                    .matchedBy(MatchedBy.SPARQL)
                    .build());
        }
        return results;
    }

    /**
     * Classifies a Fuseki search hit by its rdf:type set (pipe-separated).
     * An ontology / concept scheme always carries skos:ConceptScheme or owl:Ontology;
     * a concept carries skos:Concept. The ontology node in our data is both
     * ConceptScheme and Ontology but never skos:Concept, so an ontology marker is
     * decisive. Everything else — including skos:Concept, ambiguous, or missing
     * types — falls back to CONCEPT, matching how the text index was historically
     * consumed.
     */
    static SearchType classifyByRdfTypes(String types) {
        if (types == null || types.isEmpty()) {
            return SearchType.CONCEPT;
        }
        for (String t : types.split("\\|")) {
            if (SKOS_CONCEPT_SCHEME.equals(t) || OWL_ONTOLOGY.equals(t)) {
                return SearchType.ONTOLOGY;
            }
        }
        return SearchType.CONCEPT;
    }

    /**
     * Extracts the concept role from a pipe-separated rdf:type set produced by
     * Fuseki text search. Returns null when no specific role marker resolves, so
     * the PG-authoritative type can still backfill (KONCEPT is a terminal
     * fallback for the projection endpoint, not a search signal).
     */
    private static ConceptType conceptTypeOf(String iri, String types) {
        try {
            return conceptTypeByRdfTypes(types);
        } catch (ConflictingConceptTypeException e) {
            throw new ConflictingConceptTypeException(iri, e);
        }
    }

    static ConceptType conceptTypeByRdfTypes(String types) {
        if (types == null || types.isEmpty()) return null;
        ConceptType resolved = ConceptType.fromRdfTypes(java.util.Arrays.asList(types.split("\\|")));
        return resolved == ConceptType.KONCEPT ? null : resolved;
    }

    void enrichWithLabels(List<SearchResultDto> results, String lang) {
        List<String> conceptIris = results.stream()
                .map(SearchResultDto::getIri)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (conceptIris.isEmpty()) return;

        Model labelsModel = jenaTDB2Repository.fetchConceptLabels(conceptIris);

        for (SearchResultDto dto : results) {
            if (dto.getIri() == null) continue;

            Resource concept = labelsModel.getResource(dto.getIri());

            // Resolve prefLabel with language preference
            String bestLabel = getLiteralWithLangPreference(concept, SKOS_PREF_LABEL, lang);
            if (bestLabel != null) {
                dto.setLabel(bestLabel);
                dto.setLabelLang(lang);
            }

            // altLabel
            String altLabel = getFirstLiteral(concept);
            if (altLabel != null) dto.setAltName(altLabel);

            // description
            String description = getLiteralWithLangPreference(concept, DCTERMS_DESCRIPTION, lang);
            if (description != null) dto.setDescription(description);

            // definition
            String definition = getLiteralWithLangPreference(concept, SKOS_DEFINITION, lang);
            if (definition != null) dto.setDefinition(definition);
        }
    }

    String getLiteralWithLangPreference(Resource resource, String propertyUri, String lang) {
        Property property = resource.getModel().getProperty(propertyUri);
        StmtIterator stmts = resource.listProperties(property);

        String bestMatch = null;
        String fallback = null;

        while (stmts.hasNext()) {
            Statement stmt = stmts.next();
            if (stmt.getObject().isLiteral()) {
                Literal literal = stmt.getLiteral();
                String literalLang = literal.getLanguage();
                if (lang != null && lang.equals(literalLang)) {
                    bestMatch = literal.getString();
                } else if (fallback == null) {
                    fallback = literal.getString();
                }
            }
        }

        return bestMatch != null ? bestMatch : fallback;
    }

    String getFirstLiteral(Resource resource) {
        Property property = resource.getModel().getProperty(SKOS_ALT_LABEL);
        Statement stmt = resource.getProperty(property);
        if (stmt != null && stmt.getObject().isLiteral()) {
            return stmt.getLiteral().getString();
        }
        return null;
    }
}
