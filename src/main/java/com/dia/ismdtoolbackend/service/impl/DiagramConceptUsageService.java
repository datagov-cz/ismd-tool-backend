package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.models.diagram.DiagramJson;
import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramConceptUsageDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramConceptUsageKind;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * "Which diagrams draw this concept?" — the concept detail page's canvas cross-reference.
 *
 * <p>Not built on {@link DiagramServiceImpl#getDiagram}, which fetches the whole ontology graph per
 * diagram and would cost one such fetch per listed diagram to report a handful of IRIs. This path runs
 * one PG query for placements across all three membership shapes, one for the staged overlays on those
 * diagrams, one hierarchy SELECT scoped to the concept's graph, and one batched {@code resolveAll} for
 * every IRI the three name. Resolving once for the whole response rather than per diagram is what keeps
 * an added diagram to extra map rows instead of a round trip. See {@code docs/DIAGRAM_LAYER_API.md}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagramConceptUsageService {

    private final DiagramRepository diagramRepository;
    private final DiagramPendingEditRepository pendingEditRepository;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final ReferencedConceptResolutionEngine resolutionEngine;

    /**
     * Every canvas placement of one concept, addressed by the concept's slug, which is what the detail page
     * holds. The PG queries use projections rather than lazy associations, so the Fuseki calls that follow
     * need no open transaction and a slow external store never holds a database connection.
     */
    public DiagramConceptUsageDto usageForSlug(String conceptSlug) {
        ConceptMetadataEntity concept = conceptMetadataRepository.findBySlug(conceptSlug)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Pojem se slugem " + conceptSlug + " nebyl nalezen."));

        String conceptIri = concept.getConceptIri();
        List<DiagramRepository.ConceptUsageRow> rows = placements(conceptIri);
        if (rows.isEmpty()) {
            // The concept is still identified: "on no diagram" is an answer the page renders.
            return new DiagramConceptUsageDto(conceptIri, label(concept), concept.getSlug(), List.of());
        }

        List<Long> diagramIds = rows.stream().map(DiagramRepository.ConceptUsageRow::getDiagramId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, DiagramPendingEdit> overlays = overlays(conceptIri, diagramIds);

        // Live structure, fetched once; every placement layers its own overlay over it.
        JenaTDB2Repository.ConceptHierarchyLinks live =
                jenaTDB2Repository.fetchConceptHierarchy(conceptIri, concept.getGraphName());

        Map<String, ResolvedConceptDto> resolved =
                resolveEverything(conceptIri, rows, overlays, live);
        ResolvedConceptDto self = resolved.get(conceptIri);

        List<DiagramConceptUsageDto.Placement> placements = new ArrayList<>();
        for (DiagramRepository.ConceptUsageRow row : rows) {
            placements.add(placement(row, overlays.get(row.getDiagramId()), self, live, resolved));
        }

        return new DiagramConceptUsageDto(conceptIri, label(concept), concept.getSlug(), placements);
    }

    /** Every diagram drawing the concept, across all three membership shapes. */
    private List<DiagramRepository.ConceptUsageRow> placements(String conceptIri) {
        return diagramRepository.findConceptUsage(conceptIri, jsonArrayOf(conceptIri));
    }

    /**
     * The staged edit each of those diagrams holds on this concept, if any. A malformed overlay is skipped
     * rather than failing the read, matching how the entity's own accessor treats bad JSON.
     */
    private Map<Long, DiagramPendingEdit> overlays(String conceptIri, Collection<Long> diagramIds) {
        Map<Long, DiagramPendingEdit> byDiagram = new HashMap<>();
        for (DiagramPendingEditRepository.ConceptOverlayRow row
                : pendingEditRepository.findByConceptIriAcrossDiagrams(conceptIri, diagramIds)) {
            DiagramPendingEdit edit = deserialize(row);
            if (edit != null) {
                byDiagram.put(row.getDiagramId(), edit);
            }
        }
        return byDiagram;
    }

    private DiagramPendingEdit deserialize(DiagramPendingEditRepository.ConceptOverlayRow row) {
        String json = row.getPendingEditJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return DiagramJson.MAPPER.readValue(json, DiagramPendingEdit.class);
        } catch (JsonProcessingException e) {
            log.error("Skipping malformed pending-edit JSON on diagram {}", row.getDiagramId(), e);
            return null;
        }
    }

    /**
     * Resolves every IRI any placement could name in one batch: the concept itself, its live hierarchy, each
     * host class and the overlay values of every diagram. Resolving per placement would cost a round trip
     * per diagram.
     */
    private Map<String, ResolvedConceptDto> resolveEverything(
            String conceptIri,
            List<DiagramRepository.ConceptUsageRow> rows,
            Map<Long, DiagramPendingEdit> overlays,
            JenaTDB2Repository.ConceptHierarchyLinks live) {

        Set<String> iris = new LinkedHashSet<>();
        iris.add(conceptIri);
        iris.addAll(live.broader());
        iris.addAll(live.exactMatch());
        rows.forEach(r -> addIfPresent(iris, r.getHostClassIri()));

        for (DiagramPendingEdit overlay : overlays.values()) {
            addIfPresent(iris, overlay.getDomain());
            addIfPresent(iris, overlay.getRange());
            addAll(iris, overlay.getBroaderConcept());
            addAll(iris, overlay.getExactMatch());
        }
        return resolutionEngine.resolveAll(List.copyOf(iris));
    }

    /**
     * One placement's view: live structure with that diagram's overlay layered over it, field by field. Only
     * a non-null overlay field overrides, so a staged {@code range} does not blank the live {@code domain}.
     * Domain and range come from the concept's own resolution, so a class has none and reports null.
     */
    private DiagramConceptUsageDto.Placement placement(
            DiagramRepository.ConceptUsageRow row,
            DiagramPendingEdit overlay,
            ResolvedConceptDto self,
            JenaTDB2Repository.ConceptHierarchyLinks live,
            Map<String, ResolvedConceptDto> resolved) {

        ResolvedConceptDto domain = self != null ? self.resolvedDomain() : null;
        ResolvedConceptDto range = self != null ? self.resolvedRange() : null;
        List<String> broader = live.broader();
        List<String> exactMatch = live.exactMatch();

        if (overlay != null) {
            if (overlay.getDomain() != null) {
                domain = resolved.get(overlay.getDomain());
            }
            if (overlay.getRange() != null) {
                range = resolved.get(overlay.getRange());
            }
            // An explicitly-empty list stages "clear this predicate" rather than "unset".
            if (overlay.getBroaderConcept() != null) {
                broader = overlay.getBroaderConcept();
            }
            if (overlay.getExactMatch() != null) {
                exactMatch = overlay.getExactMatch();
            }
        }

        return new DiagramConceptUsageDto.Placement(
                row.getDiagramId(),
                row.getDiagramName(),
                row.getOntologySlug(),
                kindOf(row),
                resolved.get(row.getHostClassIri()),
                domain,
                range,
                resolveList(broader, resolved),
                resolveList(exactMatch, resolved),
                overlay != null);
    }

    /** Drops IRIs that resolved to nothing rather than emitting nulls the FE would filter. */
    private List<ResolvedConceptDto> resolveList(List<String> iris,
                                                 Map<String, ResolvedConceptDto> resolved) {
        if (iris == null || iris.isEmpty()) {
            return List.of();
        }
        return iris.stream().map(resolved::get).filter(Objects::nonNull).toList();
    }

    /** An unrecognised kind is a bug in the query rather than user input, so it fails loudly. */
    private DiagramConceptUsageKind kindOf(DiagramRepository.ConceptUsageRow row) {
        return DiagramConceptUsageKind.valueOf(row.getKind());
    }

    /** PG holds a single Czech name; the multilingual label lives in RDF and is not worth a fetch. */
    private Map<String, String> label(ConceptMetadataEntity concept) {
        return concept.getConceptName() == null ? Map.of() : Map.of("cs", concept.getConceptName());
    }

    /**
     * The concept IRI as a one-element JSON array, the right-hand side of the {@code @>} containment test.
     * Escaped through Jackson rather than concatenated, so an IRI holding a quote or backslash cannot
     * produce malformed JSON and fail the cast.
     */
    private String jsonArrayOf(String conceptIri) {
        StringBuilder sb = new StringBuilder("[\"");
        for (int i = 0; i < conceptIri.length(); i++) {
            char c = conceptIri.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.append("\"]").toString();
    }

    private static void addIfPresent(Set<String> target, String iri) {
        if (iri != null && !iri.isBlank()) {
            target.add(iri);
        }
    }

    private static void addAll(Set<String> target, List<String> iris) {
        if (iris != null) {
            iris.forEach(iri -> addIfPresent(target, iri));
        }
    }
}