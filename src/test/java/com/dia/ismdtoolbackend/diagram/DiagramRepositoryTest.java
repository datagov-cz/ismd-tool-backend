package com.dia.ismdtoolbackend.diagram;

import com.dia.ismdtoolbackend.entity.DiagramEdgeEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.entity.DiagramPendingEditEntity;
import com.dia.ismdtoolbackend.config.JpaAuditingConfig;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.DiagramNodeBacking;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;
import com.dia.ismdtoolbackend.outbox.PostgresIntegrationTestBase;
import com.dia.ismdtoolbackend.repository.DiagramEdgeRepository;
import com.dia.ismdtoolbackend.repository.DiagramNodeRepository;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
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
 * Diagram schema applies under Liquibase + {@code ddl-auto=validate} on real Postgres, and the three
 * entities round-trip. {@code @EntityScan} is scoped so strict validation checks only this feature.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@EntityScan(basePackageClasses = DiagramEntity.class)
@EnableJpaRepositories(basePackageClasses = DiagramRepository.class)
@Import(JpaAuditingConfig.class)
class DiagramRepositoryTest extends PostgresIntegrationTestBase {

    @Autowired private DiagramRepository diagramRepository;
    @Autowired private DiagramNodeRepository nodeRepository;
    @Autowired private DiagramEdgeRepository edgeRepository;
    @Autowired private DiagramPendingEditRepository pendingEditRepository;
    @Autowired private OntologyMetadataRepository ontologyRepository;
    @Autowired private EntityManager em;

    private OntologyMetadataEntity ontology(String slug) {
        OntologyMetadataEntity o = new OntologyMetadataEntity();
        o.setSlug(slug);
        o.setGraphName("https://x/" + slug);
        o.setUserId("u1");
        o.setIsPublished(false);
        return ontologyRepository.save(o);
    }

    private DiagramEntity diagramFor(OntologyMetadataEntity ontology) {
        DiagramEntity d = new DiagramEntity();
        d.setOntologyMetadata(ontology);
        d.setViewportX(-120.0);
        d.setViewportY(40.0);
        d.setViewportZoom(0.85);
        return diagramRepository.save(d);
    }

    private DiagramNodeEntity reference(DiagramEntity diagram, String iri, double x, double y) {
        DiagramNodeEntity n = new DiagramNodeEntity();
        n.setDiagram(diagram);
        n.setBacking(DiagramNodeBacking.ISMD_CONCEPT);
        n.setConceptIri(iri);
        n.setPosX(x);
        n.setPosY(y);
        return n;
    }

