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
import com.dia.ismdtoolbackend.exception.DiagramEditConflictException;
import com.dia.ismdtoolbackend.exception.DiagramNameConflictException;
import com.dia.ismdtoolbackend.exception.DiagramReadbackFailedException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The diagram layer's core service: fat read (layout ⋈ live content, overlays applied, edges projected) and
 * the Save-time layout + membership + overlay reconcile, which is the layer's only write. Pure PG — never
 * writes RDF. Materialize is delegated to {@link DiagramMaterializeService}. See
 * {@code docs/DIAGRAM_LAYER.md}.
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
     * Self-reference through the Spring proxy so the {@code @Transactional} commit/load steps are actually
     * proxied when called from the public methods. A direct {@code this.commitLayout(...)} would bypass the
     * proxy and run with NO transaction, silently undoing the write/read split.
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
    // TODO: never used, remove?
    public List<DiagramSummaryDto> listAll() {
        return diagramRepository.findAll().stream()
                .map(this::toSummary)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiagramSummaryDto> listForOntology(String ontologySlug) {
        OntologyMetadataEntity ontology = requireOntology(ontologySlug);
        return diagramRepository.findByOntologyMetadataIdOrderByIdAsc(ontology.getId()).stream()
                .map(this::toSummary)
                .toList();
    }

    private DiagramSummaryDto toSummary(DiagramEntity diagram) {
        OntologyMetadataEntity ontology = diagram.getOntologyMetadata();
        return new DiagramSummaryDto(
                diagram.getId(),
                diagram.getName(),
                ontology.getSlug(),
                ontology.getSlug(),
                ontology.getGraphName(),
                diagram.getNodes().size(),
                diagram.getUpdatedAt() != null ? diagram.getUpdatedAt().toString() : null);
    }

    /**
     * Create an empty canvas. Explicit, never implicit: a read no longer provisions a row, so a
     * non-owner opening someone else's ontology cannot bring one into existence.
     */
    @Override
    public DiagramDto createDiagram(String ontologySlug, String name) {
        DiagramSnapshot snapshot = self.commitNewDiagram(ontologySlug, name);
        return assemble(ontologySlug, snapshot, liveConcepts(snapshot.graphName()));
    }

    @Transactional
    public DiagramSnapshot commitNewDiagram(String ontologySlug, String name) {
        OntologyMetadataEntity ontology = requireOntology(ontologySlug);
        String resolved = name == null || name.isBlank() ? defaultName(ontology) : name.trim();
        // Pre-check so a taken name is a 409 naming the clash, not the generic constraint 400. The DB
        // constraint remains the real guarantee — this only improves the message on the common path.
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

    /** "Nový diagram", numbered when that is taken — a create with no name never fails on the name. */
    private String defaultName(OntologyMetadataEntity ontology) {
        String base = "Nový diagram";
        if (!diagramRepository.existsByOntologyMetadataIdAndName(ontology.getId(), base)) {
            return base;
        }
        for (int i = 2; i < 1000; i++) {
            String candidate = base + " " + i;
            if (!diagramRepository.existsByOntologyMetadataIdAndName(ontology.getId(), candidate)) {
                return candidate;
            }
        }
        return base + " " + java.util.UUID.randomUUID();
    }

    /** Delete one canvas. Its nodes, waypoints and staged edits go with it (DB cascade); concepts do not. */
    @Override
    @Transactional
    public void deleteDiagram(String ontologySlug, Long diagramId) {
        OntologyMetadataEntity ontology = requireOntology(ontologySlug);
        diagramRepository.delete(requireDiagramOf(ontology, ontologySlug, diagramId));
    }

    /**
     * No {@code @Transactional} on the public method — the Fuseki read must NOT run inside a PG transaction.
     * {@link #loadForRead} does the PG work in its own short transaction and returns a detached snapshot;
     * the graph fetch then happens with no connection held.
     */
    @Override
    public DiagramDto getDiagram(String ontologySlug, Long diagramId) {
        DiagramSnapshot snapshot = self.loadForRead(ontologySlug, diagramId);
        // A read has nothing committed to lose, so a Fuseki failure surfaces as its own error, not a readback.
        return assemble(ontologySlug, snapshot, liveConcepts(snapshot.graphName()));
    }

    @Transactional(readOnly = true)
    public DiagramSnapshot loadForRead(String ontologySlug, Long diagramId) {
        OntologyMetadataEntity ontology = requireOntology(ontologySlug);
        return snapshot(requireDiagramOf(ontology, ontologySlug, diagramId), ontology);
    }

    /**
     * Write then read: the PG write commits in {@link #commitLayout}, and only then is the graph fetched.
     *
     * <p>The two must not share a transaction. The fetch is an HTTP call to Fuseki behind a semaphore whose
     * acquire alone can take 30s; holding a Hikari connection (pool of 20) across it lets a slow Fuseki
     * exhaust the pool and stall unrelated endpoints. It would also roll back a perfectly good layout write
     * because a *read* failed — and this layer never writes RDF, so there is no dual-write to keep atomic.
     *
     * <p>Ordering is write-first, not read-first: the 409 check is the common failure here (two editors on
     * one canvas), and read-first would pay a full graph fetch on every stale save just to discard it, while
     * widening the window between the version check and the commit. The cost is that a fetch failure now
     * arrives after a durable write — reported as {@link DiagramReadbackFailedException} so the client is
     * told the save survived and must reload rather than retry into a spurious 409.
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
        // Flush so newly-inserted nodes receive their identity before parentId references resolve
        // (a child may point at a just-added parent).
        diagramRepository.saveAndFlush(diagram);
        layoutReconciler.finalizeLayout(diagram, layout, incoming);
        diagram.touch();
        // saveAndFlush (not save): the snapshot copies @Version for the response and the client echoes it on
        // its NEXT save, so the value must be the post-increment one. The flush above makes this hold today
        // even with a plain save; flushing here keeps it true independently of that.
        diagramRepository.saveAndFlush(diagram);

        return snapshot(diagram, ontology);
    }

    /** Fetch live content for an ALREADY-COMMITTED write; a Fuseki failure here is a readback, not a rollback. */
    private Map<String, ConceptDetailModel> readbackConcepts(DiagramSnapshot snapshot) {
        try {
            return liveConcepts(snapshot.graphName());
        } catch (RuntimeException e) {
            throw new DiagramReadbackFailedException(snapshot.version(), e);
        }
    }

    /**
     * The optimistic lock, enforced in the service rather than by JPA. {@code @Version} alone cannot catch
     * this: {@code saveLayout} loads the diagram fresh inside its own transaction, so Hibernate compares the
     * just-read version against itself and always wins. The client's version — the one it rendered from —
     * is the only value that carries the "has anyone saved since?" signal.
     *
     * <p>This matters because membership is a full replace: a stale save would silently delete nodes another
     * editor added, taking their staged overlays with them.
     *
     * <p>A null version is accepted only when the diagram has no saved state yet (version 0, just
     * provisioned by this very call) — the first save of an empty canvas has nothing to conflict with.
     */
    private void requireCurrentVersion(DiagramEntity diagram, Long clientVersion) {
        Long stored = diagram.getVersion();
        if (clientVersion == null && (stored == null || stored == 0L) && diagram.getNodes().isEmpty()) {
            return;
        }
        if (!java.util.Objects.equals(clientVersion, stored)) {
            log.warn("Rejected stale diagram save for {}: client version {}, stored {}",
                    diagram.getOntologyMetadata().getSlug(), clientVersion, stored);
            throw new DiagramVersionConflictException(
                    "Diagram byl mezitím uložen jiným editorem; načtěte jej znovu a uložte změny znovu.");
        }
    }

    /** A layout save carried a version behind the stored one — another editor saved first (409). */
    public static class DiagramVersionConflictException extends RuntimeException {
        public DiagramVersionConflictException(String message) {
            super(message);
        }
    }

    // Not transactional: each staged change materializes in its OWN transaction (REQUIRES_NEW) so a
    // failure can't roll back earlier successes — the per-change partial-ok guarantee.
    @Override
    public MaterializeResultDto materialize(String ontologySlug, Long diagramId,
                                            ConflictResolution onConflict) {
        // Conflict detection and any resolution commit FIRST, in their own transaction, before the
        // per-change loop opens any of its own. Detecting inside the loop would already have written RDF
        // for the changes applied ahead of the collision.
        Long ontologyId = self.resolveConflicts(ontologySlug, diagramId, onConflict);
        return materializeService.materialize(diagramId, ontologyId);
    }

    /**
     * Refuse, or clear, edits this diagram stages on concepts a sibling diagram also stages.
     *
     * <p>Both diagrams belong to one ontology and the caller has already been authorized against it, so
     * discarding on the sibling is permitted — but the sibling rows are re-read through the ontology
     * scope, never trusted from the request.
     *
     * @return the ontology id, resolved here so the caller needs no second lookup
     */
    @Transactional
    public Long resolveConflicts(String ontologySlug, Long diagramId, ConflictResolution onConflict) {
        OntologyMetadataEntity ontology = requireOntology(ontologySlug);
        requireDiagramOf(ontology, ontologySlug, diagramId);

        List<String> staged = pendingEditRepository.findStagedConceptIris(diagramId);
        if (staged.isEmpty()) {
            return ontology.getId();
        }

        List<DiagramPendingEditEntity> conflicting =
                pendingEditRepository.findConflicting(ontology.getId(), diagramId, staged);
        if (conflicting.isEmpty()) {
            return ontology.getId();
        }

        List<String> conflictedIris = conflicting.stream()
                .map(DiagramPendingEditEntity::getConceptIri)
                .distinct()
                .toList();

        if (onConflict == ConflictResolution.DISCARD_THEIRS) {
            int removed = pendingEditRepository.deleteConflictingOnSiblings(
                    ontology.getId(), diagramId, conflictedIris);
            log.info("Materialize on diagram {}: discarded {} conflicting staged edit(s) on sibling diagrams",
                    diagramId, removed);
            return ontology.getId();
        }
        if (onConflict == ConflictResolution.DISCARD_MINE) {
            int removed = pendingEditRepository.deleteOnDiagram(diagramId, conflictedIris);
            log.info("Materialize on diagram {}: discarded {} of its own conflicting staged edit(s)",
                    diagramId, removed);
            return ontology.getId();
        }

        throw new DiagramEditConflictException(buildConflictReport(diagramId, conflicting));
    }

    /** Pair each conflicted concept's own staged edit with the competing ones, for the 409 body. */
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

    /** Best-effort display label for the conflict report; PG metadata only, no graph fetch. */
    private Map<String, String> conceptLabel(String conceptIri) {
        return conceptMetadataRepository.findByConceptIri(conceptIri)
                .map(ConceptMetadataEntity::getConceptName)
                .map(name -> Map.of("cs", name))
                .orElse(null);
    }

    // ---- snapshot -------------------------------------------------------------------------------

    /**
     * Everything the response needs from PG, detached from the persistence context. Built INSIDE the
     * transaction so the subsequent Fuseki fetch — and the assembly that follows it — touch no lazy
     * association and hold no connection. {@code nodes} preserves the diagram's node order.
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
            /* Staged edits by concept IRI. Independent of `nodes` — either may exist without the other. */
            Map<String, DiagramPendingEdit> overlays
    ) {

        /** The snapshot node for a concept IRI, or null when the diagram has no such node. */
        DiagramNodeEntity node(String conceptIri) {
            return nodes.stream()
                    .filter(n -> conceptIri.equals(n.getConceptIri()))
                    .findFirst()
                    .orElse(null);
        }
    }

    /** Capture the diagram's PG state; must be called inside the transaction that read/wrote it. */
    private DiagramSnapshot snapshot(DiagramEntity diagram, OntologyMetadataEntity ontology) {
        String graphName = ontology.getGraphName();
        List<DiagramNodeEntity> nodes = new ArrayList<>(diagram.getNodes());
        Map<Long, String> nodeIriByRowId = new HashMap<>();
        for (DiagramNodeEntity n : nodes) {
            if (n.getId() != null) {
                nodeIriByRowId.put(n.getId(), n.getConceptIri());
            }
        }
        return new DiagramSnapshot(diagram.getId(), diagram.getName(), graphName, diagram.getVersion(),
                mapper.toViewport(diagram), nodes,
                conceptTypes(graphName), conceptSlugs(graphName), nodeIriByRowId,
                edgeWaypoints(diagram), overlays(diagram));
    }

    /** Staged edits by concept IRI, read inside the transaction so assembly works on a detached snapshot. */
    private Map<String, DiagramPendingEdit> overlays(DiagramEntity diagram) {
        if (diagram.getId() == null) {
            return Map.of();   // not yet persisted, so nothing can be staged against it
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

    /**
     * Waypoints by projected edge id, read inside the transaction so assembly can attach them without
     * touching a lazy association after the snapshot detaches. A row whose key no longer projects (an
     * endpoint was repointed) simply finds no match and is ignored; the next Save full-replaces the set.
     */
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

    /** Join layout rows to live content, apply overlays, project edges and property rows. */
    private DiagramDto assemble(String ontologySlug, DiagramSnapshot snapshot,
                                Map<String, ConceptDetailModel> live) {
        EdgeProjector projector =
                new EdgeProjector(mapper, snapshot.edgeWaypoints(), snapshot.overlays());
        Map<String, List<DiagramDto.PropertyRow>> rows =
                projector.propertyRows(snapshot.nodes(), live, snapshot.types(), snapshot.slugs());

        // nodes[] is the canvas: classes only. A relationship renders as an edge and a property as a
        // row inside its class, so neither appears here.
        List<DiagramDto.Node> nodes = new ArrayList<>();
        for (DiagramNodeEntity node : snapshot.nodes()) {
            if (!isCanvasMember(snapshot, node.getConceptIri())) {
                continue;
            }
            nodes.add(toNode(snapshot, node, live.get(node.getConceptIri()),
                    rows.getOrDefault(node.getConceptIri(), List.of())));
        }

        List<DiagramDto.Edge> edges =
                projector.project(snapshot.nodes(), live, snapshot.types(), snapshot.slugs());

        return new DiagramDto(snapshot.diagramId(), snapshot.name(), ontologySlug, snapshot.version(),
                snapshot.viewport(), nodes, edges, pendingEdits(snapshot, live));
    }

    /** Canvas membership: classes only. An unknown type is kept — a stale row is still on the canvas. */
    private boolean isCanvasMember(DiagramSnapshot snapshot, String conceptIri) {
        ConceptType type = snapshot.types().get(conceptIri);
        return type == null || type == ConceptType.TRIDA || type == ConceptType.KONCEPT;
    }

    /**
     * Every staged edit on the ontology — what Převzít will apply. Not filtered by what the canvas
     * renders, so an edit keeps one stable home across membership changes.
     */
    private List<DiagramDto.PendingEditEntry> pendingEdits(DiagramSnapshot snapshot,
                                                           Map<String, ConceptDetailModel> live) {
        List<DiagramDto.PendingEditEntry> entries = new ArrayList<>();
        for (Map.Entry<String, DiagramPendingEdit> staged : snapshot.overlays().entrySet()) {
            String iri = staged.getKey();
            ConceptDetailModel detail = live.get(iri);
            entries.add(new DiagramDto.PendingEditEntry(
                    iri,
                    snapshot.types().get(iri),
                    snapshot.slugs().get(iri),
                    detail != null ? detail.getName() : null,
                    detail == null,
                    staged.getValue()));
        }
        entries.sort(Comparator.comparing(DiagramDto.PendingEditEntry::iri));
        return entries;
    }

    /** Build one render-ready node; type/slug come from the snapshot, never a fresh PG read. */
    private DiagramDto.Node toNode(DiagramSnapshot snapshot, DiagramNodeEntity node,
                                   ConceptDetailModel detail, List<DiagramDto.PropertyRow> properties) {
        String conceptIri = node.getConceptIri();
        ConceptType type = snapshot.types().get(conceptIri);
        String slug = snapshot.slugs().get(conceptIri);
        Map<String, String> label = detail != null ? detail.getName() : null;
        DiagramDto.NodeData data = mapper.toNodeData(node, type, slug, label, detail, properties,
                snapshot.overlays().get(conceptIri));
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
     * Resolve a diagram, requiring that it belongs to the ontology named in the path.
     *
     * <p>The endpoints authorize the ontology <em>slug</em>; the diagram id travels beside it
     * unconstrained, so without this an owner of ANY ontology could reach another ontology's diagram
     * through their own slug — the same shape as the B2 IDOR, where the gate checked the slug while the
     * real target rode elsewhere in the request.
     *
     * <p>Filtered in ONE query rather than load-then-compare, and reported as 404 rather than 403: the
     * response must not confirm that a diagram id exists under some other ontology.
     */
    private DiagramEntity requireDiagramOf(OntologyMetadataEntity ontology, String ontologySlug,
                                           Long diagramId) {
        return diagramRepository.findByIdAndOntologyMetadataId(diagramId, ontology.getId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Diagram " + diagramId + " nepatří do slovníku " + ontologySlug + "."));
    }

    /** Live concept detail keyed by IRI; empty when the ontology graph has no concepts yet. */
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

    private Map<String, ConceptType> conceptTypes(String graphName) {
        Map<String, ConceptType> types = new HashMap<>();
        for (ConceptMetadataEntity c : conceptMetadataRepository.findByGraphName(graphName)) {
            types.put(c.getConceptIri(), c.getConceptType());
        }
        return types;
    }

    private Map<String, String> conceptSlugs(String graphName) {
        Map<String, String> slugs = new HashMap<>();
        for (ConceptMetadataEntity c : conceptMetadataRepository.findByGraphName(graphName)) {
            slugs.put(c.getConceptIri(), c.getSlug());
        }
        return slugs;
    }
}
