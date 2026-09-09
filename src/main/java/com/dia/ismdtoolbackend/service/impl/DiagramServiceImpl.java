package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramConflictDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramSummaryDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.MaterializeResultDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.ViewportDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEdgeEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.exception.DiagramConflictResolutionException;
import com.dia.ismdtoolbackend.exception.DiagramContentUnavailableException;
import com.dia.ismdtoolbackend.exception.DiagramEditConflictException;
import com.dia.ismdtoolbackend.exception.DiagramNameConflictException;
import com.dia.ismdtoolbackend.exception.DiagramReadbackFailedException;
import com.dia.ismdtoolbackend.exception.DiagramVersionConflictException;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.entity.DiagramPendingEditEntity;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.DiagramService;
import com.dia.ismdtoolbackend.service.DiagramService.ConflictResolution;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Diagram reads (layout ⋈ live content, overlays applied, edges projected) and the Save-time layout,
 * membership and overlay reconcile. Pure PG — never writes RDF; materialize is delegated to
 * {@link DiagramMaterializeService}. See {@code docs/DIAGRAM_LAYER.md}.
 */
@Slf4j
@Service
public class DiagramServiceImpl implements DiagramService {

    private final DiagramRepository diagramRepository;
    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final OntologyDetailExtractor detailExtractor;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final DiagramMaterializeService materializeService;
    private final DiagramLayoutReconciler layoutReconciler;
    private final DiagramPendingEditRepository pendingEditRepository;
    private final DiagramMapper mapper;

    /**
     * Self-proxy — a direct {@code this.commitLayout(...)} would bypass the transaction proxy and run
     * untransacted.
     *
     * <p>Typed as the impl, not {@link DiagramService}, because the four seams it reaches
     * ({@code commitNewDiagram}, {@code loadForRead}, {@code commitLayout}, {@code resolveConflicts}) return
     * impl-internal types — {@code DiagramSnapshot}, {@code Resolved}. Narrowing the field to the interface
     * would mean publishing those types on the public API to keep the same call sites, which is a worse
     * trade than four {@code public} methods on the impl.
     */
    private final DiagramServiceImpl self;

