package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.RelationType;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.enums.SearchType;
import com.dia.ismdtoolbackend.query.NKDSPARQLSearchQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
                                       List<RelationType> relationTypes, String userId) {
        if (!nkdSparqlClient.isEndpointConfigured()) {
            log.warn("NKD endpoint not configured, returning empty results");
            return new SearchProviderResult(List.of(), 0);
        }

        List<SearchResultDto> allResults = new ArrayList<>();

        if (type == null || type == SearchType.ONTOLOGY) {
            allResults.addAll(searchOntologies(query, lang, limit, offset));
        }

        if (type == null || type == SearchType.CONCEPT) {
            allResults.addAll(searchConcepts(query, lang, limit, offset, ontologyIris, relationTypes));
        }

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
        return new SearchProviderResult(results, results.size());
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
                    .type(SearchType.CONCEPT)
                    .source(SearchSource.NKD)
                    .build());
        }
        return results;
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
    }
}
