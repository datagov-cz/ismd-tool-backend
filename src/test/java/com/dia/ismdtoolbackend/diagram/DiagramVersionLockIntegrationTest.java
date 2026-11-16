package com.dia.ismdtoolbackend.diagram;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.PositionDto;
import com.dia.ismdtoolbackend.exception.DiagramVersionConflictException;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.repository.DiagramNodeRepository;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.service.impl.DiagramServiceImpl;
import com.dia.ismdtoolbackend.testsupport.pg.DiagramServiceIT;
import com.dia.ismdtoolbackend.testsupport.pg.PgTestData;
import com.dia.ismdtoolbackend.testsupport.pg.PostgresIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Finding M3 — the optimistic lock on the layout save. {@code @Version} alone is inert here:
 * {@code saveLayout} loads the diagram fresh in its own transaction, so Hibernate compares the just-read
 * version against itself and always wins. Because membership is a full replace, a stale save would
 * silently DELETE nodes another editor added, taking their staged overlays with them.
 *
 * <p>These tests drive the real {@link DiagramServiceImpl} against real Postgres; only the RDF-side
 * collaborators are mocked (an empty graph is enough — the lock is a pure-PG concern).
 */
// NOT_SUPPORTED (via @DiagramServiceIT): the service opens its own transaction per write, exactly as it
// does behind a real request. An ambient test transaction would mask that — and would hide a commit step
// that never opens one.
@DiagramServiceIT
class DiagramVersionLockIntegrationTest extends PostgresIntegrationTestBase {

    private static final String SLUG = "lock-ontology";
    private static final String GRAPH = "https://x/lock-ontology";

    @Autowired private DiagramRepository diagramRepository;
    @Autowired private DiagramNodeRepository nodeRepository;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private DiagramServiceImpl service;
    @Autowired private PgTestData data;

    private OntologyMetadataEntity ontology;
    private Long diagramId;

    @BeforeEach
    void setUp() {
        ontology = data.createOntology(SLUG, GRAPH, "u1", false);
        diagramId = data.createDiagram(ontology, "Test diagram");
    }

    /** A concept row in THIS ontology's graph, so the node passes the graph-scope guard. */
    private String seedConcept(String local) {
        String iri = GRAPH + "/pojem/" + local;
        data.createConcept(ontology, iri, local, null);
        return iri;
    }

    private DiagramLayoutDto layout(Long version, String... iris) {
        List<DiagramLayoutDto.Node> nodes = java.util.Arrays.stream(iris)
                .map(iri -> new DiagramLayoutDto.Node("iri:" + iri, new PositionDto(0.0, 0.0), null, false, List.of()))
                .toList();
        return new DiagramLayoutDto(version, null, nodes, List.of(), null);
    }

    private List<String> persistedIris() {
        return txTemplate.execute(tx ->
                nodeRepository.findByDiagramId(diagramId).stream()
                        .map(DiagramNodeEntity::getConceptIri)
                        .toList());
    }

    /**
     * The first save of a canvas with no diagram row has nothing to conflict with. Clients send 0 — the
     * version a freshly provisioned row carries — since {@code version} is mandatory on every save. Null is
     * still tolerated here at the service boundary, but the API rejects it before this point.
     */
    @Test
    void firstSave_withZeroVersion_isAccepted() {
        String a = seedConcept("a");

        DiagramDto saved = service.saveLayout(SLUG, diagramId, layout(0L, a));

        assertThat(saved.version()).isNotNull();
        assertThat(persistedIris()).containsExactly(a);
    }

    /** The response carries the version the client must echo next; it advances on every save. */
    @Test
    void saveResponse_carriesTheBumpedVersion() {
        String a = seedConcept("a");

        DiagramDto first = service.saveLayout(SLUG, diagramId, layout(null, a));
        DiagramDto second = service.saveLayout(SLUG, diagramId, layout(first.version(), a));

        assertThat(second.version())
                .as("version advances, so the echoed value is never stale on the next save")
                .isGreaterThan(first.version());
    }

    /**
     * The returned version must equal what is actually STORED, not the pre-increment in-memory value.
     * {@code @Version} is bumped at flush; if the save path does not flush before assembling the response,
     * the client echoes a stale number and its next perfectly-legitimate save 409s. The read below runs in
     * its own transaction — a fresh persistence context, like the client's next HTTP request.
     */
    @Test
    void saveResponse_versionMatchesTheStoredRow() {
        String a = seedConcept("a");

        DiagramDto saved = service.saveLayout(SLUG, diagramId, layout(null, a));

        Long stored = txTemplate.execute(tx ->
                diagramRepository.findById(diagramId).orElseThrow().getVersion());
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

        DiagramDto loaded = service.saveLayout(SLUG, diagramId, layout(null, a));
        Long staleVersion = loaded.version();          // what BOTH editors are holding

        // Editor B saves first: canvas is now {a, b}.
        service.saveLayout(SLUG, diagramId, layout(staleVersion, a, b));
        assertThat(persistedIris()).containsExactlyInAnyOrder(a, b);

        // Editor A saves its own view {a} using the now-stale version.
        assertThatThrownBy(() -> service.saveLayout(SLUG, diagramId, layout(staleVersion, a)))
                .isInstanceOf(DiagramVersionConflictException.class);

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

        DiagramDto first = service.saveLayout(SLUG, diagramId, layout(null, a));
        service.saveLayout(SLUG, diagramId, layout(first.version(), a, b));

        assertThatThrownBy(() -> service.saveLayout(SLUG, diagramId, layout(null, a)))
                .isInstanceOf(DiagramVersionConflictException.class);
        assertThat(persistedIris()).containsExactlyInAnyOrder(a, b);
    }
}
