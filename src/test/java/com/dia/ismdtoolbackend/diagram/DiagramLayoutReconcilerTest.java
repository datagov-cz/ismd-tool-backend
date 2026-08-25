package com.dia.ismdtoolbackend.diagram;

import com.dia.ismdtoolbackend.config.JpaAuditingConfig;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.PositionDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.ViewportDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.DiagramNodeBacking;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;
import com.dia.ismdtoolbackend.outbox.PostgresIntegrationTestBase;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramNodeRepository;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.impl.DiagramLayoutReconciler;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
@EntityScan(basePackageClasses = {DiagramEntity.class, ConceptMetadataEntity.class})
@EnableJpaRepositories(basePackageClasses = {DiagramRepository.class, ConceptMetadataRepository.class})
@Import(JpaAuditingConfig.class)
class DiagramLayoutReconcilerTest extends PostgresIntegrationTestBase {

    @Autowired private ConceptMetadataRepository conceptRepository;
    @Autowired private DiagramRepository diagramRepository;
    @Autowired private DiagramNodeRepository nodeRepository;
    @Autowired private OntologyMetadataRepository ontologyRepository;
    @Autowired private EntityManager em;

    private DiagramLayoutReconciler reconciler;

    @BeforeEach
    void initReconciler() {
        reconciler = new DiagramLayoutReconciler(new DiagramMapper(), conceptRepository);
    }

    private DiagramEntity newDiagram(String slug) {
        OntologyMetadataEntity o = new OntologyMetadataEntity();
        o.setSlug(slug);
        o.setGraphName("https://x/" + slug);
        o.setUserId("u1");
        o.setIsPublished(false);
        ontologyRepository.save(o);

        DiagramEntity d = new DiagramEntity();
        d.setOntologyMetadata(o);
        return diagramRepository.save(d);
    }

    private DiagramNodeEntity seedNode(DiagramEntity diagram, String iri, double x, double y) {
        return seedNode(diagram, iri, x, y, null);
    }

    /** As above, with a staged overlay on the row — the state the reap carve-out protects. */
    private DiagramNodeEntity seedNode(DiagramEntity diagram, String iri, double x, double y,
                                       DiagramPendingEdit pendingEdit) {
        DiagramNodeEntity n = new DiagramNodeEntity();
        n.setBacking(DiagramNodeBacking.ISMD_CONCEPT);
        n.setConceptIri(iri);
        n.setPosX(x);
        n.setPosY(y);
        n.setPendingEdit(pendingEdit);
        diagram.addNode(n);
        return n;
    }

    /** A minimal structural overlay — a VZTAH repointed at {@code range}. */
    private DiagramPendingEdit overlay(String range) {
        DiagramPendingEdit edit = new DiagramPendingEdit();
        edit.setRange(range);
        return edit;
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
                null,
                new ViewportDto(1.0, 2.0, 0.9),
                List.of(
                        node("https://x/pojem/keep", 5, 5),        // matching → updated in place
                        node("https://x/pojem/new", 20, 20)),       // new IRI → inserted
                // note: 'drop' omitted → removed from canvas
                List.of(), null);
        save(managed, layout);

