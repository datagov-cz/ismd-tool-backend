package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.enums.RelationType;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.enums.SearchType;
import com.dia.ismdtoolbackend.query.NKDSPARQLBrowseQuery;
import com.dia.ismdtoolbackend.query.NKDSPARQLSearchQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NkdSearchProvider implements SearchProvider {

    private final NkdSparqlClient nkdSparqlClient;

    @Override
    public SearchProviderResult search(String query, SearchType type, int limit, int offset,
                                       String lang, List<String> ontologyIris,
                                       List<RelationType> relationTypes, String userId,
                                       boolean isAdmin, Boolean publishedFilter) {
        // isAdmin / publishedFilter are ISMD-local concerns (they filter against a
        // Postgres is_published column that doesn't exist for NKD). Ignored here.
        if (!nkdSparqlClient.isEndpointConfigured()) {
            log.warn("NKD endpoint not configured, returning empty results");
            return new SearchProviderResult(List.of(), 0, 0, 0);
        }

        List<SearchResultDto> ontologyResults = (type == null || type == SearchType.ONTOLOGY)
                ? searchOntologies(query, lang, limit, offset)
                : List.of();

        List<SearchResultDto> conceptResults = (type == null || type == SearchType.CONCEPT)
                ? searchConcepts(query, lang, limit, offset, ontologyIris, relationTypes)
                : List.of();

        // Concept counts for the page's ontology results — single batched query.
        if (!ontologyResults.isEmpty()) {
            populateOntologyConceptCounts(ontologyResults);
        }

        List<SearchResultDto> allResults = new ArrayList<>(ontologyResults.size() + conceptResults.size());
        allResults.addAll(ontologyResults);
        allResults.addAll(conceptResults);

        // Dedup by IRI
        LinkedHashMap<String, SearchResultDto> deduped = new LinkedHashMap<>();
        for (SearchResultDto result : allResults) {
            if (result.getIri() != null) {
                deduped.merge(result.getIri(), result, (existing, incoming) -> {
                    mergeNonNullFields(existing, incoming);
                    return existing;
                });
            }
        }

        List<SearchResultDto> results = new ArrayList<>(deduped.values());

        Integer totalOntologies = SearchProvider.countIfMatches(
                type == null || type == SearchType.ONTOLOGY,
                () -> fetchOntologyTotal(query));
        Integer totalConcepts = SearchProvider.countIfMatches(
                type == null || type == SearchType.CONCEPT,
                () -> fetchConceptTotal(query, ontologyIris, relationTypes));

        int total = (totalOntologies != null ? totalOntologies : 0)
                + (totalConcepts != null ? totalConcepts : 0);
        return new SearchProviderResult(results, total, totalOntologies, totalConcepts);
    }

    private List<SearchResultDto> searchOntologies(String query, String lang, int limit, int offset) {
        String sparql = NKDSPARQLSearchQuery.buildOntologySearchQuery(query, lang, limit, offset);
        List<Map<String, String>> rows = nkdSparqlClient.executeSelect(sparql);

        List<SearchResultDto> results = new ArrayList<>();
        for (Map<String, String> row : rows) {
            results.add(SearchResultDto.builder()
                    .iri(row.get("resource"))
                    .label(row.get("label"))
                    .labelLang(row.get("labelLang"))
                    .description(row.get("description"))
                    .ontologyIri(row.get("ontologyIri"))
                    .lastModified(row.get("modified"))
                    .type(SearchType.ONTOLOGY)
                    .source(SearchSource.NKD)
                    .build());
        }
        return results;
    }

    private List<SearchResultDto> searchConcepts(String query, String lang, int limit, int offset,
                                                  List<String> ontologyIris,
                                                  List<RelationType> relationTypes) {
        String sparql = NKDSPARQLSearchQuery.buildConceptSearchQuery(
                query, lang, limit, offset, ontologyIris, relationTypes);
        List<Map<String, String>> rows = nkdSparqlClient.executeSelect(sparql);

        List<SearchResultDto> results = new ArrayList<>();
        for (Map<String, String> row : rows) {
            results.add(SearchResultDto.builder()
                    .iri(row.get("resource"))
                    .label(row.get("label"))
                    .labelLang(row.get("labelLang"))
                    .altName(row.get("altName"))
                    .description(row.get("description"))
                    .definition(row.get("definition"))
                    .ontologyIri(row.get("ontology"))
                    .lastModified(row.get("modified"))
                    .type(SearchType.CONCEPT)
                    .source(SearchSource.NKD)
                    .build());
        }
        return results;
    }

    /**
     * Mutates each ontology result with its concept count from a single
     * batched SPARQL query. Tolerated failures:
     * — Whole batch errors: every conceptCount stays null.
     * — Single ontology absent from result rows: defaulted to 0 (means the
     *   ontology genuinely has zero concepts).
     * — Single row with unparseable {@code cnt} literal: that ontology's
     *   count stays null (we can't tell if it's 0 or some other number).
     */
    private void populateOntologyConceptCounts(List<SearchResultDto> ontologyResults) {
        List<String> iris = ontologyResults.stream()
                .map(SearchResultDto::getIri)
                .filter(iri -> iri != null && !iri.isBlank())
                .distinct()
                .toList();
        if (iris.isEmpty()) return;

        boolean batchFailed = false;
        Map<String, Integer> counts = Map.of();
        try {
            String sparql = NKDSPARQLBrowseQuery.buildConceptCountsForOntologiesQuery(iris);
            List<Map<String, String>> rows = nkdSparqlClient.executeSelect(sparql);
            counts = new HashMap<>(rows.size() * 2);
            for (Map<String, String> row : rows) {
                String iri = row.get("ontology");
                String cnt = row.get("cnt");
                if (iri != null && cnt != null) {
                    try {
                        counts.put(iri, Integer.parseInt(cnt));
                    } catch (NumberFormatException ignored) {
                        // Tolerate odd literals — leave that ontology's count null.
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("NKD concept-count batch failed for search page, leaving counts null: {}", e.getMessage());
            batchFailed = true;
        }

        if (batchFailed) return;
        for (SearchResultDto dto : ontologyResults) {
            // Default to 0 when present in the page IRI list but absent from
            // the count result (= ontology has no concepts).
            dto.setConceptCount(counts.getOrDefault(dto.getIri(), 0));
        }
    }

    private Integer fetchOntologyTotal(String query) {
        try {
            return fetchSingleCount(NKDSPARQLSearchQuery.buildOntologySearchCountQuery(query));
        } catch (RuntimeException e) {
            log.warn("NKD ontology total-count failed: {}", e.getMessage());
            return null;
        }
    }

    private Integer fetchConceptTotal(String query, List<String> ontologyIris,
                                       List<RelationType> relationTypes) {
        try {
            return fetchSingleCount(
                    NKDSPARQLSearchQuery.buildConceptSearchCountQuery(query, ontologyIris, relationTypes));
        } catch (RuntimeException e) {
            log.warn("NKD concept total-count failed: {}", e.getMessage());
            return null;
        }
    }

    private int fetchSingleCount(String sparql) {
        List<Map<String, String>> rows = nkdSparqlClient.executeSelect(sparql);
        if (rows.isEmpty()) return 0;
        String value = rows.get(0).get("total");
        if (value == null) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("Unparseable COUNT result from NKD: {}", value);
            return 0;
        }
    }

    private void mergeNonNullFields(SearchResultDto target, SearchResultDto source) {
        if (source.getLabel() != null) target.setLabel(source.getLabel());
        if (source.getLabelLang() != null) target.setLabelLang(source.getLabelLang());
        if (source.getAltName() != null) target.setAltName(source.getAltName());
        if (source.getDescription() != null) target.setDescription(source.getDescription());
        if (source.getDefinition() != null) target.setDefinition(source.getDefinition());
        if (source.getOntologyIri() != null) target.setOntologyIri(source.getOntologyIri());
        if (source.getConceptType() != null) target.setConceptType(source.getConceptType());
        if (source.getSlug() != null) target.setSlug(source.getSlug());
        if (source.getLastModified() != null) target.setLastModified(source.getLastModified());
        if (source.getConceptCount() != null) target.setConceptCount(source.getConceptCount());
    }
}
