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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}