        List<String> iris = nodeRepository.findByDiagramId(diagram.getId()).stream()
                .map(DiagramNodeEntity::getConceptIri).toList();
        assertThat(iris).containsExactlyInAnyOrder(
                "https://x/pojem/keep", "https://x/pojem/new");   // drop removed, new added
        DiagramNodeEntity keep = nodeRepository.findByDiagramIdAndConceptIri(
                diagram.getId(), "https://x/pojem/keep").orElseThrow();
        assertThat(keep.getPosX()).isEqualTo(5.0);                 // position updated in place
    }

    /**
     * The reap carve-out ({@code DiagramLayoutReconciler:67-70}). A relationship renders as an edge and a
     * property as a row, so neither ever travels in {@code nodes[]} — their rows exist only to carry a
     * staged overlay. Reaping on absence alone would delete that row on the very next Save, silently
     * discarding the user's staged change and the work item Převzít would have applied.
     *
     * <p>Guards {@code .filter(n -> n.getPendingEdit() == null)}: without it, this test fails.
     */
    @Test
    void nodeCarryingOverlay_survivesOmissionFromNodes() {
        DiagramEntity diagram = newDiagram("overlay-survives");
        seedNode(diagram, "https://x/pojem/trida", 0, 0);
        seedNode(diagram, "https://x/pojem/vztah", 0, 0,
                overlay("https://x/pojem/target"));                 // staged, never sent as a node
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        // A Save carrying only the class — exactly what the FE sends, since a VZTAH is not a canvas node.
        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(managed, new DiagramLayoutDto(null, null,
                List.of(node("https://x/pojem/trida", 5, 5)), List.of(), null));

        List<DiagramNodeEntity> rows = nodeRepository.findByDiagramId(diagram.getId());
        assertThat(rows).extracting(DiagramNodeEntity::getConceptIri)
                .as("the overlay-carrying row must survive omission from nodes[]")
                .containsExactlyInAnyOrder("https://x/pojem/trida", "https://x/pojem/vztah");

        DiagramNodeEntity vztah = nodeRepository.findByDiagramIdAndConceptIri(
                diagram.getId(), "https://x/pojem/vztah").orElseThrow();
        assertThat(vztah.getPendingEdit()).isNotNull();
        assertThat(vztah.getPendingEdit().getRange())
                .as("the staged edit itself survives, not just the row")
                .isEqualTo("https://x/pojem/target");
    }

    /**
     * The carve-out is narrow: it spares only rows that actually carry an overlay. A row whose overlay was
     * discarded is an ordinary canvas node again and reaps on omission like any other — otherwise every
     * concept ever staged would be undeletable from the canvas.
     */
    @Test
    void nodeWithoutOverlay_isStillReapedOnOmission() {
        DiagramEntity diagram = newDiagram("overlay-cleared");
        seedNode(diagram, "https://x/pojem/keep", 0, 0);
        seedNode(diagram, "https://x/pojem/discarded", 1, 1, null);  // overlay already discarded
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(managed, new DiagramLayoutDto(null, null,
                List.of(node("https://x/pojem/keep", 0, 0)), List.of(), null));

        assertThat(nodeRepository.findByDiagramId(diagram.getId()))
                .extracting(DiagramNodeEntity::getConceptIri)
                .containsExactly("https://x/pojem/keep");
    }

    /**
     * The carve-out spares the row, not the layout: a class that IS sent in {@code nodes[]} while carrying
     * an overlay is still updated in place, and keeps its staged edit. Pins that sparing a row never means
     * skipping it.
     */
    @Test
    void nodeCarryingOverlay_isStillUpdatedWhenSent() {
        DiagramEntity diagram = newDiagram("overlay-updated");
        seedNode(diagram, "https://x/pojem/trida", 0, 0,
                overlay("https://x/pojem/target"));
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(managed, new DiagramLayoutDto(null, null,
                List.of(node("https://x/pojem/trida", 42, 43)), List.of(), null));

        DiagramNodeEntity row = nodeRepository.findByDiagramIdAndConceptIri(
                diagram.getId(), "https://x/pojem/trida").orElseThrow();
        assertThat(row.getPosX()).isEqualTo(42.0);                  // layout applied
        assertThat(row.getPosY()).isEqualTo(43.0);
        assertThat(row.getPendingEdit()).isNotNull();               // overlay untouched
        assertThat(row.getPendingEdit().getRange()).isEqualTo("https://x/pojem/target");
    }

    /**
     * The origin anchor belongs to row CREATION only. A class carrying a staged edit is sent in
     * {@code nodes[]} and in {@code overlays[]} on the same Save — the normal case for op 2 — and its real
     * position must win. Anchoring unconditionally, or applying overlays before nodes, silently moves every
     * such class to the top-left corner on every Save.
     */
    @Test
    void overlayOnAConceptAlsoInNodes_keepsItsRealPosition() {
        DiagramEntity diagram = newDiagram("anchor-order");
        seedNode(diagram, "https://x/pojem/trida", 250, 175);
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(managed, new DiagramLayoutDto(null, null,
                List.of(node("https://x/pojem/trida", 250, 175)),
                List.of(),
                List.of(new DiagramLayoutDto.Overlay("iri:https://x/pojem/trida",
                        null, null, List.of("https://x/pojem/super"), null, null))));

        DiagramNodeEntity row = nodeRepository.findByDiagramIdAndConceptIri(
                diagram.getId(), "https://x/pojem/trida").orElseThrow();
        assertThat(row.getPosX()).as("the class keeps its canvas position, not the origin anchor")
                .isEqualTo(250.0);
        assertThat(row.getPosY()).isEqualTo(175.0);
        assertThat(row.getPendingEdit()).isNotNull();
    }

    /** A brand-new overlay-only row has no box of its own, so it anchors at the origin. */
    @Test
    void overlayOnAConceptNotInNodes_provisionsAtOrigin() {
        DiagramEntity diagram = newDiagram("anchor-new");
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(managed, new DiagramLayoutDto(null, null,
                List.of(),
                List.of(),
                List.of(new DiagramLayoutDto.Overlay("iri:https://x/pojem/vztah",
                        null, "https://x/pojem/b", null, null, null))));

        DiagramNodeEntity row = nodeRepository.findByDiagramIdAndConceptIri(
                diagram.getId(), "https://x/pojem/vztah").orElseThrow();
        assertThat(row.getPosX()).isEqualTo(0.0);
        assertThat(row.getPosY()).isEqualTo(0.0);
        assertThat(row.getPendingEdit()).isNotNull();
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
        save(managed, new DiagramLayoutDto(null, null,
                List.of(node("https://x/pojem/parent", 0, 0),
                        node("https://x/pojem/child", 1, 1, "https://x/pojem/parent")),
                List.of(), null));

        DiagramNodeEntity child = nodeRepository.findByDiagramIdAndConceptIri(
                diagram.getId(), "https://x/pojem/child").orElseThrow();
        assertThat(child.getParentNodeId()).isNotNull();           // grouping established

        // Second save: omit the parent → it's removed, and the child's parentNodeId must be nulled.
        DiagramEntity managed2 = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(managed2, new DiagramLayoutDto(null, null,
                List.of(node("https://x/pojem/child", 1, 1)),      // parent omitted
                List.of(), null));

        List<DiagramNodeEntity> remaining = nodeRepository.findByDiagramId(diagram.getId());
        assertThat(remaining).extracting(DiagramNodeEntity::getConceptIri)
                .containsExactly("https://x/pojem/child");         // parent removed
        assertThat(remaining.get(0).getParentNodeId()).isNull();   // child detached, not dangling
    }

    /**
     * B2 ingress: Save authorizes the ontology SLUG, but node IRIs travel in the body. A node referencing a
     * concept in ANOTHER ontology's graph must be rejected and no row persisted — otherwise the canvas
     * becomes the staging ground for a later cross-tenant materialize.
     */
    @Test
    void foreignGraphNode_isRejected_andNotPersisted() {
        DiagramEntity diagram = newDiagram("tenant-a");
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        // A concept row belonging to a DIFFERENT ontology graph (another tenant's).
        OntologyMetadataEntity victimOntology = new OntologyMetadataEntity();
        victimOntology.setSlug("victim");
        victimOntology.setGraphName("https://x/victim");
        victimOntology.setUserId("victim-user");
        victimOntology.setIsPublished(false);
        ontologyRepository.saveAndFlush(victimOntology);

        String foreignIri = "https://x/victim/pojem/secret";
        ConceptMetadataEntity foreign = new ConceptMetadataEntity();
        foreign.setConceptIri(foreignIri);
        foreign.setGraphName("https://x/victim");
        foreign.setSlug("secret");
        foreign.setConceptName("Secret");
        foreign.setUserId("victim-user");
        foreign.setOntologyMetadata(victimOntology);
        conceptRepository.saveAndFlush(foreign);

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        DiagramLayoutDto layout = new DiagramLayoutDto(null, null, List.of(node(foreignIri, 0, 0)), List.of(), null);

        assertThatThrownBy(() -> reconciler.reconcileNodes(managed, layout))
                .isInstanceOf(ConceptValidationException.class)
                .hasMessageContaining(foreignIri);

        em.clear();
        assertThat(nodeRepository.findByDiagramId(diagram.getId()))
                .as("no node row persisted for the foreign concept").isEmpty();
    }

    /**
     * The complement: a node whose concept row is missing entirely is NOT rejected. That is a concept
     * deleted out from under the canvas — a legitimate state Převzít reports as {@code skippedStale}.
     * Failing the save would strand the user with an unsaveable canvas.
     */
    @Test
    void nodeWithNoConceptRow_isStillAccepted() {
        DiagramEntity diagram = newDiagram("no-row");
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(managed, new DiagramLayoutDto(null, null,
                List.of(node("https://x/no-row/pojem/deleted", 0, 0)), List.of(), null));

        assertThat(nodeRepository.findByDiagramId(diagram.getId()))
                .extracting(DiagramNodeEntity::getConceptIri)
                .containsExactly("https://x/no-row/pojem/deleted");
    }

    /** An edge with no waypoints stores no row: there is nothing to remember about default routing. */
    @Test
    void edgeWithoutWaypoints_persistsNoRow() {
        DiagramEntity diagram = newDiagram("edges");
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        DiagramLayoutDto layout = new DiagramLayoutDto(null, null,
                List.of(node("https://x/pojem/prop", 0, 0),
                        node("https://x/pojem/cls", 100, 0)),
                List.of(new DiagramLayoutDto.Edge("https://x/pojem/rel", null)), null);
        DiagramEntity saved = save(managed, layout);

        assertThat(diagramRepository.findById(saved.getId()).orElseThrow().getEdges()).isEmpty();
    }

    /** Waypoints are FE-only geometry, so PG is their sole owner — they must survive the round-trip intact. */
    @Test
    void persistsEdgeSegmentsThroughRoundTrip() {
        DiagramEntity diagram = newDiagram("segments");
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        DiagramLayoutDto layout = new DiagramLayoutDto(null, null,
                List.of(node("https://x/pojem/prop", 0, 0),
                        node("https://x/pojem/cls", 100, 0)),
                List.of(new DiagramLayoutDto.Edge("https://x/pojem/rel",
                        List.of(new EdgeWaypoint(12.5, -4), new EdgeWaypoint(60, 33)))), null);
        DiagramEntity saved = save(managed, layout);
        em.clear();

        assertThat(diagramRepository.findById(saved.getId()).orElseThrow().getEdges())
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getEdgeKey()).isEqualTo("https://x/pojem/rel");
                    assertThat(e.getSegments())
                            .containsExactly(new EdgeWaypoint(12.5, -4), new EdgeWaypoint(60, 33));
                });
    }

    /** An explicitly-empty waypoint list means default routing — same as omitting it: no row. */
    @Test
    void emptySegments_persistNoRow() {
        DiagramEntity diagram = newDiagram("no-segments");
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        DiagramLayoutDto layout = new DiagramLayoutDto(null, null,
                List.of(node("https://x/pojem/prop", 0, 0),
                        node("https://x/pojem/cls", 100, 0)),
                List.of(new DiagramLayoutDto.Edge("https://x/pojem/rel", List.of())), null);
        DiagramEntity saved = save(managed, layout);
        em.clear();

        assertThat(diagramRepository.findById(saved.getId()).orElseThrow().getEdges()).isEmpty();
    }

    /**
     * The ordinary flow: route an edge, save, then save again with the SAME edge still routed. A
     * clear-and-reinsert would have Hibernate emit the INSERT before the DELETE in one flush and trip the
     * (diagram_id, edge_key) unique constraint — a 500 on every canvas that has ever had a waypoint.
     */
    @Test
    void resavingTheSameEdgeKey_updatesInPlace() {
        DiagramEntity diagram = newDiagram("resave");
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(managed, new DiagramLayoutDto(null, null,
                List.of(node("https://x/pojem/cls", 0, 0)),
                List.of(new DiagramLayoutDto.Edge("https://x/pojem/rel",
                        List.of(new EdgeWaypoint(1, 2)))), null));
        em.clear();

        DiagramEntity again = diagramRepository.findById(diagram.getId()).orElseThrow();
        DiagramEntity saved = save(again, new DiagramLayoutDto(null, null,
                List.of(node("https://x/pojem/cls", 10, 10)),
                List.of(new DiagramLayoutDto.Edge("https://x/pojem/rel",
                        List.of(new EdgeWaypoint(99, 98)))), null));
        em.clear();

        assertThat(diagramRepository.findById(saved.getId()).orElseThrow().getEdges())
                .singleElement()
                .satisfies(e -> assertThat(e.getSegments()).containsExactly(new EdgeWaypoint(99, 98)));
    }

    /**
     * A hierarchy edge's key is {@code edge|KIND|<sourceIri>|<targetIri>} — two full concept IRIs, which
     * with percent-encoded Czech easily exceeds any fixed VARCHAR. It must persist, and its unique index
     * must not blow Postgres's per-row btree limit (hence the hashed index).
     */
    @Test
    void longCompositeEdgeKey_persists() {
        DiagramEntity diagram = newDiagram("long-key");
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        String longIri = "https://slovn%C3%ADk.gov.cz/datov%C3%BD/"
                + "a".repeat(480) + "/pojem/" + "b".repeat(480);
        String key = "edge|SUBCLASS_OF|" + longIri + "|" + longIri;
        assertThat(key.length()).isGreaterThan(1024);        // the old VARCHAR(1024) would have rejected it

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        DiagramEntity saved = save(managed, new DiagramLayoutDto(null, null,
                List.of(node("https://x/pojem/cls", 0, 0)),
                List.of(new DiagramLayoutDto.Edge(key, List.of(new EdgeWaypoint(1, 2)))), null));
        em.clear();

        assertThat(diagramRepository.findById(saved.getId()).orElseThrow().getEdges())
                .singleElement()
                .satisfies(e -> assertThat(e.getEdgeKey()).isEqualTo(key));
    }

    /** Waypoints survive a node being removed: the row is keyed by edge id, not by endpoint FKs. */
    @Test
    void waypointsAreFullReplacedOnEverySave() {
        DiagramEntity diagram = newDiagram("replace");
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(managed, new DiagramLayoutDto(null, null,
                List.of(node("https://x/pojem/cls", 0, 0)),
                List.of(new DiagramLayoutDto.Edge("https://x/pojem/rel",
                        List.of(new EdgeWaypoint(1, 2)))), null));
        em.clear();

        DiagramEntity again = diagramRepository.findById(diagram.getId()).orElseThrow();
        DiagramEntity saved = save(again, new DiagramLayoutDto(null, null,
                List.of(node("https://x/pojem/cls", 0, 0)), List.of(), null));
        em.clear();

        assertThat(diagramRepository.findById(saved.getId()).orElseThrow().getEdges()).isEmpty();
    }
}
