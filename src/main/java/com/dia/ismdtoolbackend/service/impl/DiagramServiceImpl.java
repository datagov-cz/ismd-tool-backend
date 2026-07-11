package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.MaterializeResultDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.NodeOverlayDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.DiagramService;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.security.SecurityUtils;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
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
@RequiredArgsConstructor
public class DiagramServiceImpl implements DiagramService {

    private final DiagramRepository diagramRepository;
    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final OntologyDetailExtractor detailExtractor;
    private final com.dia.ismdtoolbackend.repository.JenaTDB2Repository jenaTDB2Repository;
    private final DiagramMaterializeService materializeService;
    private final DiagramLayoutReconciler layoutReconciler;
    private final DiagramMapper mapper;

    // Not readOnly: first open lazy-provisions the diagram row (getOrCreateDiagram INSERTs).
    @Override
    @Transactional
    public DiagramDto getDiagram(String ontologySlug) {
        OntologyMetadataEntity ontology = requireOntology(ontologySlug);
        DiagramEntity diagram = getOrCreateDiagram(ontology);
        return assemble(ontologySlug, diagram, liveConcepts(ontology.getGraphName()));
    }

    @Override
    @Transactional
    public DiagramDto saveLayout(String ontologySlug, DiagramLayoutDto layout) {
        OntologyMetadataEntity ontology = requireOntology(ontologySlug);
        DiagramEntity diagram = getOrCreateDiagram(ontology);

        Map<String, DiagramNodeEntity> incoming = layoutReconciler.reconcileNodes(diagram, layout);
        // Flush so newly-inserted nodes receive their identity before parentId references resolve
        // (a child may point at a just-added parent).
        diagramRepository.saveAndFlush(diagram);
        layoutReconciler.finalizeLayout(diagram, layout, incoming);
        diagram.touch();
        diagramRepository.save(diagram);

        return assemble(ontologySlug, diagram, liveConcepts(ontology.getGraphName()));
    }

    @Override
    @Transactional
    public DiagramDto.Node stageOverlay(String ontologySlug, String nodeId, NodeOverlayDto overlay) {
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
            edit.setBaseUpdatedAt(baseUpdatedAt(node.getConceptIri()));
            node.setPendingEdit(edit);
        }
        diagram.touch();
        diagramRepository.save(diagram);

        ConceptDetailModel detail = liveConcept(ontology.getGraphName(), node.getConceptIri());
        return toNode(diagram, node, detail);
    }

    // Not transactional: each staged change materializes in its OWN transaction (REQUIRES_NEW) so a
    // failure can't roll back earlier successes — the per-change partial-ok guarantee.
    @Override
    public MaterializeResultDto materialize(String ontologySlug) {
        OntologyMetadataEntity ontology = requireOntology(ontologySlug);
        DiagramEntity diagram = getOrCreateDiagram(ontology);
        return materializeService.materialize(diagram.getId());
    }

    // ---- assembly -------------------------------------------------------------------------------

    /** Join layout rows to live content, apply overlays, project edges. */
    private DiagramDto assemble(String ontologySlug, DiagramEntity diagram, Map<String, ConceptDetailModel> live) {
        Map<String, ConceptType> types = conceptTypes(diagram.getOntologyMetadata().getGraphName());
        Map<String, String> slugs = conceptSlugs(diagram.getOntologyMetadata().getGraphName());

        List<DiagramDto.Node> nodes = new ArrayList<>();
        for (DiagramNodeEntity node : diagram.getNodes()) {
            nodes.add(toNode(diagram, node,
                    live.get(node.getConceptIri()),
                    types.get(node.getConceptIri()),
                    slugs.get(node.getConceptIri())));
        }

        List<DiagramDto.Edge> edges = new EdgeProjector(mapper).project(diagram.getNodes(), live);
        int pendingChangeCount = (int) diagram.getNodes().stream()
                .filter(n -> n.getPendingEdit() != null)
                .count();

        return new DiagramDto(ontologySlug, mapper.toViewport(diagram), nodes, edges, pendingChangeCount);
    }

    /** Build one render-ready node; looks up its type/slug from PG (used by the lean stage response). */
    private DiagramDto.Node toNode(DiagramEntity diagram, DiagramNodeEntity node, ConceptDetailModel detail) {
        ConceptMetadataEntity meta =
                conceptMetadataRepository.findByConceptIri(node.getConceptIri()).orElse(null);
        ConceptType type = meta != null ? meta.getConceptType() : null;
        String slug = meta != null ? meta.getSlug() : null;
        return toNode(diagram, node, detail, type, slug);
    }

    private DiagramDto.Node toNode(DiagramEntity diagram, DiagramNodeEntity node,
                                   ConceptDetailModel detail, ConceptType type, String slug) {
        Map<String, String> label = detail != null ? detail.getName() : null;
        DiagramDto.NodeData data = mapper.toNodeData(node, type, slug, label, detail);
        String parentId = node.getParentNodeId() != null
                ? parentWireId(diagram, node.getParentNodeId())
                : null;
        return new DiagramDto.Node(
                mapper.nodeId(node.getConceptIri()),
                mapper.nodeType(type),
                mapper.toPosition(node),
                parentId,
                data);
    }

    private String parentWireId(DiagramEntity diagram, Long parentNodeRowId) {
        return diagram.getNodes().stream()
                .filter(n -> parentNodeRowId.equals(n.getId()))
                .findFirst()
                .map(n -> mapper.nodeId(n.getConceptIri()))
                .orElse(null);
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

    private DiagramEntity provisionDiagram(OntologyMetadataEntity ontology) {
        DiagramEntity diagram = new DiagramEntity();
        diagram.setOntologyMetadata(ontology);
        diagram.setUserId(SecurityUtils.getCurrentUser().getUserId());
        return diagramRepository.save(diagram);
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
