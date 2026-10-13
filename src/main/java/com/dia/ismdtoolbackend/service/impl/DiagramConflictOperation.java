package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramConflictDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramPendingEditEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.DiagramConflictResolutionException;
import com.dia.ismdtoolbackend.exception.DiagramEditConflictException;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.DiagramService.ConflictResolution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Detects and resolves staged edits that two diagrams of one ontology make to the same concept. */
@Slf4j
@Component
@RequiredArgsConstructor
class DiagramConflictOperation {

    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final DiagramPendingEditRepository pendingEditRepository;
    private final DiagramScopeReader scopeReader;

    /** What the resolution settled: the ontology, and whose staged edits apply. */
    record Resolved(Long ontologyId, Long winnerDiagramId) {
    }

    /**
     * Refuses or resolves edits this diagram stages on concepts a sibling diagram also stages. Sibling rows
     * are re-read through the ontology scope, never trusted from the request. A resolution names one winner
     * and discards every loser's contested edits in a single pass; the winner is the diagram that
     * materializes, which for {@code ACCEPT_THEIRS} is not the one in the path.
     *
     * <p>The discard commits here, before any RDF is written, and stands even if every subsequent change
     * then fails — resolving a conflict is the user's decision about whose intent survives, not a
     * consequence of the write succeeding. Only contested IRIs are discarded, so a loser's uncontested
     * staged work is untouched.
     *
     * @return the ontology id and the diagram to materialize
     */
    Resolved resolveConflicts(String ontologySlug, Long diagramId,
                                     ConflictResolution onConflict, Long winnerDiagramId) {
        OntologyMetadataEntity ontology = scopeReader.requireOntology(ontologySlug);
        scopeReader.requireDiagramOf(ontology, ontologySlug, diagramId);

        // Serializes concurrent materializes of one ontology. Detection is a read-modify-write over
        // diagram_pending_edits, so without this lock two requests can each read the other as
        // non-conflicting and both go on to write RDF. No concept row is locked here, so this takes no
        // part in the concept → ontology lock order.
        ontologyMetadataRepository.findWithLockById(ontology.getId());

        Long winner = decideWinner(ontology, diagramId, onConflict, winnerDiagramId);
        // Re-detect from the WINNER's side on every path, not just the resolved one: a sibling save that
        // commits between the first query and this point stages a collision the resolution never saw.
        requireNoRemainingConflict(ontology.getId(), winner);
        return new Resolved(ontology.getId(), winner);
    }

    /** Whose staged edits materialize: this diagram when nothing collides, else the resolution's winner. */
    private Long decideWinner(OntologyMetadataEntity ontology, Long diagramId,
                              ConflictResolution onConflict, Long winnerDiagramId) {
        List<String> staged = pendingEditRepository.findStagedConceptIris(diagramId);
        if (staged.isEmpty()) {
            return noConflict(diagramId, onConflict);
        }

        List<DiagramPendingEditEntity> conflicting =
                pendingEditRepository.findConflicting(ontology.getId(), diagramId, staged);
        if (conflicting.isEmpty()) {
            return noConflict(diagramId, onConflict);
        }

        if (onConflict == null) {
            throw new DiagramEditConflictException(buildConflictReport(diagramId, conflicting));
        }

        Long winner = requireWinner(diagramId, onConflict, winnerDiagramId, conflicting);

        // Keyed on the contested IRIs, never on `staged`, so uncontested staged work survives.
        List<String> conflictedIris = conflicting.stream()
                .map(DiagramPendingEditEntity::getConceptIri)
                .distinct()
                .toList();

        int removed = pendingEditRepository.deleteConflictingExceptWinner(
                ontology.getId(), winner, conflictedIris);
        log.info("Materialize on diagram {}: {} wins, discarded {} conflicting staged edit(s) on the "
                + "other diagram(s)", diagramId, winner, removed);

        return winner;
    }

