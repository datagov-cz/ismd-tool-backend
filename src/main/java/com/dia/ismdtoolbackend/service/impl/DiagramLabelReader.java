package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramSummaryDto;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.service.OntologyLabelLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Adds the owning ontology's label, read from Fuseki, to diagram summaries. Called with no transaction open. */
@Component
@RequiredArgsConstructor
class DiagramLabelReader {

    private final OntologyLabelLookup ontologyLabelLookup;

    /**
     * Maps a page of rows, resolving every ontology's prefLabel in ONE batched RDF fetch. A
     * per-row lookup here would turn a single list into N Fuseki round-trips.
     */
    List<DiagramSummaryDto> withOntologyLabels(List<DiagramRepository.DiagramSummaryRow> rows) {
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

    /** The summary with its ontology's label filled in. */
    DiagramSummaryDto withOntologyLabel(DiagramSummaryDto summary) {
        return new DiagramSummaryDto(
                summary.diagramId(),
                summary.name(),
                summary.ontologySlug(),
                summary.graphName(),
                ontologyLabelLookup.labelFor(summary.graphName()),
                summary.nodeCount(),
                summary.updatedAt());
    }
}
