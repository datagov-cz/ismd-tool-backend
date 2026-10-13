package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramSummaryDto;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.service.OntologyLabelLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Lists diagrams as summary rows, across all ontologies or for one. */
@Component
@RequiredArgsConstructor
class DiagramListReader {

    private final DiagramRepository diagramRepository;
    private final DiagramScopeReader scopeReader;
    private final OntologyLabelLookup ontologyLabelLookup;

    List<DiagramSummaryDto> listAll(String userId) {
        // Blank is treated as absent: an omitted query param and `?userId=` should both mean
        // "no filter" rather than "match the empty user", which would return nothing.
        String filter = userId == null || userId.isBlank() ? null : userId.trim();
        return withOntologyLabels(diagramRepository.findSummaries(null, filter));
    }

    List<DiagramSummaryDto> listForOntology(String ontologySlug) {
        OntologyMetadataEntity ontology = scopeReader.requireOntology(ontologySlug);
        return withOntologyLabels(diagramRepository.findSummaries(ontology.getId(), null));
    }

    /**
     * Maps a page of rows, resolving every ontology's prefLabel in ONE batched RDF fetch. A
     * per-row lookup here would turn a single list into N Fuseki round-trips.
     */
    private List<DiagramSummaryDto> withOntologyLabels(List<DiagramRepository.DiagramSummaryRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, String>> labels = ontologyLabelLookup.labelsByGraph(
                rows.stream().map(DiagramRepository.DiagramSummaryRow::getGraphName).toList());
        return rows.stream()
                .map(row -> toSummary(row, labels == null ? null : labels.get(row.getGraphName())))
                .toList();
    }

    private DiagramSummaryDto toSummary(DiagramRepository.DiagramSummaryRow row,
                                        Map<String, String> ontologyLabel) {
        return new DiagramSummaryDto(
                row.getDiagramId(),
                row.getName(),
                row.getSlug(),
                row.getGraphName(),
                ontologyLabel,
                (int) row.getNodeCount(),
                row.getUpdatedAt() != null ? row.getUpdatedAt().toString() : null);
    }
}
