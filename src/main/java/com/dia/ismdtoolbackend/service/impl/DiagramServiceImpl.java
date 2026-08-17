package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramSummaryDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.MaterializeResultDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.NodeOverlayDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.ViewportDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEdgeEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.exception.DiagramReadbackFailedException;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.DiagramService;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The diagram layer's core service: fat read (layout ⋈ live content, overlays applied, edges projected),
 * the Save-time layout + membership reconcile, and overlay staging. Pure PG — never writes RDF. Materialize
 * is delegated to {@link DiagramMaterializeService}. See {@code docs/DIAGRAM_LAYER.md}.
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
                              DiagramMapper mapper,
                              @Lazy DiagramServiceImpl self) {
        this.diagramRepository = diagramRepository;
        this.ontologyMetadataRepository = ontologyMetadataRepository;
        this.conceptMetadataRepository = conceptMetadataRepository;
        this.detailExtractor = detailExtractor;
        this.jenaTDB2Repository = jenaTDB2Repository;
        this.materializeService = materializeService;
        this.layoutReconciler = layoutReconciler;
        this.mapper = mapper;
        this.self = self;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiagramSummaryDto> listAll() {
        return diagramRepository.findAll().stream()
                .map(this::toSummary)
                .toList();
    }

    private DiagramSummaryDto toSummary(DiagramEntity diagram) {
        OntologyMetadataEntity ontology = diagram.getOntologyMetadata();
        return new DiagramSummaryDto(
                ontology.getSlug(),
                ontology.getSlug(),
                ontology.getGraphName(),
                diagram.getNodes().size(),
                diagram.getUpdatedAt() != null ? diagram.getUpdatedAt().toString() : null);
    }

    /**
     * No {@code @Transactional} on the public method — the Fuseki read must NOT run inside a PG transaction.
     * {@link #loadForRead} does the PG work in its own short transaction and returns a detached snapshot;
     * the graph fetch then happens with no connection held.
     */
    @Override
    public DiagramDto getDiagram(String ontologySlug) {
        DiagramSnapshot snapshot = self.loadForRead(ontologySlug);
        // A read has nothing committed to lose, so a Fuseki failure surfaces as its own error, not a readback.
        return assemble(ontologySlug, snapshot, liveConcepts(snapshot.graphName()));
    }

    @Transactional(readOnly = true)
    public DiagramSnapshot loadForRead(String ontologySlug) {
        OntologyMetadataEntity ontology = requireOntology(ontologySlug);
        DiagramEntity diagram = diagramRepository.findByOntologyMetadataId(ontology.getId())
                .orElseGet(() -> transientDiagram(ontology));
        return snapshot(diagram, ontology);
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
    public DiagramDto saveLayout(String ontologySlug, DiagramLayoutDto layout) {
        DiagramSnapshot snapshot = self.commitLayout(ontologySlug, layout);
        return assemble(ontologySlug, snapshot, readbackConcepts(snapshot));
    }

    @Transactional
    public DiagramSnapshot commitLayout(String ontologySlug, DiagramLayoutDto layout) {
        OntologyMetadataEntity ontology = requireOntology(ontologySlug);
        DiagramEntity diagram = getOrCreateDiagram(ontology);
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

    /** Same write-then-read split as {@link #saveLayout}; see that method for why the two are separated. */
    @Override
    public DiagramDto.Node stageOverlay(String ontologySlug, String nodeId, NodeOverlayDto overlay) {
        DiagramSnapshot snapshot = self.commitOverlay(ontologySlug, nodeId, overlay);
        String conceptIri = mapper.conceptIriFromNodeId(nodeId);
        Map<String, ConceptDetailModel> live = readbackConcepts(snapshot, conceptIri);
        return toNode(snapshot, snapshot.node(conceptIri), live.get(conceptIri))
                .withVersion(snapshot.version());
    }

    @Transactional
    public DiagramSnapshot commitOverlay(String ontologySlug, String nodeId, NodeOverlayDto overlay) {
        OntologyMetadataEntity ontology = requireOntology(ontologySlug);
        DiagramEntity diagram = getOrCreateDiagram(ontology);

        String conceptIri = mapper.conceptIriFromNodeId(nodeId);
        DiagramNodeEntity node = diagram.getNodes().stream()
                .filter(n -> conceptIri.equals(n.getConceptIri()))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException(
                        "Uzel diagramu s id " + nodeId + " nebyl nalezen."));

        DiagramPendingEdit edit = mapper.toPendingEdit(overlay);
        if (edit == null) {
            node.setPendingEdit(null);
        } else {
            requireOwnGraph(ontology.getGraphName(), edit);
            edit.setBaseUpdatedAt(baseUpdatedAt(node.getConceptIri()));
            node.setPendingEdit(edit);
        }
        diagram.touch();
        // saveAndFlush (not save): the snapshot copies @Version for the response, so it must be the
        // post-increment value — the transaction commits before anything reads it back.
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

    /** As {@link #readbackConcepts}, narrowed to the one concept the lean stage response renders. */
    private Map<String, ConceptDetailModel> readbackConcepts(DiagramSnapshot snapshot, String conceptIri) {
        try {
            ConceptDetailModel detail = liveConcept(snapshot.graphName(), conceptIri);
            return detail == null ? Map.of() : Map.of(conceptIri, detail);
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

    /**
     * Op 6's {@code addBroaderOn} / {@code broader} name concepts that need never be on the canvas, so they
     * are the one overlay input that can reach {@code editConcept}/{@code deleteConcept} on its own. Reject a
     * foreign target at stage time rather than letting it sit staged until Převzít. The applier re-asserts
     * this — an overlay staged before this check existed is still refused there.
     */
    private void requireOwnGraph(String diagramGraphName, DiagramPendingEdit edit) {
        DiagramPendingEdit.ConvertToHierarchy marker = edit.getConvertToHierarchy();
        if (marker == null) {
            return;
        }
        requireOwnGraph(diagramGraphName, marker.getAddBroaderOn());
        requireOwnGraph(diagramGraphName, marker.getBroader());
    }

    private void requireOwnGraph(String diagramGraphName, String conceptIri) {
        if (conceptIri == null) {
            return;
        }
        // An unknown IRI is left to materialize, which reports it as a per-change VALIDATION failure;
        // only a concept that exists in ANOTHER ontology's graph is a cross-tenant reach.
        String graphName = conceptMetadataRepository.findByConceptIri(conceptIri)
                .map(ConceptMetadataEntity::getGraphName)
                .orElse(null);
        if (graphName != null && !java.util.Objects.equals(diagramGraphName, graphName)) {
            log.warn("Rejected overlay referencing concept {} (graph {}) on a diagram for graph {}",
                    conceptIri, graphName, diagramGraphName);
            throw new com.dia.ismdtoolbackend.exception.ConceptValidationException(
                    "Pojem " + conceptIri + " nepatří do slovníku tohoto diagramu.");
        }
    }

    // Not transactional: each staged change materializes in its OWN transaction (REQUIRES_NEW) so a
    // failure can't roll back earlier successes — the per-change partial-ok guarantee.
    @Override
    public MaterializeResultDto materialize(String ontologySlug) {
        OntologyMetadataEntity ontology = requireOntology(ontologySlug);
        DiagramEntity diagram = getOrCreateDiagram(ontology);
        return materializeService.materialize(diagram.getId());
    }

    // ---- snapshot -------------------------------------------------------------------------------

    /**
     * Everything the response needs from PG, detached from the persistence context. Built INSIDE the
     * transaction so the subsequent Fuseki fetch — and the assembly that follows it — touch no lazy
     * association and hold no connection. {@code nodes} preserves the diagram's node order.
     */
    public record DiagramSnapshot(
            String graphName,
            Long version,
            ViewportDto viewport,
            List<DiagramNodeEntity> nodes,
            Map<String, ConceptType> types,
            Map<String, String> slugs,
            Map<Long, String> nodeIriByRowId,
            Map<String, EdgePresentation> edgePresentation
    ) {

        /** The snapshot node for a concept IRI, or null when the diagram has no such node. */
        DiagramNodeEntity node(String conceptIri) {
            return nodes.stream()
                    .filter(n -> conceptIri.equals(n.getConceptIri()))
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * The presentation-only state of a persisted edge row, keyed by the projected edge id. Edge existence
     * and kind stay a projection of {@code live ⊕ overlay}; only what RDF cannot express — which handle each
     * end attaches to and how the link is routed — is read back from PG.
     */
    public record EdgePresentation(String sourceHandle, String targetHandle, List<EdgeWaypoint> segments) {
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
        return new DiagramSnapshot(graphName, diagram.getVersion(), mapper.toViewport(diagram), nodes,
                conceptTypes(graphName), conceptSlugs(graphName), nodeIriByRowId,
                edgePresentation(diagram));
    }

    /**
     * Resolve each persisted edge row to {@code (kind, sourceIri, targetIri)} — the same key
     * {@link EdgeProjector} derives — so assembly can attach handles without touching a lazy association
     * after the snapshot detaches. A row whose key no longer projects (an endpoint was repointed) simply
     * finds no match and is ignored; the next Save full-replaces it.
     */
    private Map<String, EdgePresentation> edgePresentation(DiagramEntity diagram) {
        Map<String, EdgePresentation> byKey = new HashMap<>();
        for (DiagramEdgeEntity edge : diagram.getEdges()) {
            if (edge.getSourceNode() == null || edge.getTargetNode() == null || edge.getEdgeKind() == null) {
                continue;
            }
            byKey.put(
                    EdgeProjector.projectedEdgeId(edge.getEdgeKind(),
                            edge.getSourceNode().getConceptIri(),
                            edge.getTargetNode().getConceptIri()),
                    new EdgePresentation(edge.getSourceHandle(), edge.getTargetHandle(),
                            edge.getSegments()));
        }
        return byKey;
    }

    // ---- assembly -------------------------------------------------------------------------------

    /** Join layout rows to live content, apply overlays, project edges. */
    private DiagramDto assemble(String ontologySlug, DiagramSnapshot snapshot,
                                Map<String, ConceptDetailModel> live) {
        List<DiagramDto.Node> nodes = new ArrayList<>();
        for (DiagramNodeEntity node : snapshot.nodes()) {
            nodes.add(toNode(snapshot, node, live.get(node.getConceptIri())));
        }

        List<DiagramDto.Edge> edges = new EdgeProjector(mapper, snapshot.edgePresentation())
                .project(snapshot.nodes(), live);
        int pendingChangeCount = (int) snapshot.nodes().stream()
                .filter(n -> n.getPendingEdit() != null)
                .count();

        return new DiagramDto(ontologySlug, snapshot.version(), snapshot.viewport(), nodes, edges,
                pendingChangeCount);
    }

    /** Build one render-ready node; type/slug come from the snapshot, never a fresh PG read. */
    private DiagramDto.Node toNode(DiagramSnapshot snapshot, DiagramNodeEntity node,
                                   ConceptDetailModel detail) {
        String conceptIri = node.getConceptIri();
        ConceptType type = snapshot.types().get(conceptIri);
        String slug = snapshot.slugs().get(conceptIri);
        Map<String, String> label = detail != null ? detail.getName() : null;
        DiagramDto.NodeData data = mapper.toNodeData(node, type, slug, label, detail);
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

    private DiagramEntity getOrCreateDiagram(OntologyMetadataEntity ontology) {
        return diagramRepository.findByOntologyMetadataId(ontology.getId())
                .orElseGet(() -> provisionDiagram(ontology));
    }

    /**
     * Create the diagram row for an ontology. Ownership is not stored here — it is the ontology's, read
     * through {@code ontologyMetadata}.
     */
    private DiagramEntity provisionDiagram(OntologyMetadataEntity ontology) {
        DiagramEntity diagram = new DiagramEntity();
        diagram.setOntologyMetadata(ontology);
        try {
            return diagramRepository.saveAndFlush(diagram);
        } catch (DataIntegrityViolationException e) {
            // Concurrent provision won the uq_diagrams_ontology_metadata race; adopt its row.
            return diagramRepository.findByOntologyMetadataId(ontology.getId()).orElseThrow(() -> e);
        }
    }

    /**
     * Unsaved stand-in for an ontology with no diagram yet — renders as an empty canvas without
     * creating a row. Carries only what {@link #assemble} reads: the ontology, an empty node list
     * and a null viewport.
     */
    private DiagramEntity transientDiagram(OntologyMetadataEntity ontology) {
        DiagramEntity diagram = new DiagramEntity();
        diagram.setOntologyMetadata(ontology);
        return diagram;
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

    /** Live detail for a single concept, or null when the graph is empty / the concept is gone (stale). */
    private ConceptDetailModel liveConcept(String graphName, String conceptIri) {
        Model raw = jenaTDB2Repository.fetchGraph(graphName);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Model processed = detailExtractor.applyOFNTransformations(raw);
        return detailExtractor.extractConceptDetail(processed, conceptIri);
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

    private java.time.LocalDateTime baseUpdatedAt(String conceptIri) {
        return conceptMetadataRepository.findByConceptIri(conceptIri)
                .map(ConceptMetadataEntity::getUpdatedAt)
                .orElse(null);
    }
}
