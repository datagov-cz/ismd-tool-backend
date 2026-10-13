package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramSummaryDto;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.DiagramNameConflictException;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Creates, renames and deletes diagrams. */
@Component
@RequiredArgsConstructor
class DiagramLifecycleOperation {

    private final DiagramRepository diagramRepository;
    private final DiagramScopeReader scopeReader;
    private final DiagramSnapshotReader snapshotReader;

    DiagramSnapshot commitNewDiagram(String ontologySlug, String name) {
        OntologyMetadataEntity ontology = scopeReader.requireOntology(ontologySlug);
        String resolved = name == null || name.isBlank() ? defaultName(ontology) : name.trim();
        // Pre-check for a 409 naming the clash; the DB constraint stays the real guarantee.
        if (diagramRepository.existsByOntologyMetadataIdAndName(ontology.getId(), resolved)) {
            throw new DiagramNameConflictException(resolved);
        }
        DiagramEntity diagram = new DiagramEntity();
        diagram.setOntologyMetadata(ontology);
        diagram.setName(resolved);
        try {
            return snapshotReader.snapshot(diagramRepository.saveAndFlush(diagram), ontology);
        } catch (DataIntegrityViolationException e) {
            // Lost a concurrent create race for the same name.
            throw new DiagramNameConflictException(resolved);
        }
    }

    /** Renames one diagram. PG only, no graph read. Renaming to the current name is a no-op. */
    DiagramSummaryDto renameDiagram(String ontologySlug, Long diagramId, String name) {
        OntologyMetadataEntity ontology = scopeReader.requireOntology(ontologySlug);
        DiagramEntity diagram = scopeReader.requireDiagramOf(ontology, ontologySlug, diagramId);

        // The service is also called directly, so the DTO's @NotBlank is not the only gate.
        String resolved = name == null ? "" : name.trim();
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException("Název diagramu nesmí být prázdný.");
        }
        if (resolved.equals(diagram.getName())) {
            return summaryOf(diagram, ontology);
        }
        // Excludes this diagram so an unchanged name never collides with itself;
        // uq_diagrams_ontology_name stays the real guarantee.
        if (diagramRepository.existsByOntologyMetadataIdAndNameAndIdNot(
                ontology.getId(), resolved, diagram.getId())) {
            throw new DiagramNameConflictException(resolved);
        }

        diagram.setName(resolved);
        try {
            return summaryOf(diagramRepository.saveAndFlush(diagram), ontology);
        } catch (DataIntegrityViolationException e) {
            // Lost a concurrent rename race for the same name.
            throw new DiagramNameConflictException(resolved);
        }
    }

    /**
     * Summary for one loaded diagram, without the ontology label; the node count comes off the loaded
     * collection, no query.
     */
    private DiagramSummaryDto summaryOf(DiagramEntity diagram, OntologyMetadataEntity ontology) {
        return new DiagramSummaryDto(
                diagram.getId(),
                diagram.getName(),
                ontology.getSlug(),
                ontology.getGraphName(),
                null,
                diagram.getNodes().size(),
                diagram.getUpdatedAt() != null ? diagram.getUpdatedAt().toString() : null);
    }

    /**
     * How many numbered candidates to try before falling back to a UUID suffix. One query each, so the cap
     * bounds the worst case; a slovník with this many unnamed diagrams is far past where a readable
     * auto-name helps anyone, and the UUID still guarantees a successful create.
     */
    private static final int MAX_DEFAULT_NAME_ATTEMPTS = 1000;

    /** "Nový diagram", numbered when taken, so a create with no name never fails on the name. */
    private String defaultName(OntologyMetadataEntity ontology) {
        String base = "Nový diagram";
        if (!diagramRepository.existsByOntologyMetadataIdAndName(ontology.getId(), base)) {
            return base;
        }
        for (int i = 2; i < MAX_DEFAULT_NAME_ATTEMPTS; i++) {
            String candidate = base + " " + i;
            if (!diagramRepository.existsByOntologyMetadataIdAndName(ontology.getId(), candidate)) {
                return candidate;
            }
        }
        return base + " " + UUID.randomUUID();
    }

    /** Deletes one diagram. Nodes, waypoints and staged edits cascade with it; concepts do not. */
    void deleteDiagram(String ontologySlug, Long diagramId) {
        OntologyMetadataEntity ontology = scopeReader.requireOntology(ontologySlug);
        diagramRepository.delete(scopeReader.requireDiagramOf(ontology, ontologySlug, diagramId));
    }
}
