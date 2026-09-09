package com.dia.ismdtoolbackend.diagram;

import com.dia.ismdtoolbackend.config.JpaAuditingConfig;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.PositionDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.ViewportDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.entity.DiagramPendingEditEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.DiagramNodeBacking;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;
import com.dia.ismdtoolbackend.outbox.PostgresIntegrationTestBase;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramNodeRepository;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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

    /** Spied so a save's query count can be asserted, not just its result. */
    @MockitoSpyBean private ConceptMetadataRepository conceptRepository;
    @Autowired private DiagramRepository diagramRepository;
    @Autowired private DiagramNodeRepository nodeRepository;
    @MockitoSpyBean private DiagramPendingEditRepository pendingEditRepository;
    @Autowired private OntologyMetadataRepository ontologyRepository;
    @Autowired private EntityManager em;

    private DiagramLayoutReconciler reconciler;

    @BeforeEach
    void initReconciler() {
        reconciler = new DiagramLayoutReconciler(new DiagramMapper(), conceptRepository,
                pendingEditRepository);
    }

    private DiagramEntity newDiagram(String slug) {
        OntologyMetadataEntity o = new OntologyMetadataEntity();
        o.setSlug(slug);
        o.setGraphName("https://x/" + slug);
        o.setUserId("u1");
        o.setIsPublished(false);
        ontologyRepository.save(o);

        DiagramEntity d = new DiagramEntity();
        d.setName("Hlavní diagram");
        d.setOntologyMetadata(o);
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

    /** Stage an edit on a concept of this diagram, independently of any layout row. */
    private void stageEdit(DiagramEntity diagram, String iri, DiagramPendingEdit edit) {
        DiagramPendingEditEntity row = new DiagramPendingEditEntity();
        row.setDiagram(diagram);
        row.setOntologyMetadata(diagram.getOntologyMetadata());
        row.setConceptIri(iri);
        row.setPendingEdit(edit);
        pendingEditRepository.saveAndFlush(row);
    }

    /** The staged edit for a concept of this diagram, or null when nothing is staged. */
    private DiagramPendingEdit stagedEdit(DiagramEntity diagram, String iri) {
        return pendingEditRepository.findByDiagramIdAndConceptIri(diagram.getId(), iri)
                .map(DiagramPendingEditEntity::getPendingEdit)
                .orElse(null);
    }

    /**
     * A concept row in the diagram's own graph, so {@code baseUpdatedAt} has an {@code updatedAt} to read.
     * Auditing populates the timestamp on save.
     */
    private ConceptMetadataEntity seedConcept(DiagramEntity diagram, String iri) {
        ConceptMetadataEntity c = new ConceptMetadataEntity();
        c.setConceptIri(iri);
        c.setGraphName(diagram.getOntologyMetadata().getGraphName());
        c.setSlug(iri.substring(iri.lastIndexOf('/') + 1) + "-" + diagram.getId());
        c.setConceptName("Seed");
        c.setUserId("u1");
        c.setOntologyMetadata(diagram.getOntologyMetadata());
        return conceptRepository.saveAndFlush(c);
    }

    /**
     * The concept's {@code updatedAt} as Postgres holds it, read after an {@code em.clear()}.
     *
     * <p>Always compare fingerprints against this, never against the in-memory entity auditing just
     * stamped: {@code updated_at} is a {@code TIMESTAMP} (microseconds), while the fingerprint reaches the
     * assertion through {@code pending_edit_json} (ISO-8601 text, nanoseconds intact). Reading the
     * pre-truncation value would compare nanoseconds against microseconds and pass only when the clock
     * happens to land on a whole microsecond.
     */
    private LocalDateTime persistedUpdatedAt(String conceptIri) {
        LocalDateTime updatedAt = conceptRepository.findByConceptIri(conceptIri).orElseThrow().getUpdatedAt();
        em.clear();
        return updatedAt;
    }

    /** An FE-shaped Save that carries no canvas nodes, only the staged overlay. */
    private DiagramLayoutDto overlayOnly(String conceptIri, String range) {
        return new DiagramLayoutDto(null, null, List.of(), List.of(),
                List.of(new DiagramLayoutDto.Overlay("iri:" + conceptIri,
                        null, range, null, null, null)));
    }

    /** A minimal structural overlay — a VZTAH repointed at {@code range}. */
    private DiagramPendingEdit overlay(String range) {
        DiagramPendingEdit edit = new DiagramPendingEdit();
        edit.setRange(range);
        return edit;
    }

    private DiagramLayoutDto.Node node(String iri, double x, double y) {
        return new DiagramLayoutDto.Node("iri:" + iri, new PositionDto(x, y), null, false, List.of());
    }

    private DiagramLayoutDto.Node node(String iri, double x, double y, String parentIri) {
        return new DiagramLayoutDto.Node("iri:" + iri, new PositionDto(x, y),
                parentIri != null ? "iri:" + parentIri : null, false, List.of());
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

    /**
     * {@code properties} is three-way like {@code overlays}, not a full replace like node membership: a
     * client that does not manage property visibility omits the field and keeps the curated rows. Were null
     * coerced to {@code []}, every such save would silently blank the class's rendered properties.
     */
    @Test
    void omittedProperties_keepsCuratedRows_whileEmptyListClearsThem() {
        DiagramEntity diagram = newDiagram("visible-props");
        DiagramNodeEntity seeded = seedNode(diagram, "https://x/pojem/trida", 0, 0);
        seeded.setVisibleProperties(List.of("https://x/pojem/vlastnost"));
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        // Save with `properties` absent — the node moves, the curated rows must survive.
        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(managed, new DiagramLayoutDto(null, null,
                List.of(new DiagramLayoutDto.Node(
                        "iri:https://x/pojem/trida", new PositionDto(9.0, 9.0), null, false, null)),
                List.of(), null));

        assertThat(nodeRepository.findByDiagramIdAndConceptIri(
                diagram.getId(), "https://x/pojem/trida").orElseThrow().getVisibleProperties())
                .as("an omitted properties array must not clear the curated rows")
                .containsExactly("https://x/pojem/vlastnost");

        // An explicit [] is the clear signal, and still works.
        DiagramEntity again = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(again, new DiagramLayoutDto(null, null,
                List.of(new DiagramLayoutDto.Node(
                        "iri:https://x/pojem/trida", new PositionDto(9.0, 9.0), null, false, List.of())),
                List.of(), null));

        assertThat(nodeRepository.findByDiagramIdAndConceptIri(
                diagram.getId(), "https://x/pojem/trida").orElseThrow().getVisibleProperties())
                .as("an explicit [] still clears them")
                .isEmpty();
    }

    /**
     * A save resolves every concept it touches in ONE query. The previous shape issued a
     * {@code findByConceptIri} per incoming node for the foreign check, plus up to three more per overlay
     * entry (subject graph-check, staged-row lookup, stale-base fingerprint) — 200+ round trips on the
     * ~200-node canvas the docs describe.
     */
    @Test
    void save_resolvesConceptsInOneQuery_notOnePerNode() {
        DiagramEntity diagram = newDiagram("n-plus-one");
        for (int i = 0; i < 12; i++) {
            seedConcept(diagram, "https://x/n-plus-one/pojem/c" + i);
        }
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        List<DiagramLayoutDto.Node> nodes = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            nodes.add(node("https://x/n-plus-one/pojem/c" + i, i, i));
        }
        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        clearInvocations(conceptRepository, pendingEditRepository);
        save(managed, new DiagramLayoutDto(null, null, nodes, List.of(),
                List.of(new DiagramLayoutDto.Overlay(
                        "iri:https://x/n-plus-one/pojem/c0", null,
                        "https://x/n-plus-one/pojem/c1", null, null, null))));

        verify(conceptRepository, times(1)).findByConceptIriIn(any());
        verify(conceptRepository, never()).findByConceptIri(any());
        // The overlay's staged row comes from one diagram-scoped read, not a lookup per entry.
        verify(pendingEditRepository, times(1)).findByDiagramId(diagram.getId());
        verify(pendingEditRepository, never()).findByDiagramIdAndConceptIri(any(), any());
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
     * A relationship renders as an edge and a property as a row, so neither ever travels in
     * {@code nodes[]}. Their staged edits live in {@code diagram_pending_edits} and have no layout row at
     * all, so a Save that omits them cannot touch the staged work Převzít would have applied.
     */
    @Test
    void stagedEditOnAConceptNeverSentAsANode_survivesTheSave() {
        DiagramEntity diagram = newDiagram("overlay-survives");
        seedNode(diagram, "https://x/pojem/trida", 0, 0);
        diagramRepository.saveAndFlush(diagram);
        stageEdit(diagram, "https://x/pojem/vztah", overlay("https://x/pojem/target"));
        em.clear();

        // A Save carrying only the class — exactly what the FE sends, since a VZTAH is not a canvas node.
        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(managed, new DiagramLayoutDto(null, null,
                List.of(node("https://x/pojem/trida", 5, 5)), List.of(), null));

        assertThat(nodeRepository.findByDiagramId(diagram.getId()))
                .extracting(DiagramNodeEntity::getConceptIri)
                .as("layout holds the canvas only — the VZTAH never had a row")
                .containsExactly("https://x/pojem/trida");

        assertThat(stagedEdit(diagram, "https://x/pojem/vztah"))
                .as("the staged edit survives a Save that never mentions it")
                .isNotNull()
                .extracting(DiagramPendingEdit::getRange)
                .isEqualTo("https://x/pojem/target");
    }

    /**
     * Membership is a plain full replace with no carve-out: an omitted row is off the canvas whether or
     * not its concept carries a staged edit. Removing a node is a visual act and carries no RDF intent, so
     * the edit is left alone — the two instructions in a Save no longer constrain each other.
     */
    @Test
    void classCarryingAStagedEdit_isStillReapedOnOmission() {
        DiagramEntity diagram = newDiagram("overlay-cleared");
        seedNode(diagram, "https://x/pojem/keep", 0, 0);
        seedNode(diagram, "https://x/pojem/removed", 1, 1);
        diagramRepository.saveAndFlush(diagram);
        stageEdit(diagram, "https://x/pojem/removed", overlay("https://x/pojem/target"));
        em.clear();

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(managed, new DiagramLayoutDto(null, null,
                List.of(node("https://x/pojem/keep", 0, 0)), List.of(), null));

        assertThat(nodeRepository.findByDiagramId(diagram.getId()))
                .extracting(DiagramNodeEntity::getConceptIri)
                .as("a staged edit does not pin its concept to the canvas")
                .containsExactly("https://x/pojem/keep");
        assertThat(stagedEdit(diagram, "https://x/pojem/removed"))
                .as("and leaving the canvas does not discard the staged edit")
                .isNotNull();
    }

    /**
     * A class sent in {@code nodes[]} while carrying a staged edit has its layout updated in place and
     * keeps the edit: the two live in different tables, so writing one cannot disturb the other.
     */
    @Test
    void nodeCarryingStagedEdit_isStillUpdatedWhenSent() {
        DiagramEntity diagram = newDiagram("overlay-updated");
        seedNode(diagram, "https://x/pojem/trida", 0, 0);
        diagramRepository.saveAndFlush(diagram);
        stageEdit(diagram, "https://x/pojem/trida", overlay("https://x/pojem/target"));
        em.clear();

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(managed, new DiagramLayoutDto(null, null,
                List.of(node("https://x/pojem/trida", 42, 43)), List.of(), null));

        DiagramNodeEntity row = nodeRepository.findByDiagramIdAndConceptIri(
                diagram.getId(), "https://x/pojem/trida").orElseThrow();
        assertThat(row.getPosX()).isEqualTo(42.0);                  // layout applied
        assertThat(row.getPosY()).isEqualTo(43.0);
        assertThat(stagedEdit(diagram, "https://x/pojem/trida"))    // edit untouched
                .isNotNull()
                .extracting(DiagramPendingEdit::getRange).isEqualTo("https://x/pojem/target");
    }

    /**
     * A class sent in {@code nodes[]} and in {@code overlays[]} on the same Save — the normal case for
     * op 2 — keeps the position from {@code nodes[]}. Staging writes no layout at all, so the two halves
     * of a Save cannot fight over the row.
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
        assertThat(row.getPosX()).as("the class keeps the position sent in nodes[]").isEqualTo(250.0);
        assertThat(row.getPosY()).isEqualTo(175.0);
        assertThat(stagedEdit(diagram, "https://x/pojem/trida")).isNotNull();
    }

    /** Staging is not placement: an overlay on a concept absent from {@code nodes[]} adds no canvas row. */
    @Test
    void overlayOnAConceptNotInNodes_addsNoLayoutRow() {
        DiagramEntity diagram = newDiagram("anchor-new");
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(managed, new DiagramLayoutDto(null, null,
                List.of(),
                List.of(),
                List.of(new DiagramLayoutDto.Overlay("iri:https://x/pojem/vztah",
                        null, "https://x/pojem/b", null, null, null))));

        assertThat(nodeRepository.findByDiagramIdAndConceptIri(diagram.getId(), "https://x/pojem/vztah"))
                .as("staging an edit never puts a concept on the canvas")
                .isEmpty();
        assertThat(stagedEdit(diagram, "https://x/pojem/vztah")).isNotNull();
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
     * B2 ingress: Save authorizes the ontology SLUG, but node IRIs travel in the body. Placing another
     * ontology's concept is allowed — that is the foreign-node feature — so what stops the canvas being a
     * staging ground for a cross-tenant materialize is that the row is marked foreign, which makes it
     * read-only and refuses every overlay targeting it.
     */
    @Test
    void foreignGraphNode_isPersistedAsReadOnly_notAsAnEditableNode() {
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

        reconciler.reconcileNodes(managed, layout);
        diagramRepository.saveAndFlush(managed);

        em.clear();
        assertThat(nodeRepository.findByDiagramId(diagram.getId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getConceptIri()).isEqualTo(foreignIri);
                    assertThat(row.isForeign())
                            .as("derived from the concept's graph — this is what makes it uneditable")
                            .isTrue();
                });
    }

    /**
     * The other half of the derivation, so the flag above is not simply always-on: an own-graph concept
     * is stored editable. Without this the guard could be satisfied by marking everything foreign.
     */
    @Test
    void ownGraphNode_isPersistedEditable() {
        DiagramEntity diagram = newDiagram("tenant-a");
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        OntologyMetadataEntity own = ontologyRepository.findAll().stream()
                .filter(o -> "tenant-a".equals(o.getSlug()))
                .findFirst().orElseThrow();
        String ownIri = own.getGraphName() + "/pojem/mine";
        ConceptMetadataEntity c = new ConceptMetadataEntity();
        c.setConceptIri(ownIri);
        c.setGraphName(own.getGraphName());
        c.setSlug("mine");
        c.setConceptName("Mine");
        c.setUserId(own.getUserId());
        c.setOntologyMetadata(own);
        conceptRepository.saveAndFlush(c);

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        reconciler.reconcileNodes(managed,
                new DiagramLayoutDto(null, null, List.of(node(ownIri, 0, 0)), List.of(), null));
        diagramRepository.saveAndFlush(managed);

        em.clear();
        assertThat(nodeRepository.findByDiagramId(diagram.getId()))
                .singleElement()
                .satisfies(row -> assertThat(row.isForeign()).isFalse());
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

    /**
     * A row IS the edge's canvas membership, so an unrouted edge still persists one — the row says "drawn",
     * and {@code segments_json} merely stays null for default routing. Storing nothing here would make the
     * edge indistinguishable from one the user removed, and it would vanish on the next read.
     */
    @Test
    void edgeWithoutWaypoints_persistsAMembershipRow() {
        DiagramEntity diagram = newDiagram("edges");
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        DiagramLayoutDto layout = new DiagramLayoutDto(null, null,
                List.of(node("https://x/pojem/prop", 0, 0),
                        node("https://x/pojem/cls", 100, 0)),
                List.of(new DiagramLayoutDto.Edge("https://x/pojem/rel", null)), null);
        DiagramEntity saved = save(managed, layout);

        assertThat(diagramRepository.findById(saved.getId()).orElseThrow().getEdges())
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getEdgeKey()).isEqualTo("https://x/pojem/rel");
                    assertThat(e.getSegments()).as("membership without routing").isNull();
                });
    }

    /**
     * Omitting {@code segments} on an entry must not discard geometry the user already drew: the entry is
     * about membership, and says nothing about routing. This was a silent data loss — every save that
     * echoed edges back without their waypoints wiped them.
     */
    @Test
    void omittedSegments_keepTheStoredWaypoints() {
        DiagramEntity diagram = newDiagram("keep-segments");
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(managed, new DiagramLayoutDto(null, null,
                List.of(node("https://x/pojem/prop", 0, 0), node("https://x/pojem/cls", 100, 0)),
                List.of(new DiagramLayoutDto.Edge("https://x/pojem/rel",
                        List.of(new EdgeWaypoint(12.5, -4)))), null));
        em.clear();

        // Second save: the same edge, membership only — no segments field.
        DiagramEntity again = diagramRepository.findById(diagram.getId()).orElseThrow();
        DiagramEntity saved = save(again, new DiagramLayoutDto(again.getVersion(), null,
                List.of(node("https://x/pojem/prop", 0, 0), node("https://x/pojem/cls", 100, 0)),
                List.of(new DiagramLayoutDto.Edge("https://x/pojem/rel", null)), null));
        em.clear();

        assertThat(diagramRepository.findById(saved.getId()).orElseThrow().getEdges())
                .singleElement()
                .satisfies(e -> assertThat(e.getSegments())
                        .as("routing survives a membership-only save")
                        .containsExactly(new EdgeWaypoint(12.5, -4)));
    }

    /**
     * A null {@code edges} is a no-op, not "remove every edge". A client that does not manage edges at all
     * must not clear the canvas's edges by omitting the field.
     */
    @Test
    void nullEdges_leaveMembershipUntouched() {
        DiagramEntity diagram = newDiagram("null-edges");
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(managed, new DiagramLayoutDto(null, null,
                List.of(node("https://x/pojem/prop", 0, 0), node("https://x/pojem/cls", 100, 0)),
                List.of(new DiagramLayoutDto.Edge("https://x/pojem/rel", null)), null));
        em.clear();

        DiagramEntity again = diagramRepository.findById(diagram.getId()).orElseThrow();
        DiagramEntity saved = save(again, new DiagramLayoutDto(again.getVersion(), null,
                List.of(node("https://x/pojem/prop", 0, 0), node("https://x/pojem/cls", 100, 0)),
                null, null));
        em.clear();

        assertThat(diagramRepository.findById(saved.getId()).orElseThrow().getEdges())
                .as("the edge is still on the canvas")
                .hasSize(1);
    }

    /**
     * The removal path: a present {@code edges} is authoritative, so an edge omitted from it leaves the
     * canvas. This is the only way to take an edge off a diagram whose endpoint classes both stay.
     */
    @Test
    void omittingAnEdgeFromAPresentArray_removesItFromTheCanvas() {
        DiagramEntity diagram = newDiagram("remove-edge");
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(managed, new DiagramLayoutDto(null, null,
                List.of(node("https://x/pojem/prop", 0, 0), node("https://x/pojem/cls", 100, 0)),
                List.of(new DiagramLayoutDto.Edge("https://x/pojem/rel",
                        List.of(new EdgeWaypoint(12.5, -4)))), null));
        em.clear();

        DiagramEntity again = diagramRepository.findById(diagram.getId()).orElseThrow();
        DiagramEntity saved = save(again, new DiagramLayoutDto(again.getVersion(), null,
                List.of(node("https://x/pojem/prop", 0, 0), node("https://x/pojem/cls", 100, 0)),
                List.of(), null));
        em.clear();

        assertThat(diagramRepository.findById(saved.getId()).orElseThrow().getEdges())
                .as("an empty array is a real statement: no edges on this canvas")
                .isEmpty();
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

    /**
     * An explicitly-empty waypoint list CLEARS the routing — distinct from omitting the field, which keeps
     * it. The edge stays on the canvas either way; only its geometry differs.
     */
    @Test
    void emptySegments_clearRoutingButKeepTheEdge() {
        DiagramEntity diagram = newDiagram("no-segments");
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(managed, new DiagramLayoutDto(null, null,
                List.of(node("https://x/pojem/prop", 0, 0), node("https://x/pojem/cls", 100, 0)),
                List.of(new DiagramLayoutDto.Edge("https://x/pojem/rel",
                        List.of(new EdgeWaypoint(12.5, -4)))), null));
        em.clear();

        DiagramEntity again = diagramRepository.findById(diagram.getId()).orElseThrow();
        DiagramEntity saved = save(again, new DiagramLayoutDto(again.getVersion(), null,
                List.of(node("https://x/pojem/prop", 0, 0), node("https://x/pojem/cls", 100, 0)),
                List.of(new DiagramLayoutDto.Edge("https://x/pojem/rel", List.of())), null));
        em.clear();

        assertThat(diagramRepository.findById(saved.getId()).orElseThrow().getEdges())
                .singleElement()
                .satisfies(e -> assertThat(e.getSegments())
                        .as("[] is an explicit clear, unlike an omitted field")
                        .isNull());
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

    /**
     * The stale-base fingerprint is stamped when the overlay comes into existence on the row, and never
     * again while it lives there ({@code prior == null ? baseUpdatedAt(iri) : prior.getBaseUpdatedAt()}).
     * The FE re-sends its whole staged set on an ordinary autosave, so re-stamping on every entry would
     * quietly absorb a concurrent concept edit — the overlay would keep materializing against a base the
     * user never saw, which is exactly what STALE_BASE exists to report.
     *
     * <p>Guards the {@code prior == null} condition: hard-coding it true (always re-stamp) disables the
     * STALE_BASE guard without failing any other test in the suite.
     */
    @Test
    void restagingAnExistingOverlay_keepsTheOriginalFingerprint() {
        DiagramEntity diagram = newDiagram("fingerprint-keep");
        diagramRepository.saveAndFlush(diagram);
        ConceptMetadataEntity concept = seedConcept(diagram, "https://x/fingerprint-keep/pojem/vztah");
        String iri = concept.getConceptIri();
        em.clear();
        LocalDateTime staged = persistedUpdatedAt(iri);

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(managed, overlayOnly(iri, "https://x/pojem/a"));
        em.clear();

        // A concurrent edit of the concept itself, after the overlay was staged.
        ConceptMetadataEntity touched = conceptRepository.findByConceptIri(iri).orElseThrow();
        touched.setConceptName("Renamed underneath the diagram");
        conceptRepository.saveAndFlush(touched);
        em.clear();
        assertThat(persistedUpdatedAt(iri))
                .as("the concurrent edit must actually move updatedAt, or this test proves nothing")
                .isAfter(staged);

        // The FE's next autosave re-sends the same staged overlay — it must not re-stamp.
        DiagramEntity again = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(again, overlayOnly(iri, "https://x/pojem/a"));
        em.clear();

        assertThat(stagedEdit(again, iri).getBaseUpdatedAt())
                .as("re-sending a staged overlay must not refresh the stale-base fingerprint")
                .isEqualTo(staged);
    }

    /**
     * The complement, so the test above cannot pass by the fingerprint simply never being written: a
     * discard clears the row's overlay, so the next entry is a fresh stamp and picks up the concept's
     * current {@code updatedAt}. This is the documented STALE_BASE recovery — discard, then re-stage.
     */
    @Test
    void stagingAfterADiscard_stampsAFreshFingerprint() {
        DiagramEntity diagram = newDiagram("fingerprint-fresh");
        diagramRepository.saveAndFlush(diagram);
        ConceptMetadataEntity concept = seedConcept(diagram, "https://x/fingerprint-fresh/pojem/vztah");
        String iri = concept.getConceptIri();
        em.clear();
        LocalDateTime staged = persistedUpdatedAt(iri);

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(managed, overlayOnly(iri, "https://x/pojem/a"));
        em.clear();

        ConceptMetadataEntity touched = conceptRepository.findByConceptIri(iri).orElseThrow();
        touched.setConceptName("Renamed underneath the diagram");
        conceptRepository.saveAndFlush(touched);
        em.clear();
        LocalDateTime afterEdit = persistedUpdatedAt(iri);

        // Discard: an entry carrying conceptIri and nothing else.
        DiagramEntity discarding = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(discarding, new DiagramLayoutDto(null, null, List.of(), List.of(),
                List.of(new DiagramLayoutDto.Overlay("iri:" + iri,
                        null, null, null, null, null))));
        em.clear();
        // The discard deletes the staged row, which is what the re-stage below needs.
        assertThat(stagedEdit(discarding, iri))
                .as("the discard must actually remove the staged edit").isNull();
        em.clear();

        DiagramEntity restaging = diagramRepository.findById(diagram.getId()).orElseThrow();
        save(restaging, overlayOnly(iri, "https://x/pojem/a"));
        em.clear();

        assertThat(stagedEdit(restaging, iri).getBaseUpdatedAt())
                .as("staging after a discard re-reads the concept's current updatedAt")
                .isEqualTo(afterEdit)
                .isNotEqualTo(staged);
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
