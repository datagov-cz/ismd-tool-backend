package com.dia.ismdtoolbackend.diagram;

import com.dia.ismdtoolbackend.entity.DiagramEdgeEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.config.JpaAuditingConfig;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.DiagramEdgeKind;
import com.dia.ismdtoolbackend.enums.DiagramNodeBacking;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.outbox.PostgresIntegrationTestBase;
import com.dia.ismdtoolbackend.repository.DiagramEdgeRepository;
import com.dia.ismdtoolbackend.repository.DiagramNodeRepository;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import org.springframework.dao.DataIntegrityViolationException;

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

    // Overlay coexists with the IRI, round-trips through the JSON column, and the CHECK permits it.
    @Test
    void nodeWithOverlay_roundTripsAndCoexistsWithIri() {
        DiagramEntity diagram = diagramFor(ontology("overlay-carrier"));

        DiagramNodeEntity node = reference(diagram, "https://x/pojem/je-zamestnan-u", 520.0, 210.0);
        DiagramPendingEdit edit = new DiagramPendingEdit();
        edit.setRange("https://x/pojem/organizace");
        edit.setExactMatch(List.of("https://x/pojem/pracuje-u"));
        node.setPendingEdit(edit);
        Long id = nodeRepository.save(node).getId();

        em.flush();
        em.clear();

        DiagramNodeEntity reloaded = nodeRepository.findById(id).orElseThrow();
        assertThat(reloaded.getConceptIri()).isEqualTo("https://x/pojem/je-zamestnan-u"); // IRI kept
        assertThat(reloaded.getPendingEditJson()).contains("organizace");                // diff persisted
        DiagramPendingEdit back = reloaded.getPendingEdit();
        assertThat(back.getRange()).isEqualTo("https://x/pojem/organizace");
        assertThat(back.getExactMatch()).containsExactly("https://x/pojem/pracuje-u");
    }

    // A node with no staged edits is a plain live reference — null overlay, still valid.
    @Test
    void nodeWithoutOverlay_isValidAndHasNullOverlay() {
        DiagramEntity diagram = diagramFor(ontology("no-overlay"));
        Long id = nodeRepository.save(reference(diagram, "https://x/pojem/plain", 0, 0)).getId();
        em.flush();
        em.clear();

        DiagramNodeEntity reloaded = nodeRepository.findById(id).orElseThrow();
        assertThat(reloaded.getPendingEditJson()).isNull();
        assertThat(reloaded.getPendingEdit()).isNull();
    }

    @Test
    void edgeRoundTripsAndEndpointFinderWorks() {
        DiagramEntity diagram = diagramFor(ontology("edges-o"));
        DiagramNodeEntity src = nodeRepository.save(reference(diagram, "https://x/pojem/a", 0, 0));
        DiagramNodeEntity tgt = nodeRepository.save(reference(diagram, "https://x/pojem/b", 200, 0));

        DiagramEdgeEntity edge = new DiagramEdgeEntity();
        edge.setDiagram(diagram);
        edge.setSourceNode(src);
        edge.setTargetNode(tgt);
        edge.setEdgeKind(DiagramEdgeKind.RANGE);
        edgeRepository.save(edge);

        assertThat(edgeRepository.findByDiagramId(diagram.getId())).hasSize(1);
        assertThat(edgeRepository.findBySourceNodeIdOrTargetNodeId(tgt.getId(), tgt.getId()))
                .hasSize(1);
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
        DiagramNodeEntity src = nodeRepository.save(reference(diagram, "https://x/pojem/a", 0, 0));
        DiagramNodeEntity tgt = nodeRepository.save(reference(diagram, "https://x/pojem/b", 1, 1));
        DiagramEdgeEntity edge = new DiagramEdgeEntity();
        edge.setDiagram(diagram);
        edge.setSourceNode(src);
        edge.setTargetNode(tgt);
        edge.setEdgeKind(DiagramEdgeKind.DOMAIN);
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

    @Test
    void allEdgeKinds_persist() {
        DiagramEntity diagram = diagramFor(ontology("enum-o"));
        DiagramNodeEntity a = nodeRepository.save(reference(diagram, "https://x/pojem/a", 0, 0));
        DiagramNodeEntity b = nodeRepository.save(reference(diagram, "https://x/pojem/b", 1, 1));

        for (DiagramEdgeKind kind : DiagramEdgeKind.values()) {
            DiagramEdgeEntity e = new DiagramEdgeEntity();
            e.setDiagram(diagram);
            e.setSourceNode(a);
            e.setTargetNode(b);
            e.setEdgeKind(kind);
            edgeRepository.save(e);
        }
        assertThat(edgeRepository.findByDiagramId(diagram.getId()))
                .hasSize(DiagramEdgeKind.values().length);
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

    // H2: both cascade FK columns on diagram_edges are indexed (unindexed FKs → seq-scan-per-delete).
    @Test
    void edgeEndpointForeignKeyColumnsAreIndexed() {
        @SuppressWarnings("unchecked")
        List<String> indexes = em.createNativeQuery(
                        "SELECT indexname FROM pg_indexes "
                                + "WHERE schemaname = 'ismd_schema' AND tablename = 'diagram_edges'")
                .getResultList();
        assertThat(indexes)
                .contains("idx_diagram_edges_source_node_id", "idx_diagram_edges_target_node_id");
    }

    // removeNode() drops the node and its incident edges in one unit of work (mid-aggregate delete).
    @Test
    void removeNode_alsoRemovesIncidentEdges() {
        DiagramEntity diagram = diagramFor(ontology("removenode-o"));
        DiagramNodeEntity a = reference(diagram, "https://x/pojem/a", 0, 0);
        DiagramNodeEntity b = reference(diagram, "https://x/pojem/b", 1, 1);
        DiagramNodeEntity c = reference(diagram, "https://x/pojem/c", 2, 2);
        diagram.addNode(a);
        diagram.addNode(b);
        diagram.addNode(c);
        DiagramEdgeEntity ab = new DiagramEdgeEntity();
        ab.setSourceNode(a);
        ab.setTargetNode(b);
        ab.setEdgeKind(DiagramEdgeKind.DOMAIN);
        diagram.addEdge(ab);
        DiagramEdgeEntity bc = new DiagramEdgeEntity();
        bc.setSourceNode(b);
        bc.setTargetNode(c);
        bc.setEdgeKind(DiagramEdgeKind.RANGE);
        diagram.addEdge(bc);
        diagramRepository.saveAndFlush(diagram);
        em.clear();

        // Reload managed — removeNode must operate on managed instances for orphanRemoval to fire.
        DiagramEntity managed = diagramRepository.findById(diagram.getId()).orElseThrow();
        DiagramNodeEntity managedB = managed.getNodes().stream()
                .filter(n -> "https://x/pojem/b".equals(n.getConceptIri()))
                .findFirst().orElseThrow();

        managed.removeNode(managedB);          // b is an endpoint of BOTH edges
        diagramRepository.saveAndFlush(managed);
        em.clear();

        assertThat(nodeRepository.findByDiagramId(diagram.getId()))
                .extracting(DiagramNodeEntity::getConceptIri)
                .containsExactlyInAnyOrder("https://x/pojem/a", "https://x/pojem/c");
        assertThat(edgeRepository.findByDiagramId(diagram.getId())).isEmpty(); // both incident edges gone
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
        DiagramNodeEntity node = new DiagramNodeEntity();
        java.util.List<String> cyclic = new java.util.ArrayList<>();
        @SuppressWarnings({"unchecked", "rawtypes"})
        java.util.List raw = cyclic;
        raw.add(cyclic); // cycle → JsonMappingException on write
        DiagramPendingEdit edit = new DiagramPendingEdit();
        edit.setExactMatch(cyclic);

        assertThatThrownBy(() -> node.setPendingEdit(edit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to serialize pending edit");
        assertThat(node.getPendingEditJson()).isNull(); // never persisted partial/empty
    }
}