    /**
     * Nothing collides, so a resolution has nothing to decide. A stray {@code ACCEPT_THEIRS} is still
     * refused rather than materializing a diagram whose edits were never offered up here.
     */
    private Long noConflict(Long diagramId, ConflictResolution onConflict) {
        if (onConflict == ConflictResolution.ACCEPT_THEIRS) {
            throw new DiagramConflictResolutionException(
                    "Žádná kolize s jiným diagramem neexistuje; převzetí změn jiného diagramu nelze použít.");
        }
        return diagramId;
    }

    /**
     * The winning diagram id, validated against the conflict set rather than taken on trust. The endpoint
     * authorizes only the slug, and {@code ACCEPT_THEIRS} materializes a diagram other than the one in the
     * path, so the winner must appear in this ontology's conflict set.
     */
    private Long requireWinner(Long diagramId, ConflictResolution onConflict, Long winnerDiagramId,
                               List<DiagramPendingEditEntity> conflicting) {
        if (onConflict == ConflictResolution.ACCEPT_MINE) {
            if (winnerDiagramId != null && !winnerDiagramId.equals(diagramId)) {
                throw new DiagramConflictResolutionException(
                        "Parametr winnerDiagramId lze použít pouze s onConflict=ACCEPT_THEIRS.");
            }
            return diagramId;
        }

        if (winnerDiagramId == null) {
            throw new DiagramConflictResolutionException(
                    "Pro onConflict=ACCEPT_THEIRS je nutné uvést winnerDiagramId — diagram, jehož změny "
                            + "se převezmou.");
        }
        boolean inConflictSet = conflicting.stream()
                .anyMatch(row -> winnerDiagramId.equals(row.getDiagram().getId()));
        if (!inConflictSet) {
            throw new DiagramConflictResolutionException(
                    "Diagram " + winnerDiagramId + " není mezi kolidujícími diagramy.");
        }
        return winnerDiagramId;
    }

    /**
     * The final gate before any RDF is written: re-detects from the winner's side, in the transaction that
     * decided it. Runs on every path, so it catches both a collision a resolution left standing and one a
     * sibling save staged after the first detection query.
     */
    private void requireNoRemainingConflict(Long ontologyId, Long winnerDiagramId) {
        List<String> staged = pendingEditRepository.findStagedConceptIris(winnerDiagramId);
        if (staged.isEmpty()) {
            return;
        }
        List<DiagramPendingEditEntity> remaining =
                pendingEditRepository.findConflicting(ontologyId, winnerDiagramId, staged);
        if (!remaining.isEmpty()) {
            throw new DiagramEditConflictException(buildConflictReport(winnerDiagramId, remaining));
        }
    }

    /** Pairs each conflicted concept's own staged edit with the competing ones, for the 409 body. */
    private DiagramConflictDto buildConflictReport(Long diagramId,
                                                   List<DiagramPendingEditEntity> conflicting) {
        Map<String, List<DiagramConflictDto.Theirs>> theirsByIri = new LinkedHashMap<>();
        for (DiagramPendingEditEntity row : conflicting) {
            theirsByIri.computeIfAbsent(row.getConceptIri(), k -> new ArrayList<>())
                    .add(new DiagramConflictDto.Theirs(
                            row.getDiagram().getId(),
                            row.getDiagram().getName(),
                            row.getPendingEdit()));
        }

        List<DiagramConflictDto.Conflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<DiagramConflictDto.Theirs>> e : theirsByIri.entrySet()) {
            String iri = e.getKey();
            DiagramPendingEdit mine = pendingEditRepository
                    .findByDiagramIdAndConceptIri(diagramId, iri)
                    .map(DiagramPendingEditEntity::getPendingEdit)
                    .orElse(null);
            conflicts.add(new DiagramConflictDto.Conflict(iri, conceptLabel(iri), mine, e.getValue()));
        }
        return new DiagramConflictDto(conflicts);
    }

    /** Display label for the conflict report; PG metadata only, no graph fetch. */
    private Map<String, String> conceptLabel(String conceptIri) {
        return conceptMetadataRepository.findByConceptIri(conceptIri)
                .map(ConceptMetadataEntity::getConceptName)
                .map(name -> Map.of("cs", name))
                .orElse(null);
    }
}
