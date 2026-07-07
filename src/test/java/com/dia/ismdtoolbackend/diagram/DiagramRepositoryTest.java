package com.dia.ismdtoolbackend.diagram;

import com.dia.ismdtoolbackend.entity.DiagramEdgeEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.config.JpaAuditingConfig;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.DiagramEdgeKind;
import com.dia.ismdtoolbackend.enums.DiagramNodeBacking;
import com.dia.ismdtoolbackend.models.diagram.DiagramDraftContent;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the diagram schema ({@code diagrams} / {@code diagram_nodes} / {@code diagram_edges}) applies
 * under Liquibase + {@code ddl-auto=validate} on REAL Postgres, and that the three entities round-trip —
 * including the draft JSON serialization and the unique-diagram-per-ontology constraint.
 *
 * <p>Scoped {@code @EntityScan} to the diagram entities plus {@link OntologyMetadataEntity} (the FK
 * parent) so strict validation checks only this feature's schema — the pre-existing
 * {@code Instant↔timestamptz} mappings elsewhere are out of scope, same as the outbox test.
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
        d.setUserId("u1");
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

    @Test
    void draftNode_serializesContentToJsonColumn() {
        DiagramEntity diagram = diagramFor(ontology("draft-carrier"));

        DiagramNodeEntity draft = new DiagramNodeEntity();
        draft.setDiagram(diagram);
        draft.setBacking(DiagramNodeBacking.DRAFT);
        draft.setPosX(520.0);
        draft.setPosY(210.0);
        DiagramDraftContent content = new DiagramDraftContent();
        content.setConceptType(ConceptType.VZTAH);
        content.setLabel(Map.of("cs", "je zaměstnán u"));
        content.setRange("iri:https://x/pojem/organizace");
        draft.setDraftContent(content);
        Long id = nodeRepository.save(draft).getId();

        em.flush();
        em.clear();

        DiagramNodeEntity reloaded = nodeRepository.findById(id).orElseThrow();
        assertThat(reloaded.getConceptIri()).isNull();
        assertThat(reloaded.getDraftJson()).contains("je zaměstnán u"); // round-trips UTF-8
        DiagramDraftContent back = reloaded.getDraftContent();
        assertThat(back.getConceptType()).isEqualTo(ConceptType.VZTAH);
        assertThat(back.getLabel()).containsEntry("cs", "je zaměstnán u");
        assertThat(back.getRange()).isEqualTo("iri:https://x/pojem/organizace");
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
        edge.setEdgeKind(DiagramEdgeKind.DRAFT_LINK);
        edgeRepository.save(edge);

        assertThat(edgeRepository.findByDiagramId(diagram.getId())).hasSize(1);
        assertThat(edgeRepository.findBySourceNodeIdOrTargetNodeId(tgt.getId(), tgt.getId()))
                .hasSize(1);
    }

    @Test
    void nodeFindersFilterByBackingAndIri() {
        DiagramEntity diagram = diagramFor(ontology("finders-o"));
        nodeRepository.save(reference(diagram, "https://x/pojem/a", 0, 0));
        DiagramNodeEntity draft = new DiagramNodeEntity();
        draft.setDiagram(diagram);
        draft.setBacking(DiagramNodeBacking.DRAFT);
        draft.setPosX(1.0);
        draft.setPosY(1.0);
        nodeRepository.save(draft);

        assertThat(nodeRepository.findByDiagramId(diagram.getId())).hasSize(2);
        assertThat(nodeRepository.findByDiagramIdAndBacking(diagram.getId(), DiagramNodeBacking.ISMD_CONCEPT))
                .hasSize(1);
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
        edge.setEdgeKind(DiagramEdgeKind.DRAFT_LINK);
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
    void draftLinkAndDerivedKinds_persistAcrossAllEnumValues() {
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

    // H1: two ISMD_CONCEPT nodes referencing the same concept on one diagram are rejected by the
    // partial-unique index (Postgres-only; the H2 junit profile can't honour partial indexes, so this
    // assertion is meaningful only here on Testcontainers PG).
    @Test
    void duplicateConceptNodeOnSameDiagram_isRejected() {
        DiagramEntity diagram = diagramFor(ontology("dup-o"));
        nodeRepository.saveAndFlush(reference(diagram, "https://x/pojem/same", 0, 0));

        assertThatThrownBy(() ->
                nodeRepository.saveAndFlush(reference(diagram, "https://x/pojem/same", 50, 50)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // H1: the guard is scoped to a diagram and to ISMD_CONCEPT nodes — same IRI on a DIFFERENT diagram
    // is fine, and multiple drafts (null concept_iri) never collide under the partial index.
    @Test
    void sameConceptOnDifferentDiagrams_andManyDrafts_areAllowed() {
        DiagramEntity d1 = diagramFor(ontology("dup-a"));
        DiagramEntity d2 = diagramFor(ontology("dup-b"));
        nodeRepository.saveAndFlush(reference(d1, "https://x/pojem/shared", 0, 0));
        nodeRepository.saveAndFlush(reference(d2, "https://x/pojem/shared", 0, 0)); // different diagram → ok

        for (int i = 0; i < 3; i++) {
            DiagramNodeEntity draft = new DiagramNodeEntity();
            draft.setDiagram(d1);
            draft.setBacking(DiagramNodeBacking.DRAFT);
            draft.setPosX((double) i);
            draft.setPosY(0.0);
            nodeRepository.saveAndFlush(draft); // null concept_iri, partial index excludes it → ok
        }
        assertThat(nodeRepository.findByDiagramId(d1.getId())).hasSize(4);
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

    // C2: removeNode() drops the node AND its incident edges in one unit of work — no JPA-vs-DB-cascade
    // fight, no leftover edges. Exercises a mid-aggregate delete (the case the ontology-cascade test,
    // which clears the context first, deliberately does not).
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

        // Reload the managed aggregate — removeNode must operate on managed instances for orphanRemoval
        // to fire (entities use identity equality; a detached instance would silently no-op). This is the
        // L1 contract the addNode/removeNode helpers exist to keep callers on.
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

    // M1: the backing⟺content invariant is enforced on write (entity @PrePersist). A DRAFT carrying a
    // conceptIri, or an ISMD_CONCEPT missing one / carrying draft content, is rejected before insert.
    @Test
    void backingInvariant_isEnforcedOnWrite() {
        DiagramEntity diagram = diagramFor(ontology("invariant-o"));

        DiagramNodeEntity draftWithIri = new DiagramNodeEntity();
        draftWithIri.setDiagram(diagram);
        draftWithIri.setBacking(DiagramNodeBacking.DRAFT);
        draftWithIri.setConceptIri("https://x/pojem/should-not-be-here");
        draftWithIri.setPosX(0.0);
        draftWithIri.setPosY(0.0);
        assertThatThrownBy(() -> nodeRepository.saveAndFlush(draftWithIri))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("DRAFT node must not carry a conceptIri");

        DiagramNodeEntity conceptWithoutIri = new DiagramNodeEntity();
        conceptWithoutIri.setDiagram(diagram);
        conceptWithoutIri.setBacking(DiagramNodeBacking.ISMD_CONCEPT);
        conceptWithoutIri.setPosX(0.0);
        conceptWithoutIri.setPosY(0.0);
        assertThatThrownBy(() -> nodeRepository.saveAndFlush(conceptWithoutIri))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("ISMD_CONCEPT node must carry a conceptIri");
    }

    // M2: a serialization failure in setDraftContent throws (no silent NULL-content draft). Modeled with
    // content Jackson can't serialize — a self-referential map.
    @Test
    void setDraftContent_throwsOnSerializationFailure() {
        DiagramNodeEntity node = new DiagramNodeEntity();
        java.util.Map<String, String> cyclic = new java.util.HashMap<>();
        // A raw-typed put to smuggle a non-String value Jackson will choke on for Map<String,String>.
        @SuppressWarnings({"unchecked", "rawtypes"})
        java.util.Map raw = cyclic;
        raw.put("self", cyclic); // cycle → JsonMappingException on write
        DiagramDraftContent content = new DiagramDraftContent();
        content.setLabel(cyclic);

        assertThatThrownBy(() -> node.setDraftContent(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to serialize draft content");
        assertThat(node.getDraftJson()).isNull(); // never persisted partial/empty
    }
}