    @Test
    void schemaValidatesAndDiagramRoundTrips() {
        DiagramEntity saved = diagramFor(ontology("pracovni-pomer"));

        DiagramEntity found = diagramRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getViewportZoom()).isEqualTo(0.85);
        assertThat(found.getVersion()).isNotNull();          // @Version seeded
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(diagramRepository.findByOntologyMetadataSlug("pracovni-pomer")).isPresent();
    }

    // A staged edit round-trips through its own table, keyed by (ontology, concept IRI).
    @Test
    void pendingEditRoundTripsAndIsKeyedByOntologyAndConcept() {
        OntologyMetadataEntity ontology = ontology("overlay-carrier");

        DiagramPendingEdit edit = new DiagramPendingEdit();
        edit.setRange("https://x/pojem/organizace");
        edit.setExactMatch(List.of("https://x/pojem/pracuje-u"));
        Long id = pendingEditRepository.save(
                pendingEdit(ontology, "https://x/pojem/je-zamestnan-u", edit)).getId();

        em.flush();
        em.clear();

        DiagramPendingEditEntity reloaded = pendingEditRepository.findById(id).orElseThrow();
        assertThat(reloaded.getConceptIri()).isEqualTo("https://x/pojem/je-zamestnan-u");
        assertThat(reloaded.getPendingEditJson()).contains("organizace");                // diff persisted
        DiagramPendingEdit back = reloaded.getPendingEdit();
        assertThat(back.getRange()).isEqualTo("https://x/pojem/organizace");
        assertThat(back.getExactMatch()).containsExactly("https://x/pojem/pracuje-u");

        assertThat(pendingEditRepository.findByOntologyMetadataIdAndConceptIri(
                ontology.getId(), "https://x/pojem/je-zamestnan-u")).isPresent();
    }

    // A staged edit needs no canvas node: the concept it targets may be off-canvas, or never on it.
    @Test
    void pendingEditNeedsNoLayoutRow() {
        OntologyMetadataEntity ontology = ontology("staged-off-canvas");
        DiagramPendingEdit edit = new DiagramPendingEdit();
        edit.setDomain("https://x/pojem/osoba");
        pendingEditRepository.save(pendingEdit(ontology, "https://x/pojem/vlastnost", edit));

        em.flush();
        em.clear();

        assertThat(pendingEditRepository.findByOntologyMetadataId(ontology.getId())).hasSize(1);
        assertThat(nodeRepository.findAll())
                .as("staging provisions no layout row — membership is nodes[] alone")
                .noneMatch(n -> "https://x/pojem/vlastnost".equals(n.getConceptIri()));
    }

    // One staged edit per concept per ontology; re-staging updates rather than duplicating.
    @Test
    void secondPendingEditForTheSameConcept_violatesTheUniqueConstraint() {
        OntologyMetadataEntity ontology = ontology("dup-staged");
        DiagramPendingEdit edit = new DiagramPendingEdit();
        edit.setRange("https://x/pojem/a");
        pendingEditRepository.saveAndFlush(pendingEdit(ontology, "https://x/pojem/rel", edit));

        DiagramPendingEdit other = new DiagramPendingEdit();
        other.setRange("https://x/pojem/b");
        assertThatThrownBy(() -> pendingEditRepository.saveAndFlush(
                pendingEdit(ontology, "https://x/pojem/rel", other)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // A layout row is layout only — nothing on it carries staged intent.
    @Test
    void layoutRowCarriesNoStagedEdit() {
        DiagramEntity diagram = diagramFor(ontology("no-overlay"));
        Long id = nodeRepository.save(reference(diagram, "https://x/pojem/plain", 0, 0)).getId();
        em.flush();
        em.clear();

        DiagramNodeEntity reloaded = nodeRepository.findById(id).orElseThrow();
        assertThat(reloaded.getConceptIri()).isEqualTo("https://x/pojem/plain");
        assertThat(pendingEditRepository.findByOntologyMetadataIdAndConceptIri(
                reloaded.getDiagram().getOntologyMetadata().getId(), "https://x/pojem/plain"))
                .isEmpty();
    }

    @Test
    void edgeWaypointsRoundTrip() {
        DiagramEntity diagram = diagramFor(ontology("edges-o"));

        DiagramEdgeEntity edge = new DiagramEdgeEntity();
        edge.setDiagram(diagram);
        edge.setEdgeKey("https://x/pojem/rel");
        edge.setSegments(List.of(new EdgeWaypoint(12.5, -4)));
        edgeRepository.save(edge);
        em.flush();
        em.clear();

        assertThat(edgeRepository.findByDiagramId(diagram.getId())).singleElement()
                .satisfies(e -> {
                    assertThat(e.getEdgeKey()).isEqualTo("https://x/pojem/rel");
                    assertThat(e.getSegments()).containsExactly(new EdgeWaypoint(12.5, -4));
                });
    }

    @Test
    void nodeFindersByDiagramAndIri() {
        DiagramEntity diagram = diagramFor(ontology("finders-o"));
        nodeRepository.save(reference(diagram, "https://x/pojem/a", 0, 0));
        nodeRepository.save(reference(diagram, "https://x/pojem/b", 1, 1));

        assertThat(nodeRepository.findByDiagramId(diagram.getId())).hasSize(2);
        assertThat(nodeRepository.findByDiagramIdAndConceptIri(diagram.getId(), "https://x/pojem/a"))
                .isPresent();
    }

    @Test
    void ontologyDeleteCascadesDiagramNodesAndEdges() {
        OntologyMetadataEntity o = ontology("cascade-o");
        DiagramEntity diagram = diagramFor(o);
        nodeRepository.save(reference(diagram, "https://x/pojem/a", 0, 0));
        nodeRepository.save(reference(diagram, "https://x/pojem/b", 1, 1));
        DiagramEdgeEntity edge = new DiagramEdgeEntity();
        edge.setDiagram(diagram);
        edge.setEdgeKey("https://x/pojem/rel");
        edge.setSegments(List.of(new EdgeWaypoint(1, 2)));
        edgeRepository.save(edge);
        em.flush();
        // Detach everything so the delete exercises the DB-level ON DELETE CASCADE, not JPA's
        // orphan-removal — a bare `delete(managedOntology)` would have Hibernate re-persist the
        // still-managed diagram against the removed parent on flush.
        em.clear();

        ontologyRepository.deleteById(o.getId());   // ON DELETE CASCADE → diagram → nodes → edges
        ontologyRepository.flush();
        em.clear();

        assertThat(diagramRepository.findByOntologyMetadataId(o.getId())).isEmpty();
        assertThat(nodeRepository.findByDiagramId(diagram.getId())).isEmpty();
        assertThat(edgeRepository.findByDiagramId(diagram.getId())).isEmpty();
    }

    /**
     * One waypoint row per edge per diagram: a second Save must update the geometry, never accumulate a
     * duplicate row that read-side assembly would then pick from arbitrarily.
     */
    @Test
    void duplicateEdgeKeyOnOneDiagram_isRejected() {
        DiagramEntity diagram = diagramFor(ontology("enum-o"));

        DiagramEdgeEntity first = new DiagramEdgeEntity();
        first.setDiagram(diagram);
        first.setEdgeKey("https://x/pojem/rel");
        edgeRepository.saveAndFlush(first);

        DiagramEdgeEntity duplicate = new DiagramEdgeEntity();
        duplicate.setDiagram(diagram);
        duplicate.setEdgeKey("https://x/pojem/rel");

        assertThatThrownBy(() -> edgeRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // Two nodes referencing the same concept on one diagram are rejected by the (now plain) unique index.
    @Test
    void duplicateConceptNodeOnSameDiagram_isRejected() {
        DiagramEntity diagram = diagramFor(ontology("dup-o"));
        nodeRepository.saveAndFlush(reference(diagram, "https://x/pojem/same", 0, 0));

        assertThatThrownBy(() ->
                nodeRepository.saveAndFlush(reference(diagram, "https://x/pojem/same", 50, 50)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // The uniqueness guard is scoped to a diagram — the same IRI on a DIFFERENT diagram is fine.
    @Test
    void sameConceptOnDifferentDiagrams_isAllowed() {
        DiagramEntity d1 = diagramFor(ontology("dup-a"));
        DiagramEntity d2 = diagramFor(ontology("dup-b"));
        nodeRepository.saveAndFlush(reference(d1, "https://x/pojem/shared", 0, 0));
        nodeRepository.saveAndFlush(reference(d2, "https://x/pojem/shared", 0, 0)); // different diagram → ok

        assertThat(nodeRepository.findByDiagramId(d1.getId())).hasSize(1);
        assertThat(nodeRepository.findByDiagramId(d2.getId())).hasSize(1);
    }

    /**
     * The endpoint FK columns and their indexes are gone: endpoints are derived from RDF, so a stored copy
     * could silently disagree with the projection. Asserting their absence keeps the migration honest —
     * a reintroduced column would be dead state nothing reads.
     */
    @Test
    void edgeEndpointColumnsAreGone_andEdgeKeyIsUnique() {
        @SuppressWarnings("unchecked")
        List<String> columns = em.createNativeQuery(
                        "SELECT column_name FROM information_schema.columns "
                                + "WHERE table_schema = 'ismd_schema' AND table_name = 'diagram_edges'")
                .getResultList();
        assertThat(columns)
                .doesNotContain("source_node_id", "target_node_id", "source_handle", "target_handle",
                        "edge_kind")
                .contains("edge_key", "segments_json");

        @SuppressWarnings("unchecked")
        List<String> indexes = em.createNativeQuery(
                        "SELECT indexname FROM pg_indexes "
                                + "WHERE schemaname = 'ismd_schema' AND tablename = 'diagram_edges'")
                .getResultList();
        assertThat(indexes)
                .doesNotContain("idx_diagram_edges_source_node_id", "idx_diagram_edges_target_node_id")
                .contains("uq_diagram_edges_diagram_edge_key");
    }

    /**
     * removeNode() drops the node without touching waypoint rows: a row is keyed by the projected edge id,
     * not by endpoint FKs, so an edge that no longer projects simply finds no match on read and is cleared
     * by the next Save. Pinning this stops the old endpoint-cascade being reintroduced.
     */
    @Test
    void removeNode_leavesWaypointRowsForTheNextSaveToClear() {
        DiagramEntity diagram = diagramFor(ontology("removenode-o"));
        diagram.addNode(reference(diagram, "https://x/pojem/a", 0, 0));
        diagram.addNode(reference(diagram, "https://x/pojem/b", 1, 1));
        DiagramEdgeEntity ab = new DiagramEdgeEntity();
        ab.setEdgeKey("https://x/pojem/rel");
        ab.setSegments(List.of(new EdgeWaypoint(1, 2)));
        diagram.addEdge(ab);
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        // Reload managed — removeNode must operate on managed instances for orphanRemoval to fire.
        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        DiagramNodeEntity managedB = managed.getNodes().stream()
                .filter(n -> "https://x/pojem/b".equals(n.getConceptIri()))
                .findFirst().orElseThrow();

        managed.removeNode(managedB);
        diagramRepository.saveAndFlush(managed);
        em.clear();

        assertThat(nodeRepository.findByDiagramId(diagram.getId()))
                .extracting(DiagramNodeEntity::getConceptIri)
                .containsExactly("https://x/pojem/a");
        assertThat(edgeRepository.findByDiagramId(diagram.getId())).hasSize(1);
    }

    // removeNode() nulls the parentNodeId of any child grouped under the removed node.
    @Test
    void removeNode_nullsChildrenParent() {
        DiagramEntity diagram = diagramFor(ontology("parent-o"));
        DiagramNodeEntity parent = reference(diagram, "https://x/pojem/parent", 0, 0);
        DiagramNodeEntity child = reference(diagram, "https://x/pojem/child", 1, 1);
        diagram.addNode(parent);
        diagram.addNode(child);
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        DiagramNodeEntity managedParent = managed.getNodes().stream()
                .filter(n -> "https://x/pojem/parent".equals(n.getConceptIri())).findFirst().orElseThrow();
        DiagramNodeEntity managedChild = managed.getNodes().stream()
                .filter(n -> "https://x/pojem/child".equals(n.getConceptIri())).findFirst().orElseThrow();
        managedChild.setParentNodeId(managedParent.getId());
        diagramRepository.saveAndFlush(managed);
        em.clear();

        DiagramEntity reloaded = diagramRepository.findById(diagram.getId()).orElseThrow();
        DiagramNodeEntity reParent = reloaded.getNodes().stream()
                .filter(n -> "https://x/pojem/parent".equals(n.getConceptIri())).findFirst().orElseThrow();
        reloaded.removeNode(reParent);
        diagramRepository.saveAndFlush(reloaded);
        em.clear();

        DiagramNodeEntity reChild = nodeRepository.findByDiagramIdAndConceptIri(
                diagram.getId(), "https://x/pojem/child").orElseThrow();
        assertThat(reChild.getParentNodeId()).isNull();     // parent gone → child detached, not dangling
    }

    // C1: touch() dirties the diagram row so a save bumps @Version even when only children changed.
    @Test
    void touch_bumpsVersionOnSave() {
        DiagramEntity diagram = diagramRepository.saveAndFlush(diagramFor(ontology("touch-o")));
        Long v0 = diagram.getVersion();

        diagram.touch();
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        Long v1 = diagramRepository.findById(diagram.getId()).orElseThrow().getVersion();
        assertThat(v1).isGreaterThan(v0);
    }

    // @PrePersist rejects a node missing its conceptIri.
    @Test
    void nodeInvariant_isEnforcedOnWrite() {
        DiagramEntity diagram = diagramFor(ontology("invariant-o"));

        DiagramNodeEntity conceptWithoutIri = new DiagramNodeEntity();
        conceptWithoutIri.setDiagram(diagram);
        conceptWithoutIri.setBacking(DiagramNodeBacking.ISMD_CONCEPT);
        conceptWithoutIri.setPosX(0.0);
        conceptWithoutIri.setPosY(0.0);
        assertThatThrownBy(() -> nodeRepository.saveAndFlush(conceptWithoutIri))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("must carry a conceptIri");
    }

    // setPendingEdit throws on a serialization failure (no silent null overlay).
    @Test
    void setPendingEdit_throwsOnSerializationFailure() {
        DiagramPendingEditEntity row = new DiagramPendingEditEntity();
        java.util.List<String> cyclic = new java.util.ArrayList<>();
        @SuppressWarnings({"unchecked", "rawtypes"})
        java.util.List raw = cyclic;
        raw.add(cyclic); // cycle → JsonMappingException on write
        DiagramPendingEdit edit = new DiagramPendingEdit();
        edit.setExactMatch(cyclic);

        assertThatThrownBy(() -> row.setPendingEdit(edit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to serialize pending edit");
        assertThat(row.getPendingEditJson()).isNull(); // never persisted partial/empty
    }

    // A row exists only while it carries an edit, so discarding deletes it rather than blanking it.
    @Test
    void setPendingEdit_rejectsNull() {
        DiagramPendingEditEntity row = new DiagramPendingEditEntity();
        row.setConceptIri("https://x/pojem/rel");

        assertThatThrownBy(() -> row.setPendingEdit(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("discard deletes the row");
    }

    private DiagramPendingEditEntity pendingEdit(OntologyMetadataEntity ontology, String conceptIri,
                                                 DiagramPendingEdit edit) {
        DiagramPendingEditEntity row = new DiagramPendingEditEntity();
        row.setOntologyMetadata(ontology);
        row.setConceptIri(conceptIri);
        row.setPendingEdit(edit);
        return row;
    }
}
