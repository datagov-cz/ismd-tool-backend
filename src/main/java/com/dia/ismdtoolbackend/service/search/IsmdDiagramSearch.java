package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.service.OntologyLabelLookup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Diagram rows of an ISMD search, with their owning ontology's label. */
@Slf4j
@Component
@RequiredArgsConstructor
class IsmdDiagramSearch {

    private final DiagramSearchLookup diagramSearchLookup;
    private final OntologyLabelLookup ontologyLabelLookup;

    /**
     * One page of DIAGRAM results: one per ontology that has a diagram and whose slug matches the query.
     * Keyed by a synthetic {@code graphName + "#diagram"} IRI so a type=null pass keeps it distinct from the
     * ontology's ONTOLOGY row through dedup. A PG failure degrades to no diagram results rather than
     * failing search.
     */
    List<SearchResultDto> searchDiagrams(String query, Boolean publishedFilter,
                                                 int limit, int offset) {
        List<SearchResultDto> rows;
        try {
            rows = diagramSearchLookup.search(query, publishedFilter, limit, offset);
        } catch (RuntimeException e) {
            log.warn("PG diagram search failed, continuing without diagram results: {}", e.getMessage());
            return List.of();
        }
        // Deliberately OUTSIDE diagramSearchLookup's transaction: the ontology label comes from
        // Fuseki, and holding a pooled PG connection across that HTTP call is what the lookup's
        // own doc warns against. One batched fetch for the page, not one per row.
        attachOntologyLabels(rows);
        return rows;
    }

    /**
     * Fills in each diagram row's owning-ontology prefLabel. Names live only in RDF, so this is one
     * batched CONSTRUCT for the whole page; rows whose ontology has no label simply keep a null.
     */
    void attachOntologyLabels(List<SearchResultDto> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Map<String, String>> labels = ontologyLabelLookup.labelsByGraph(
                rows.stream().map(SearchResultDto::getOntologyIri).toList());
        if (labels == null || labels.isEmpty()) {
            return;
        }
        for (SearchResultDto row : rows) {
            row.setOntologyLabel(labels.get(row.getOntologyIri()));
        }
    }

    Integer countDiagramMatches(String query, Boolean publishedFilter) {
        try {
            return (int) diagramSearchLookup.count(query, publishedFilter);
        } catch (RuntimeException e) {
            log.warn("PG diagram total-count failed: {}", e.getMessage());
            return null;
        }
    }
}
