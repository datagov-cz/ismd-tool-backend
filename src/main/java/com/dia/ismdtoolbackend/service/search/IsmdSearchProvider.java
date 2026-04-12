package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.RelationType;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.enums.SearchType;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class IsmdSearchProvider implements SearchProvider {

    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final ConceptMetadataRepository conceptMetadataRepository;

    @Override
    public SearchProviderResult search(String query, SearchType type, int limit, int offset,
                                       String lang, List<String> ontologyIris,
                                       List<RelationType> relationTypes, String userId) {
        List<SearchResultDto> allResults = new ArrayList<>();

        if (type == null || type == SearchType.ONTOLOGY) {
            allResults.addAll(searchOntologies(query, userId));
        }

        if (type == null || type == SearchType.CONCEPT) {
            allResults.addAll(searchConcepts(query, userId, ontologyIris));
        }

        // Dedup by IRI
        LinkedHashMap<String, SearchResultDto> deduped = new LinkedHashMap<>();
        for (SearchResultDto result : allResults) {
            if (result.getIri() != null) {
                deduped.putIfAbsent(result.getIri(), result);
            }
        }

        List<SearchResultDto> results = new ArrayList<>(deduped.values());

        // Apply offset and limit in-memory (PG queries return full matches)
        int fromIndex = Math.min(offset, results.size());
        int toIndex = Math.min(fromIndex + limit, results.size());
        List<SearchResultDto> paged = results.subList(fromIndex, toIndex);

        return new SearchProviderResult(paged, results.size());
    }

    private List<SearchResultDto> searchOntologies(String query, String userId) {
        List<OntologyMetadataEntity> entities = ontologyMetadataRepository.searchByText(query, userId);

        return entities.stream()
                .map(e -> SearchResultDto.builder()
                        .iri(e.getGraphName())
                        .slug(e.getSlug())
                        .label(e.getSlug())
                        .type(SearchType.ONTOLOGY)
                        .source(SearchSource.ISMD)
                        .isPublished(e.getIsPublished())
                        .build())
                .toList();
    }

    private List<SearchResultDto> searchConcepts(String query, String userId, List<String> ontologyIris) {
        boolean hasGraphFilter = ontologyIris != null && !ontologyIris.isEmpty();
        List<String> graphNames = hasGraphFilter ? ontologyIris : List.of();

        List<ConceptMetadataEntity> entities = conceptMetadataRepository.searchByText(
                query, userId, hasGraphFilter, graphNames);

        return entities.stream()
                .map(e -> SearchResultDto.builder()
                        .iri(e.getConceptIri())
                        .slug(e.getSlug())
                        .label(e.getConceptName())
                        .type(SearchType.CONCEPT)
                        .source(SearchSource.ISMD)
                        .conceptType(e.getConceptType())
                        .ontologyIri(e.getGraphName())
                        .isPublished(e.getIsPublished())
                        .build())
                .toList();
    }
}