    public DiagramServiceImpl(DiagramRepository diagramRepository,
                              OntologyMetadataRepository ontologyMetadataRepository,
                              ConceptMetadataRepository conceptMetadataRepository,
                              OntologyDetailExtractor detailExtractor,
                              JenaTDB2Repository jenaTDB2Repository,
                              DiagramMaterializeService materializeService,
                              DiagramLayoutReconciler layoutReconciler,
                              DiagramPendingEditRepository pendingEditRepository,
                              DiagramMapper mapper,
                              @Lazy DiagramServiceImpl self) {
        this.diagramRepository = diagramRepository;
        this.ontologyMetadataRepository = ontologyMetadataRepository;
        this.conceptMetadataRepository = conceptMetadataRepository;
        this.detailExtractor = detailExtractor;
        this.jenaTDB2Repository = jenaTDB2Repository;
        this.materializeService = materializeService;
        this.layoutReconciler = layoutReconciler;
        this.pendingEditRepository = pendingEditRepository;
        this.mapper = mapper;
        this.self = self;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiagramSummaryDto> listAll() {
        return diagramRepository.findSummaries(null).stream()
                .map(this::toSummary)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiagramSummaryDto> listForOntology(String ontologySlug) {
        OntologyMetadataEntity ontology = requireOntology(ontologySlug);
        return diagramRepository.findSummaries(ontology.getId()).stream()
                .map(this::toSummary)
                .toList();
    }

    private DiagramSummaryDto toSummary(DiagramRepository.DiagramSummaryRow row) {
        return new DiagramSummaryDto(
                row.getDiagramId(),
                row.getName(),
                row.getSlug(),
                row.getGraphName(),
                (int) row.getNodeCount(),
                row.getUpdatedAt() != null ? row.getUpdatedAt().toString() : null);
    }

    /** Create an empty canvas. Creation is explicit — a read never provisions a row. */
    @Override
    public DiagramDto createDiagram(String ontologySlug, String name) {
        DiagramSnapshot snapshot = self.commitNewDiagram(ontologySlug, name);
        return assemble(ontologySlug, snapshot, readbackConcepts(snapshot));
    }

    @Transactional
    public DiagramSnapshot commitNewDiagram(String ontologySlug, String name) {
        OntologyMetadataEntity ontology = requireOntology(ontologySlug);
        String resolved = name == null || name.isBlank() ? defaultName(ontology) : name.trim();
        // Pre-check for a 409 naming the clash; the DB constraint stays the real guarantee.
        if (diagramRepository.existsByOntologyMetadataIdAndName(ontology.getId(), resolved)) {
            throw new DiagramNameConflictException(resolved);
        }
        DiagramEntity diagram = new DiagramEntity();
        diagram.setOntologyMetadata(ontology);
        diagram.setName(resolved);
        try {
            return snapshot(diagramRepository.saveAndFlush(diagram), ontology);
        } catch (DataIntegrityViolationException e) {
            // Lost a concurrent create race for the same name.
            throw new DiagramNameConflictException(resolved);
        }
    }

    /** Renames one diagram. PG only, no graph read. Renaming to the current name is a no-op. */
    @Override
    @Transactional
    public DiagramSummaryDto renameDiagram(String ontologySlug, Long diagramId, String name) {
        OntologyMetadataEntity ontology = requireOntology(ontologySlug);
        DiagramEntity diagram = requireDiagramOf(ontology, ontologySlug, diagramId);

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

    /** Summary for one loaded diagram; the node count comes off the loaded collection, no query. */
    private DiagramSummaryDto summaryOf(DiagramEntity diagram, OntologyMetadataEntity ontology) {
        return new DiagramSummaryDto(
                diagram.getId(),
                diagram.getName(),
                ontology.getSlug(),
                ontology.getGraphName(),
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
    @Override
    @Transactional
    public void deleteDiagram(String ontologySlug, Long diagramId) {
        OntologyMetadataEntity ontology = requireOntology(ontologySlug);
        diagramRepository.delete(requireDiagramOf(ontology, ontologySlug, diagramId));
    }

    /**
     * Untransacted: {@link #loadForRead} returns a detached snapshot, so the Fuseki fetch holds no
     * connection. An unreadable own graph is a 502, the same status the save path returns for the same
     * failure.
     */
    @Override
    public DiagramDto getDiagram(String ontologySlug, Long diagramId) {
        DiagramSnapshot snapshot = self.loadForRead(ontologySlug, diagramId);
        LiveConcepts live;
        try {
            live = liveContent(snapshot);
        } catch (RuntimeException e) {
            throw new DiagramContentUnavailableException(e);
        }
        return assemble(ontologySlug, snapshot, live);
    }

    @Transactional(readOnly = true)
    public DiagramSnapshot loadForRead(String ontologySlug, Long diagramId) {
        OntologyMetadataEntity ontology = requireOntology(ontologySlug);
        return snapshot(requireDiagramOf(ontology, ontologySlug, diagramId), ontology);
    }


    /**
     * Commits the layout, then fetches the graph outside that transaction so a slow Fuseki holds no
     * connection. A fetch failure lands after a durable write and surfaces as
     * {@link DiagramReadbackFailedException} — reload, not retry. See {@code docs/DIAGRAM_LAYER.md}.
     */
    @Override
    public DiagramDto saveLayout(String ontologySlug, Long diagramId, DiagramLayoutDto layout) {
        DiagramSnapshot snapshot = self.commitLayout(ontologySlug, diagramId, layout);
        return assemble(ontologySlug, snapshot, readbackConcepts(snapshot));
    }

    @Transactional
    public DiagramSnapshot commitLayout(String ontologySlug, Long diagramId, DiagramLayoutDto layout) {
        OntologyMetadataEntity ontology = requireOntology(ontologySlug);
        DiagramEntity diagram = requireDiagramOf(ontology, ontologySlug, diagramId);
        requireCurrentVersion(diagram, layout.version());

        Map<String, DiagramNodeEntity> incoming = layoutReconciler.reconcileNodes(diagram, layout);
        // New nodes need an identity before parentId references resolve against them.
        diagramRepository.saveAndFlush(diagram);
        layoutReconciler.finalizeLayout(diagram, layout, incoming);
        diagram.touch();
        // The snapshot must carry the post-increment @Version the client echoes on its next save.
        diagramRepository.saveAndFlush(diagram);

        return snapshot(diagram, ontology);
    }

    /**
     * The resolved live content plus the IRIs whose graph could not be read at all. The two absences are
     * different facts: an IRI missing from {@code byIri} after its graph was read is deleted, one in
     * {@code unavailable} is only unreadable right now. See {@link DiagramDto.NodeData#stale()}.
     */
    private record LiveConcepts(Map<String, ConceptDetailModel> byIri, Set<String> unavailable) {

        ConceptDetailModel get(String iri) {
            return byIri.get(iri);
        }

        boolean isUnavailable(String iri) {
            return unavailable.contains(iri);
        }
    }

    /** Live content for an already-committed write; a Fuseki failure here is a readback, not a rollback. */
    private LiveConcepts readbackConcepts(DiagramSnapshot snapshot) {
        try {
            return liveContent(snapshot);
        } catch (RuntimeException e) {
            throw new DiagramReadbackFailedException(snapshot.version(), e);
        }
    }

    /**
     * Merges content for foreign nodes — concepts referenced from other ontologies — into the own-graph
     * map so they render with a label instead of a bare IRI. One fetch per distinct foreign graph;
     * own-graph entries win. The own graph fails closed, since without it there is no diagram to render; a
     * foreign graph that fails to load leaves the rest of the canvas usable and marks only its own nodes
     * unavailable.
     */
    private LiveConcepts liveContent(DiagramSnapshot snapshot) {
        Map<String, ConceptDetailModel> own = liveConcepts(snapshot.graphName());
        if (snapshot.foreignGraphs().isEmpty()) {
            return new LiveConcepts(own, Set.of());
        }
        Map<String, List<String>> irisByGraph = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : snapshot.foreignGraphs().entrySet()) {
            irisByGraph.computeIfAbsent(e.getValue(), g -> new ArrayList<>()).add(e.getKey());
        }

        Map<String, ConceptDetailModel> merged = new HashMap<>(own);
        Set<String> unavailable = new HashSet<>();
        for (Map.Entry<String, List<String>> e : irisByGraph.entrySet()) {
            Map<String, ConceptDetailModel> foreign;
            try {
                foreign = liveConcepts(e.getKey());
            } catch (RuntimeException ex) {
                log.warn("Foreign graph {} could not be read for diagram {}; its {} node(s) render as "
                        + "unavailable, not deleted", e.getKey(), snapshot.diagramId(), e.getValue().size(), ex);
                unavailable.addAll(e.getValue());
                continue;
            }
            for (String iri : e.getValue()) {
                ConceptDetailModel detail = foreign.get(iri);
                if (detail != null) {
                    merged.putIfAbsent(iri, detail);
                }
            }
        }
        return new LiveConcepts(merged, unavailable);
    }

    /**
     * Optimistic lock enforced here rather than by JPA: {@code saveLayout} re-reads the diagram in its own
     * transaction, so {@code @Version} would only compare that value against itself. Membership is a full
     * replace, so a stale save would delete another editor's nodes. A null version is accepted only for a
     * diagram with no saved state yet.
     */
    private void requireCurrentVersion(DiagramEntity diagram, Long clientVersion) {
        Long stored = diagram.getVersion();
        if (clientVersion == null && (stored == null || stored == 0L) && diagram.getNodes().isEmpty()) {
            return;
        }
        if (!Objects.equals(clientVersion, stored)) {
            log.warn("Rejected stale diagram save for {}: client version {}, stored {}",
                    diagram.getOntologyMetadata().getSlug(), clientVersion, stored);
            throw new DiagramVersionConflictException(
                    "Diagram byl mezitím uložen jiným editorem; načtěte jej znovu a uložte změny znovu.");
        }
    }

    // Not transactional: each staged change materializes in its own REQUIRES_NEW transaction, so a
    // failure cannot roll back earlier successes.
    @Override
    public MaterializeResultDto materialize(String ontologySlug, Long diagramId,
                                            ConflictResolution onConflict, Long winnerDiagramId) {
        // Detection and resolution commit before the per-change loop writes any RDF.
        Resolved resolved = self.resolveConflicts(ontologySlug, diagramId, onConflict, winnerDiagramId);
        return materializeService.materialize(resolved.winnerDiagramId(), resolved.ontologyId());
    }

    /** What the resolution settled: the ontology, and whose staged edits apply. */
    public record Resolved(Long ontologyId, Long winnerDiagramId) {
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
    @Transactional
    public Resolved resolveConflicts(String ontologySlug, Long diagramId,
                                     ConflictResolution onConflict, Long winnerDiagramId) {
        OntologyMetadataEntity ontology = requireOntology(ontologySlug);
        requireDiagramOf(ontology, ontologySlug, diagramId);

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

    // ---- snapshot -------------------------------------------------------------------------------

    /**
     * Everything the response needs from PG, detached from the persistence context so the Fuseki fetch and
     * the assembly after it touch no lazy association. {@code nodes} preserves the diagram's node order.
     */
    public record DiagramSnapshot(
            Long diagramId,
            String name,
            String graphName,
            Long version,
            ViewportDto viewport,
            List<DiagramNodeEntity> nodes,
            Map<String, ConceptType> types,
            Map<String, String> slugs,
            Map<Long, String> nodeIriByRowId,
            Map<String, List<EdgeWaypoint>> edgeWaypoints,
            /* Edge membership: the projected edge ids placed on this canvas. */
            Set<String> onCanvasEdges,
            /* Staged edits by concept IRI. Independent of `nodes`; either may exist without the other. */
            Map<String, DiagramPendingEdit> overlays,
            /* Graph name per foreign node IRI. An NKD IRI has no PG row, so no entry. */
            Map<String, String> foreignGraphs
    ) {
    }

    /**
     * Captures the diagram's PG state; must run inside the transaction that read or wrote it. Two metadata
     * queries in all — types, slugs and foreign graph names are derived from the same two result sets.
     */
    private DiagramSnapshot snapshot(DiagramEntity diagram, OntologyMetadataEntity ontology) {
        String graphName = ontology.getGraphName();
        List<DiagramNodeEntity> nodes = new ArrayList<>(diagram.getNodes());
        Map<Long, String> nodeIriByRowId = new HashMap<>();
        for (DiagramNodeEntity n : nodes) {
            if (n.getId() != null) {
                nodeIriByRowId.put(n.getId(), n.getConceptIri());
            }
        }

        List<ConceptMetadataEntity> ownConcepts = conceptMetadataRepository.findByGraphName(graphName);
        List<ConceptMetadataEntity> foreignConcepts = foreignMetadata(nodes);

        DiagramSnapshot snapshot = new DiagramSnapshot(diagram.getId(), diagram.getName(), graphName,
                diagram.getVersion(), mapper.toViewport(diagram), nodes,
                conceptTypes(ownConcepts, foreignConcepts), conceptSlugs(ownConcepts, foreignConcepts),
                nodeIriByRowId, edgeWaypoints(diagram), onCanvasEdges(diagram), overlays(diagram),
                foreignGraphs(foreignConcepts));
        log.debug("Diagram {} snapshot: {} node(s), {} placed edge(s), {} overlay(s), {} own concept(s), "
                        + "{} foreign node(s) across {} graph(s), version {}",
                diagram.getId(), nodes.size(), snapshot.onCanvasEdges().size(), snapshot.overlays().size(),
                ownConcepts.size(), snapshot.foreignGraphs().size(),
                snapshot.foreignGraphs().values().stream().distinct().count(), snapshot.version());
        return snapshot;
    }

    /** The projected edge ids on this canvas, one row per placed edge. */
    private Set<String> onCanvasEdges(DiagramEntity diagram) {
        Set<String> keys = new HashSet<>();
        for (DiagramEdgeEntity edge : diagram.getEdges()) {
            if (edge.getEdgeKey() != null) {
                keys.add(edge.getEdgeKey());
            }
        }
        return keys;
    }

    /** Graph name per foreign node IRI; an external IRI with no PG row stays absent. */
    private Map<String, String> foreignGraphs(List<ConceptMetadataEntity> foreignConcepts) {
        Map<String, String> byIri = new HashMap<>();
        for (ConceptMetadataEntity c : foreignConcepts) {
            if (c.getGraphName() != null) {
                byIri.put(c.getConceptIri(), c.getGraphName());
            }
        }
        return byIri;
    }

    private static List<String> foreignIris(List<DiagramNodeEntity> nodes) {
        return nodes.stream()
                .filter(DiagramNodeEntity::isForeign)
                .map(DiagramNodeEntity::getConceptIri)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /** Staged edits by concept IRI, read inside the transaction so assembly sees a detached snapshot. */
    private Map<String, DiagramPendingEdit> overlays(DiagramEntity diagram) {
        if (diagram.getId() == null) {
            return Map.of();   // not persisted, so nothing can be staged against it
        }
        Map<String, DiagramPendingEdit> byIri = new HashMap<>();
        for (DiagramPendingEditEntity row : pendingEditRepository.findByDiagramId(diagram.getId())) {
            DiagramPendingEdit edit = row.getPendingEdit();
            if (edit != null) {
                byIri.put(row.getConceptIri(), edit);
            }
        }
        return byIri;
    }

    /** Waypoints by projected edge id. A key that no longer projects is ignored; the next Save replaces the set. */
    private Map<String, List<EdgeWaypoint>> edgeWaypoints(DiagramEntity diagram) {
        Map<String, List<EdgeWaypoint>> byKey = new HashMap<>();
        for (DiagramEdgeEntity edge : diagram.getEdges()) {
            List<EdgeWaypoint> segments = edge.getSegments();
            if (edge.getEdgeKey() == null || segments == null) {
                continue;
            }
            byKey.put(edge.getEdgeKey(), segments);
        }
        return byKey;
    }

    // ---- assembly -------------------------------------------------------------------------------

    /** Joins layout rows to live content, applies overlays, projects edges and property rows. */
    private DiagramDto assemble(String ontologySlug, DiagramSnapshot snapshot, LiveConcepts live) {
        EdgeProjector projector = new EdgeProjector(mapper, snapshot.edgeWaypoints(),
                snapshot.overlays(), new HashSet<>(foreignIris(snapshot.nodes())),
                snapshot.onCanvasEdges());
        Map<String, List<DiagramDto.PropertyRow>> rows =
                projector.propertyRows(snapshot.nodes(), live.byIri(), snapshot.types(), snapshot.slugs());

        // nodes[] is classes only; relationships render as edges and properties as rows.
        List<DiagramDto.Node> nodes = new ArrayList<>();
        for (DiagramNodeEntity node : snapshot.nodes()) {
            if (!ConceptType.isCanvasMember(snapshot.types().get(node.getConceptIri()))) {
                continue;
            }
            nodes.add(toNode(snapshot, node, live, rows.getOrDefault(node.getConceptIri(), List.of())));
        }

        List<DiagramDto.Edge> edges =
                projector.project(snapshot.nodes(), live.byIri(), snapshot.types(), snapshot.slugs());

        log.debug("Diagram {} assembled: {} of {} node(s) on canvas, {} projected edge(s), {} property row(s)"
                        + ", {} live concept(s), {} unavailable",
                snapshot.diagramId(), nodes.size(), snapshot.nodes().size(), edges.size(),
                rows.values().stream().mapToInt(List::size).sum(), live.byIri().size(),
                live.unavailable().size());

        return new DiagramDto(snapshot.diagramId(), snapshot.name(), ontologySlug, snapshot.version(),
                snapshot.viewport(), nodes, edges, pendingEdits(snapshot, live));
    }

    /** Every staged edit on the diagram — what Převzít applies. Not filtered by canvas membership. */
    private List<DiagramDto.PendingEditEntry> pendingEdits(DiagramSnapshot snapshot, LiveConcepts live) {
        List<DiagramDto.PendingEditEntry> entries = new ArrayList<>();
        for (Map.Entry<String, DiagramPendingEdit> staged : snapshot.overlays().entrySet()) {
            String iri = staged.getKey();
            ConceptDetailModel detail = live.get(iri);
            boolean unavailable = live.isUnavailable(iri);
            entries.add(new DiagramDto.PendingEditEntry(
                    iri,
                    snapshot.types().get(iri),
                    snapshot.slugs().get(iri),
                    detail != null ? detail.getName() : null,
                    detail == null && !unavailable,
                    unavailable,
                    staged.getValue()));
        }
        entries.sort(Comparator.comparing(DiagramDto.PendingEditEntry::iri));
        return entries;
    }

    /** Builds one render-ready node; type and slug come from the snapshot, never a fresh PG read. */
    private DiagramDto.Node toNode(DiagramSnapshot snapshot, DiagramNodeEntity node,
                                   LiveConcepts live, List<DiagramDto.PropertyRow> properties) {
        String conceptIri = node.getConceptIri();
        ConceptType type = snapshot.types().get(conceptIri);
        String slug = snapshot.slugs().get(conceptIri);
        ConceptDetailModel detail = live.get(conceptIri);
        Map<String, String> label = detail != null ? detail.getName() : null;
        DiagramDto.NodeData data = mapper.toNodeData(node, type, slug, label, detail, properties,
                snapshot.overlays().get(conceptIri), live.isUnavailable(conceptIri));
        String parentId = node.getParentNodeId() != null
                ? parentWireId(snapshot, node.getParentNodeId())
                : null;
        return new DiagramDto.Node(
                mapper.nodeId(conceptIri),
                mapper.nodeType(type),
                mapper.toPosition(node),
                parentId,
                node.isCollapsed(),
                data);
    }

    private String parentWireId(DiagramSnapshot snapshot, Long parentNodeRowId) {
        String conceptIri = snapshot.nodeIriByRowId().get(parentNodeRowId);
        return conceptIri != null ? mapper.nodeId(conceptIri) : null;
    }

    // ---- lookups --------------------------------------------------------------------------------

    private OntologyMetadataEntity requireOntology(String ontologySlug) {
        return ontologyMetadataRepository.findBySlug(ontologySlug)
                .orElseThrow(() -> new OntologyNotFoundException(
                        "Metadata slovníku s názvem " + ontologySlug + " nebyla nalezena."));
    }

    /**
     * Resolves a diagram, requiring that it belongs to the ontology in the path. The endpoints authorize the
     * slug only, so without this an owner of any ontology could reach another's diagram through their own
     * slug. 404 rather than 403, so the response does not confirm the id exists elsewhere.
     */
    private DiagramEntity requireDiagramOf(OntologyMetadataEntity ontology, String ontologySlug,
                                           Long diagramId) {
        return diagramRepository.findByIdAndOntologyMetadataId(diagramId, ontology.getId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Diagram " + diagramId + " nepatří do slovníku " + ontologySlug + "."));
    }

    /** Live concept detail by IRI; empty when the graph has no concepts yet. */
    private Map<String, ConceptDetailModel> liveConcepts(String graphName) {
        Model raw = jenaTDB2Repository.fetchGraph(graphName);
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Model processed = detailExtractor.applyOFNTransformations(raw);
        OntologyDetailModel detail = detailExtractor.extractOntologyDetail(processed);
        Map<String, ConceptDetailModel> byIri = new HashMap<>();
        if (detail.getConcepts() != null) {
            for (ConceptDetailModel c : detail.getConcepts()) {
                byIri.put(c.getIri(), c);
            }
        }
        return byIri;
    }

    /**
     * Concept types by IRI: the whole own graph plus the named foreign nodes. Without a type a foreign
     * class takes {@link #isCanvasMember}'s unknown branch and renders as deleted.
     */
    private Map<String, ConceptType> conceptTypes(List<ConceptMetadataEntity> own,
                                                  List<ConceptMetadataEntity> foreign) {
        Map<String, ConceptType> types = new HashMap<>();
        for (ConceptMetadataEntity c : own) {
            types.put(c.getConceptIri(), c.getConceptType());
        }
        for (ConceptMetadataEntity c : foreign) {
            types.putIfAbsent(c.getConceptIri(), c.getConceptType());
        }
        return types;
    }

    /** Slugs by IRI, own graph plus foreign nodes, so a foreign node can deep-link to detail. */
    private Map<String, String> conceptSlugs(List<ConceptMetadataEntity> own,
                                             List<ConceptMetadataEntity> foreign) {
        Map<String, String> slugs = new HashMap<>();
        for (ConceptMetadataEntity c : own) {
            slugs.put(c.getConceptIri(), c.getSlug());
        }
        for (ConceptMetadataEntity c : foreign) {
            slugs.putIfAbsent(c.getConceptIri(), c.getSlug());
        }
        return slugs;
    }

    /** PG rows for the foreign nodes in one query; empty for NKD IRIs, which have no local row. */
    private List<ConceptMetadataEntity> foreignMetadata(List<DiagramNodeEntity> nodes) {
        List<String> iris = foreignIris(nodes);
        return iris.isEmpty() ? List.of() : conceptMetadataRepository.findByConceptIriIn(iris);
    }
}
