package com.dia.ismdtoolbackend.diagram;

import com.dia.ismdtoolbackend.config.JpaAuditingConfig;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.PositionDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.ViewportDto;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.DiagramEdgeKind;
import com.dia.ismdtoolbackend.enums.DiagramNodeBacking;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.outbox.PostgresIntegrationTestBase;
import com.dia.ismdtoolbackend.repository.DiagramNodeRepository;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.impl.DiagramLayoutReconciler;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Save-time layout membership reconcile ({@code PUT …/layout}) on real Postgres: the incoming node set is
 * authoritative — a new IRI is inserted, a matching row is updated in place, an omitted persisted node is
 * removed from the canvas (concept untouched), and a removed parent nulls its children's {@code parentNodeId}.
 * Exercises {@link DiagramLayoutReconciler} directly (the pure membership logic), mirroring the two-step
 * flush protocol {@code DiagramServiceImpl.saveLayout} uses.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@EntityScan(basePackageClasses = DiagramEntity.class)
@EnableJpaRepositories(basePackageClasses = DiagramRepository.class)
@Import(JpaAuditingConfig.class)
class DiagramLayoutReconcilerTest extends PostgresIntegrationTestBase {

    private final DiagramLayoutReconciler reconciler = new DiagramLayoutReconciler(new DiagramMapper());

    @Autowired private DiagramRepository diagramRepository;
    @Autowired private DiagramNodeRepository nodeRepository;
    @Autowired private OntologyMetadataRepository ontologyRepository;
    @Autowired private EntityManager em;

    private DiagramEntity newDiagram(String slug) {
        OntologyMetadataEntity o = new OntologyMetadataEntity();
        o.setSlug(slug);
        o.setGraphName("https://x/" + slug);
        o.setUserId("u1");
        o.setIsPublished(false);
        ontologyRepository.save(o);

        DiagramEntity d = new DiagramEntity();
        d.setOntologyMetadata(o);
        d.setUserId("u1");
        return diagramRepository.save(d);
    }

    private DiagramNodeEntity seedNode(DiagramEntity diagram, String iri, double x, double y) {
        DiagramNodeEntity n = new DiagramNodeEntity();
        n.setBacking(DiagramNodeBacking.ISMD_CONCEPT);
        n.setConceptIri(iri);
        n.setPosX(x);
        n.setPosY(y);
        diagram.addNode(n);
        return n;
    }

    private DiagramLayoutDto.Node node(String iri, double x, double y) {
        return new DiagramLayoutDto.Node("iri:" + iri, new PositionDto(x, y), null, false);
    }

    private DiagramLayoutDto.Node node(String iri, double x, double y, String parentIri) {
        return new DiagramLayoutDto.Node("iri:" + iri, new PositionDto(x, y),
                parentIri != null ? "iri:" + parentIri : null, false);
    }

    /** Run the two-step reconcile the way the service does: reconcile → flush → finalize → save. */
    private DiagramEntity save(DiagramEntity diagram, DiagramLayoutDto layout) {
        var incoming = reconciler.reconcileNodes(diagram, layout);
        diagramRepository.saveAndFlush(diagram);       // new rows get identity
        reconciler.finalizeLayout(diagram, layout, incoming);
        diagram.touch();
        DiagramEntity saved = diagramRepository.saveAndFlush(diagram);
        em.clear();
        return saved;
    }

    @Test
    void addsNewIriNode_keepsMatching_removesOmitted() {
        DiagramEntity diagram = newDiagram("membership");
        seedNode(diagram, "https://x/pojem/keep", 0, 0);
        seedNode(diagram, "https://x/pojem/drop", 10, 10);
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        DiagramLayoutDto layout = new DiagramLayoutDto(
                new ViewportDto(1.0, 2.0, 0.9),
                List.of(
                        node("https://x/pojem/keep", 5, 5),        // matching → updated in place
                        node("https://x/pojem/new", 20, 20)),       // new IRI → inserted
                // note: 'drop' omitted → removed from canvas
                List.of());
        save(managed, layout);

        List<String> iris = nodeRepository.findByDiagramId(diagram.getId()).stream()
                .map(DiagramNodeEntity::getConceptIri).toList();
        assertThat(iris).containsExactlyInAnyOrder(
                "https://x/pojem/keep", "https://x/pojem/new");   // drop removed, new added
        DiagramNodeEntity keep = nodeRepository.findByDiagramIdAndConceptIri(
                diagram.getId(), "https://x/pojem/keep").orElseThrow();
        assertThat(keep.getPosX()).isEqualTo(5.0);                 // position updated in place
    }

    @Test
    void removingParentNode_nullsChildrenParent() {
        DiagramEntity diagram = newDiagram("parent-drop");
        seedNode(diagram, "https://x/pojem/parent", 0, 0);
        seedNode(diagram, "https://x/pojem/child", 1, 1);
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        // First save: establish the parent→child grouping.
        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(managed, new DiagramLayoutDto(null,
                List.of(node("https://x/pojem/parent", 0, 0),
                        node("https://x/pojem/child", 1, 1, "https://x/pojem/parent")),
                List.of()));

        DiagramNodeEntity child = nodeRepository.findByDiagramIdAndConceptIri(
                diagram.getId(), "https://x/pojem/child").orElseThrow();
        assertThat(child.getParentNodeId()).isNotNull();           // grouping established

        // Second save: omit the parent → it's removed, and the child's parentNodeId must be nulled.
        DiagramEntity managed2 = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(managed2, new DiagramLayoutDto(null,
                List.of(node("https://x/pojem/child", 1, 1)),      // parent omitted
                List.of()));

        List<DiagramNodeEntity> remaining = nodeRepository.findByDiagramId(diagram.getId());
        assertThat(remaining).extracting(DiagramNodeEntity::getConceptIri)
                .containsExactly("https://x/pojem/child");         // parent removed
        assertThat(remaining.get(0).getParentNodeId()).isNull();   // child detached, not dangling
    }

    @Test
    void persistsEdgeProjectionRows() {
        DiagramEntity diagram = newDiagram("edges");
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        DiagramLayoutDto layout = new DiagramLayoutDto(null,
                List.of(node("https://x/pojem/prop", 0, 0),
                        node("https://x/pojem/cls", 100, 0)),
                List.of(new DiagramLayoutDto.Edge(
                        "e-1", "iri:https://x/pojem/prop", "iri:https://x/pojem/cls",
                        DiagramEdgeKind.DOMAIN, null, null)));
        DiagramEntity saved = save(managed, layout);

        assertThat(diagramRepository.findById(saved.getId()).orElseThrow().getEdges()).hasSize(1);
    }
}
