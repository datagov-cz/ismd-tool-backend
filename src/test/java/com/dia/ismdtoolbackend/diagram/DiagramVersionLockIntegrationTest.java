package com.dia.ismdtoolbackend.diagram;

import com.dia.ismdtoolbackend.config.JpaAuditingConfig;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.PositionDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.outbox.PostgresIntegrationTestBase;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramNodeRepository;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.impl.DiagramLayoutReconciler;
import com.dia.ismdtoolbackend.service.impl.DiagramMaterializeService;
import com.dia.ismdtoolbackend.service.impl.DiagramServiceImpl;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
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
import static org.mockito.Mockito.mock;

/**
 * Finding M3 — the optimistic lock on the layout save. {@code @Version} alone is inert here:
 * {@code saveLayout} loads the diagram fresh in its own transaction, so Hibernate compares the just-read
 * version against itself and always wins. Because membership is a full replace, a stale save would
 * silently DELETE nodes another editor added, taking their staged overlays with them.
 *
 * <p>These tests drive the real {@link DiagramServiceImpl} against real Postgres; only the RDF-side
 * collaborators are mocked (an empty graph is enough — the lock is a pure-PG concern).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@EntityScan(basePackageClasses = {DiagramEntity.class, ConceptMetadataEntity.class})
@EnableJpaRepositories(basePackageClasses = {DiagramRepository.class, ConceptMetadataRepository.class})
@Import(JpaAuditingConfig.class)
class DiagramVersionLockIntegrationTest extends PostgresIntegrationTestBase {

    private static final String SLUG = "lock-ontology";
    private static final String GRAPH = "https://x/lock-ontology";

    @Autowired private DiagramRepository diagramRepository;
    @Autowired private DiagramNodeRepository nodeRepository;
    @Autowired private OntologyMetadataRepository ontologyRepository;
    @Autowired private ConceptMetadataRepository conceptRepository;
    @Autowired private EntityManager em;

    private DiagramServiceImpl service;

    @BeforeEach
    void setUp() {
        nodeRepository.deleteAllInBatch();
        diagramRepository.deleteAllInBatch();
        conceptRepository.deleteAllInBatch();
        ontologyRepository.deleteAllInBatch();

        OntologyMetadataEntity ontology = new OntologyMetadataEntity();
        ontology.setSlug(SLUG);
        ontology.setGraphName(GRAPH);
        ontology.setUserId("u1");
        ontology.setIsPublished(false);
        ontologyRepository.saveAndFlush(ontology);

        DiagramMapper mapper = new DiagramMapper();
        // The graph is empty, so the detail extractor is never consulted for content.
        service = new DiagramServiceImpl(
                diagramRepository, ontologyRepository, conceptRepository,
                mock(OntologyDetailExtractor.class), mock(JenaTDB2Repository.class),
                mock(DiagramMaterializeService.class),
                new DiagramLayoutReconciler(mapper, conceptRepository), mapper);
    }

    /** A concept row in THIS ontology's graph, so the node passes the graph-scope guard. */
    private String seedConcept(String local) {
        String iri = GRAPH + "/pojem/" + local;
        ConceptMetadataEntity c = new ConceptMetadataEntity();
        c.setConceptIri(iri);
        c.setGraphName(GRAPH);
        c.setSlug(local);
        c.setConceptName(local);
        c.setUserId("u1");
        c.setOntologyMetadata(ontologyRepository.findBySlug(SLUG).orElseThrow());
        conceptRepository.saveAndFlush(c);
        return iri;
    }

    private DiagramLayoutDto layout(Long version, String... iris) {
        List<DiagramLayoutDto.Node> nodes = java.util.Arrays.stream(iris)
                .map(iri -> new DiagramLayoutDto.Node("iri:" + iri, new PositionDto(0.0, 0.0), null, false))
                .toList();
        return new DiagramLayoutDto(version, null, nodes, List.of());
    }

    private List<String> persistedIris() {
        return nodeRepository.findByDiagramId(diagramRepository.findByOntologyMetadataSlug(SLUG)
                        .orElseThrow().getId()).stream()
                .map(DiagramNodeEntity::getConceptIri)
                .toList();
    }

    /** The first save of a canvas with no diagram row has nothing to conflict with — null is allowed. */
    @Test
    void firstSave_withNullVersion_isAccepted() {
        String a = seedConcept("a");

        DiagramDto saved = service.saveLayout(SLUG, layout(null, a));

        assertThat(saved.version()).isNotNull();
        assertThat(persistedIris()).containsExactly(a);
    }

    /** The response carries the version the client must echo next; it advances on every save. */
    @Test
    void saveResponse_carriesTheBumpedVersion() {
        String a = seedConcept("a");

        DiagramDto first = service.saveLayout(SLUG, layout(null, a));
        DiagramDto second = service.saveLayout(SLUG, layout(first.version(), a));

        assertThat(second.version())
                .as("version advances, so the echoed value is never stale on the next save")
                .isGreaterThan(first.version());
    }

    /**
     * The returned version must equal what is actually STORED, not the pre-increment in-memory value.
     * {@code @Version} is bumped at flush; if the save path does not flush before assembling the response,
     * the client echoes a stale number and its next perfectly-legitimate save 409s. Read back through a
     * cleared persistence context — the next HTTP request is a different one.
     */
    @Test
    void saveResponse_versionMatchesTheStoredRow() {
        String a = seedConcept("a");

        DiagramDto saved = service.saveLayout(SLUG, layout(null, a));

        em.flush();
        em.clear();
        Long stored = diagramRepository.findByOntologyMetadataSlug(SLUG).orElseThrow().getVersion();
        assertThat(saved.version())
                .as("response version must be the stored one, or the client's next save falsely conflicts")
                .isEqualTo(stored);
    }

    /**
     * THE M3 REGRESSION. Two editors load the same canvas. Editor B adds a node and saves. Editor A —
     * still holding the pre-B version — saves its own membership. Because the save is a full replace,
     * accepting A would delete B's node. It must be rejected instead.
     */
    @Test
    void staleSave_isRejected_andDoesNotDeleteTheOtherEditorsNode() {
        String a = seedConcept("a");
        String b = seedConcept("b");

        DiagramDto loaded = service.saveLayout(SLUG, layout(null, a));
        Long staleVersion = loaded.version();          // what BOTH editors are holding

        // Editor B saves first: canvas is now {a, b}.
        service.saveLayout(SLUG, layout(staleVersion, a, b));
        assertThat(persistedIris()).containsExactlyInAnyOrder(a, b);

        // Editor A saves its own view {a} using the now-stale version.
        assertThatThrownBy(() -> service.saveLayout(SLUG, layout(staleVersion, a)))
                .isInstanceOf(DiagramServiceImpl.DiagramVersionConflictException.class);

        // B's node survives — the silent-delete this finding is about did not happen.
        assertThat(persistedIris())
                .as("stale full-replace must not delete the other editor's node")
                .containsExactlyInAnyOrder(a, b);
    }

    /** Once a diagram exists, a null version is a conflict: that client never read the current state. */
    @Test
    void nullVersion_onAnExistingDiagram_isRejected() {
        String a = seedConcept("a");
        String b = seedConcept("b");

        DiagramDto first = service.saveLayout(SLUG, layout(null, a));
        service.saveLayout(SLUG, layout(first.version(), a, b));

        assertThatThrownBy(() -> service.saveLayout(SLUG, layout(null, a)))
                .isInstanceOf(DiagramServiceImpl.DiagramVersionConflictException.class);
        assertThat(persistedIris()).containsExactlyInAnyOrder(a, b);
    }
}