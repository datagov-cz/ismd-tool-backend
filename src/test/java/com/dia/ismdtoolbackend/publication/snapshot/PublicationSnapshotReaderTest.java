package com.dia.ismdtoolbackend.publication.snapshot;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEdgeEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.DiagramNodeBacking;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Diagram;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Node;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Ontology;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.testsupport.pg.PgTestData;
import com.dia.ismdtoolbackend.testsupport.pg.PlainJpaIT;
import com.dia.ismdtoolbackend.testsupport.pg.PostgresIntegrationTestBase;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@PlainJpaIT
@Transactional
class PublicationSnapshotReaderTest extends PostgresIntegrationTestBase {

    private static final String GRAPH = "https://x/snapshot";
    private static final String A = GRAPH + "/pojem/snapshot-reader-a";
    private static final String B = GRAPH + "/pojem/snapshot-reader-b";

    @Autowired private PublicationSnapshotReader reader;
    @Autowired private PgTestData data;
    @Autowired private ConceptMetadataRepository conceptRepository;
    @Autowired private DiagramRepository diagramRepository;
    @Autowired private EntityManager em;

    @Test
    void readsVocabulariesConceptsAndSavedLayout_resolvingParentsToConceptIris() {
        OntologyMetadataEntity ontology = data.createOntology("snapshot", GRAPH, PgTestData.USER, true);
        ConceptMetadataEntity a = data.createConcept(ontology, A, "A", ConceptType.TRIDA);
        a.setIsPublished(true);
        a.setInTezaurus(true);
        conceptRepository.save(a);
        data.createConcept(ontology, B, "B", ConceptType.TRIDA);

        DiagramEntity diagram = diagramRepository.findById(data.createDiagram(ontology, "Přehled")).orElseThrow();
        diagram.setViewportZoom(0.5);
        DiagramNodeEntity parent = node(A, 10, 20);
        diagram.addNode(parent);
        em.persist(parent);
        em.flush();
        DiagramNodeEntity child = node(B, 30, 40);
        child.setParentNodeId(parent.getId());
        child.setVisibleProperties(List.of(A));
        diagram.addNode(child);
        DiagramEdgeEntity routed = new DiagramEdgeEntity();
        routed.setEdgeKey(B);
        routed.setSegments(List.of(new EdgeWaypoint(5, 6)));
        diagram.addEdge(routed);
        DiagramEdgeEntity unrouted = new DiagramEdgeEntity();
        unrouted.setEdgeKey(A);
        diagram.addEdge(unrouted);
        diagramRepository.saveAndFlush(diagram);
        DiagramPendingEdit staged = new DiagramPendingEdit();
        staged.setRange(A);
        data.stagePendingEdit(diagram.getId(), ontology, B, staged);
        em.flush();
        em.clear();

        PublicationSnapshot snapshot = reader.read();

        Ontology o = snapshot.ontologies().stream().filter(x -> x.iri().equals(GRAPH)).findFirst().orElseThrow();
        assertThat(o.slug()).isEqualTo("snapshot");
        assertThat(o.published()).isTrue();
        assertThat(o.concepts()).extracting(PublicationSnapshot.Concept::iri).containsExactly(A, B);
        assertThat(o.concepts().get(0).inTezaurus()).isTrue();
        assertThat(o.concepts().get(1).published()).as("null is_published reads as draft").isFalse();

        Diagram d = snapshot.diagrams().stream().filter(x -> x.ontologyIri().equals(GRAPH)).findFirst().orElseThrow();
        assertThat(d.iriId()).isEqualTo(diagram.getIriId());
        assertThat(d.viewportZoom()).isEqualTo(0.5);
        assertThat(d.nodes()).extracting(Node::conceptIri).containsExactly(A, B);
        assertThat(d.nodes().get(1).parentConceptIri()).isEqualTo(A);
        assertThat(d.nodes().get(1).visibleProperties()).containsExactly(A);
        assertThat(d.edges()).extracting(PublicationSnapshot.Edge::edgeKey).containsExactly(B, A);
        assertThat(d.edges().get(0).segments()).containsExactly(new EdgeWaypoint(5, 6));
        assertThat(d.edges().get(1).segments()).as("no route reads as empty, not null").isEmpty();
        assertThat(snapshot.excludedConceptIris()).isEmpty();
    }

    private static DiagramNodeEntity node(String iri, double x, double y) {
        DiagramNodeEntity n = new DiagramNodeEntity();
        n.setBacking(DiagramNodeBacking.ISMD_CONCEPT);
        n.setConceptIri(iri);
        n.setPosX(x);
        n.setPosY(y);
        return n;
    }
}
