package com.dia.ismdtoolbackend.diagram;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.NodeOverlayDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.PositionDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.exception.DiagramReadbackFailedException;
import com.dia.ismdtoolbackend.exception.JenaTDB2Exception;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.outbox.OutboxEntry;
import com.dia.ismdtoolbackend.outbox.OutboxEntryRepository;
import com.dia.ismdtoolbackend.outbox.PostgresIntegrationTestBase;
import com.dia.ismdtoolbackend.outbox.TransactionTemplateConfig;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramNodeRepository;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.impl.DiagramLayoutReconciler;
import com.dia.ismdtoolbackend.service.impl.DiagramMaterializeService;
import com.dia.ismdtoolbackend.service.impl.DiagramServiceImpl;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import org.apache.jena.rdf.model.ModelFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The optimistic-lock version as the FE actually consumes it, against real Postgres so {@code @Version}
 * increments for real. Pins the contract sentence in {@code docs/DIAGRAM_LAYER_API.md}: "Every successful
 * PUT …/layout AND PATCH …/nodes/overlay advances the version, so always use the newest one you have
 * received" — which requires the overlay response to actually CARRY that version, and to carry the
 * post-increment value.
 *
 * <p>Real PG is the point: {@code @Version} is assigned by the Hibernate flush, so a mocked repository (or
 * H2 with a stubbed save) would return whatever the stub holds and pass even when the service forgets to
 * flush. The round-trip test below is the one that fails on a plain {@code save}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@Import({DiagramOverlayVersionIntegrationTest.Beans.class, TransactionTemplateConfig.class,
        com.dia.ismdtoolbackend.config.JpaAuditingConfig.class})
@EntityScan(basePackageClasses = {OutboxEntry.class, ConceptMetadataEntity.class, DiagramEntity.class})
@EnableJpaRepositories(basePackageClasses = {OutboxEntryRepository.class, ConceptMetadataRepository.class,
        DiagramRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DiagramOverlayVersionIntegrationTest extends PostgresIntegrationTestBase {

    private static final String GRAPH = "https://slovnik.gov.cz/vg";
    private static final String SLUG = "version-ontology";
    private static final String USER = "user123";
    private static final String CLASS_A = GRAPH + "/pojem/trida-a";
    private static final String CLASS_B = GRAPH + "/pojem/trida-b";

    @Autowired private ConceptMetadataRepository conceptRepo;
    @Autowired private OntologyMetadataRepository ontologyRepo;
    @Autowired private DiagramRepository diagramRepo;
    @Autowired private DiagramNodeRepository nodeRepo;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private DiagramServiceImpl diagramService;
    @Autowired private JenaTDB2Repository tdb2;

    @BeforeEach
    void setUp() {
        // The context (and its mock) is cached across tests; reset so a stubbed failure can't leak.
        reset(tdb2);
        when(tdb2.fetchGraph(any())).thenReturn(ModelFactory.createDefaultModel());
        txTemplate.executeWithoutResult(tx -> {
            nodeRepo.deleteAllInBatch();
            diagramRepo.deleteAllInBatch();
            conceptRepo.deleteAllInBatch();
            ontologyRepo.deleteAllInBatch();
        });
        txTemplate.executeWithoutResult(tx -> {
            OntologyMetadataEntity ont = new OntologyMetadataEntity();
            ont.setSlug(SLUG);
            ont.setGraphName(GRAPH);
            ont.setUserId(USER);
            ont.setIsPublished(false);
            ont.setCreatedAt(LocalDateTime.now());
            OntologyMetadataEntity saved = ontologyRepo.save(ont);
            seedConcept(saved, CLASS_A, "Třída A");
            seedConcept(saved, CLASS_B, "Třída B");
        });
    }

    /**
     * A concept metadata row only — no RDF. The overlay path reads live content through a mocked
     * {@link JenaTDB2Repository} (empty graph → stale node), which does not affect versioning.
     */
    private void seedConcept(OntologyMetadataEntity ontology, String iri, String name) {
        ConceptMetadataEntity c = new ConceptMetadataEntity();
        c.setConceptIri(iri);
        c.setConceptName(name);
        c.setConceptType(ConceptType.TRIDA);
        c.setGraphName(GRAPH);
        c.setUserId(USER);
        c.setOntologyMetadata(ontology);
        c.setSlug(iri.substring(iri.lastIndexOf('/') + 1));
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        conceptRepo.save(c);
    }

    /** Put both classes on the canvas, returning the version of that save. */
    private Long seedCanvas() {
        DiagramLayoutDto layout = new DiagramLayoutDto(
                null,
                null,
                List.of(node(CLASS_A, 0, 0), node(CLASS_B, 100, 0)),
                List.of());
        return diagramService.saveLayout(SLUG, layout).version();
    }

    private DiagramLayoutDto.Node node(String iri, double x, double y) {
        return new DiagramLayoutDto.Node(DiagramMapper.NODE_ID_PREFIX + iri, new PositionDto(x, y), null, false);
    }

    private NodeOverlayDto broaderOverlay(String nodeIri, String broaderIri) {
        return new NodeOverlayDto(DiagramMapper.NODE_ID_PREFIX + nodeIri, null, null,
                List.of(broaderIri), null, null, null, null);
    }

    /** The version stored in PG right now — the value a fresh GET would report. */
    private Long storedVersion() {
        return diagramRepo.findByOntologyMetadataSlug(SLUG).orElseThrow().getVersion();
    }

    // ---- tests ----------------------------------------------------------------------------------

    /** The reviewer's finding: the stage response must expose a version at all. */
    @Test
    void stageOverlay_responseCarriesVersion() {
        seedCanvas();

        DiagramDto.Node staged = diagramService.stageOverlay(
                SLUG, DiagramMapper.NODE_ID_PREFIX + CLASS_A, broaderOverlay(CLASS_A, CLASS_B));

        assertThat(staged.version())
                .as("PATCH …/nodes/overlay must return the diagram version the FE has to echo")
                .isNotNull();
    }

    /** "Every successful PATCH …/nodes/overlay advances the version" — and returns the ADVANCED one. */
    @Test
    void stageOverlay_advancesVersionAndReturnsPostIncrementValue() {
        Long afterSave = seedCanvas();

        DiagramDto.Node staged = diagramService.stageOverlay(
                SLUG, DiagramMapper.NODE_ID_PREFIX + CLASS_A, broaderOverlay(CLASS_A, CLASS_B));

        assertThat(staged.version()).as("version advances on a successful stage").isGreaterThan(afterSave);
        // The returned value is the POST-increment one, not the version the service read at entry: a plain
        // save() (no flush) would hand back `afterSave` here.
        assertThat(staged.version()).as("returned version matches what is now stored").isEqualTo(storedVersion());
    }

    /**
     * The round-trip that matters to the FE: echo the version from the overlay response into the next layout
     * save and it must be accepted. This is the assertion a missing flush breaks — a pre-increment version
     * is exactly one behind and 409s.
     */
    @Test
    void versionFromStageResponse_isAcceptedByTheNextLayoutSave() {
        seedCanvas();

        DiagramDto.Node staged = diagramService.stageOverlay(
                SLUG, DiagramMapper.NODE_ID_PREFIX + CLASS_A, broaderOverlay(CLASS_A, CLASS_B));

        DiagramLayoutDto next = new DiagramLayoutDto(
                staged.version(),                       // echo it, exactly as the contract instructs
                null,
                List.of(node(CLASS_A, 50, 50), node(CLASS_B, 100, 0)),
                List.of());

        DiagramDto saved = diagramService.saveLayout(SLUG, next);

        assertThat(saved.version()).isGreaterThan(staged.version());
        // And the overlay survived the layout save (membership replace kept the node).
        assertThat(saved.nodes())
                .filteredOn(n -> (DiagramMapper.NODE_ID_PREFIX + CLASS_A).equals(n.id()))
                .singleElement()
                .satisfies(n -> assertThat(n.data().hasPendingEdits()).isTrue());
    }

    /**
     * The negative half: the version from BEFORE the stage is now stale. Without this, a test could pass by
     * the lock simply never rejecting anything.
     */
    @Test
    void versionFromBeforeTheStage_isRejectedByTheNextLayoutSave() {
        Long beforeStage = seedCanvas();

        diagramService.stageOverlay(
                SLUG, DiagramMapper.NODE_ID_PREFIX + CLASS_A, broaderOverlay(CLASS_A, CLASS_B));

        DiagramLayoutDto stale = new DiagramLayoutDto(
                beforeStage,                            // the version the client held before staging
                null,
                List.of(node(CLASS_A, 50, 50)),
                List.of());

        assertThatThrownBy(() -> diagramService.saveLayout(SLUG, stale))
                .as("a version predating the overlay stage is stale → 409")
                .isInstanceOf(DiagramServiceImpl.DiagramVersionConflictException.class);
    }

    /**
     * The version must be correct at the moment the DTO is BUILT, not merely by the time the caller reads it.
     * The other tests call the service from outside a transaction, so the commit-time flush lands before they
     * observe anything — they cannot tell an in-method flush from a commit-time one. Running the stage inside
     * a caller-owned transaction keeps it open past the return, so the stamped value is the one the service
     * actually had when it built the response.
     *
     * <p>Note this does NOT fail if the service downgrades to a plain {@code save}: the {@code liveConcept}
     * read that follows auto-flushes the persistence context, assigning {@code @Version} before the stamp.
     * The explicit {@code saveAndFlush} is what keeps that independent of a later query's side effect; this
     * test pins the observable guarantee (the stamp is post-increment), not the mechanism that provides it.
     */
    @Test
    void stageOverlay_versionIsPostIncrementAtTheMomentTheResponseIsBuilt() {
        Long afterSave = seedCanvas();

        Long stamped = txTemplate.execute(tx -> diagramService.stageOverlay(
                SLUG, DiagramMapper.NODE_ID_PREFIX + CLASS_A, broaderOverlay(CLASS_A, CLASS_B)).version());

        assertThat(stamped)
                .as("the response is stamped with the advanced version before the transaction commits")
                .isEqualTo(afterSave + 1);
    }

    /** Discarding an overlay is also a successful PATCH, so it advances the version too. */
    @Test
    void discardOverlay_alsoAdvancesAndReturnsVersion() {
        seedCanvas();
        DiagramDto.Node staged = diagramService.stageOverlay(
                SLUG, DiagramMapper.NODE_ID_PREFIX + CLASS_A, broaderOverlay(CLASS_A, CLASS_B));

        // A body carrying only nodeId = discard.
        NodeOverlayDto discard = new NodeOverlayDto(DiagramMapper.NODE_ID_PREFIX + CLASS_A,
                null, null, null, null, null, null, null);
        DiagramDto.Node cleared = diagramService.stageOverlay(
                SLUG, DiagramMapper.NODE_ID_PREFIX + CLASS_A, discard);

        assertThat(cleared.version()).isGreaterThan(staged.version());
        assertThat(cleared.version()).isEqualTo(storedVersion());
        assertThat(cleared.data().hasPendingEdits()).isFalse();
    }

    /**
     * The version belongs to the diagram, not the node: inside the fat read it stays null (omitted on the
     * wire by {@code @JsonInclude(NON_NULL)}) so no client mistakes it for a per-node lock.
     */
    @Test
    void fatRead_leavesPerNodeVersionNull() {
        seedCanvas();
        diagramService.stageOverlay(
                SLUG, DiagramMapper.NODE_ID_PREFIX + CLASS_A, broaderOverlay(CLASS_A, CLASS_B));

        DiagramDto diagram = diagramService.getDiagram(SLUG);

        assertThat(diagram.version()).as("the diagram itself carries the version").isNotNull();
        assertThat(diagram.nodes()).isNotEmpty();
        assertThat(diagram.nodes()).allSatisfy(n ->
                assertThat(n.version()).as("nodes inside the fat read carry no version").isNull());
    }

    /**
     * The layout round-trip for {@code collapsed}: the FE sends it, PG stores it, and the read must give it
     * back — otherwise a collapsed group silently re-expands on every reload. It was persisted but never
     * returned; only an end-to-end save-then-read catches that, since each half worked in isolation.
     */
    @Test
    void collapsed_survivesTheSaveAndComesBackOnTheRead() {
        DiagramLayoutDto layout = new DiagramLayoutDto(
                null, null,
                List.of(new DiagramLayoutDto.Node(DiagramMapper.NODE_ID_PREFIX + CLASS_A,
                                new PositionDto(0.0, 0.0), null, true),
                        new DiagramLayoutDto.Node(DiagramMapper.NODE_ID_PREFIX + CLASS_B,
                                new PositionDto(100.0, 0.0), null, false)),
                List.of());

        DiagramDto saved = diagramService.saveLayout(SLUG, layout);

        // Echoed straight back on the save response...
        assertThat(saved.nodes())
                .filteredOn(n -> (DiagramMapper.NODE_ID_PREFIX + CLASS_A).equals(n.id()))
                .singleElement()
                .satisfies(n -> assertThat(n.collapsed()).isTrue());

        // ...and still there on a fresh read, which is what a page reload actually does.
        DiagramDto reloaded = diagramService.getDiagram(SLUG);
        assertThat(reloaded.nodes())
                .filteredOn(n -> (DiagramMapper.NODE_ID_PREFIX + CLASS_A).equals(n.id()))
                .singleElement()
                .satisfies(n -> assertThat(n.collapsed())
                        .as("a collapsed group must not re-expand on reload").isTrue());
        assertThat(reloaded.nodes())
                .filteredOn(n -> (DiagramMapper.NODE_ID_PREFIX + CLASS_B).equals(n.id()))
                .singleElement()
                .satisfies(n -> assertThat(n.collapsed()).isFalse());
    }

    // ---- write/read split -----------------------------------------------------------------------
    // The PG write and the Fuseki content read are deliberately NOT in one transaction: the fetch is an
    // HTTP call behind a 30s-timeout semaphore, and holding a Hikari connection (pool of 20) across it lets
    // a slow Fuseki exhaust the pool. The layer never writes RDF, so there is no dual-write to keep atomic.

    /**
     * The behaviour the split buys: a Fuseki failure AFTER the commit must not undo the layout. The old
     * single-transaction shape rolled the write back because a *read* failed.
     */
    @Test
    void saveLayout_whenFusekiFailsOnReadback_keepsTheWriteAndReportsItAsReadback() {
        Long before = seedCanvas();
        when(tdb2.fetchGraph(GRAPH)).thenThrow(new JenaTDB2Exception("Fuseki je nedostupná."));

        DiagramLayoutDto move = new DiagramLayoutDto(
                before, null, List.of(node(CLASS_A, 999, 999), node(CLASS_B, 100, 0)), List.of());

        assertThatThrownBy(() -> diagramService.saveLayout(SLUG, move))
                .isInstanceOf(DiagramReadbackFailedException.class)
                .satisfies(e -> assertThat(((DiagramReadbackFailedException) e).getVersion())
                        .as("the error carries the post-write version so the FE need not re-read")
                        .isEqualTo(before + 1));

        // The write is durable: version advanced and the moved position is persisted.
        assertThat(storedVersion()).as("the committed write survives the failed read").isEqualTo(before + 1);
        txTemplate.executeWithoutResult(tx -> assertThat(
                nodeRepo.findByDiagramIdAndConceptIri(
                        diagramRepo.findByOntologyMetadataSlug(SLUG).orElseThrow().getId(), CLASS_A)
                        .orElseThrow().getPosX())
                .as("the layout change itself is committed").isEqualTo(999.0));
    }

    /** Same guarantee on the overlay path: the staged edit survives a failed content read. */
    @Test
    void stageOverlay_whenFusekiFailsOnReadback_keepsTheOverlay() {
        Long before = seedCanvas();
        when(tdb2.fetchGraph(GRAPH)).thenThrow(new JenaTDB2Exception("Fuseki je nedostupná."));

        assertThatThrownBy(() -> diagramService.stageOverlay(
                SLUG, DiagramMapper.NODE_ID_PREFIX + CLASS_A, broaderOverlay(CLASS_A, CLASS_B)))
                .isInstanceOf(DiagramReadbackFailedException.class)
                .satisfies(e -> assertThat(((DiagramReadbackFailedException) e).getVersion())
                        .isEqualTo(before + 1));

        txTemplate.executeWithoutResult(tx -> assertThat(
                nodeRepo.findByDiagramIdAndConceptIri(
                        diagramRepo.findByOntologyMetadataSlug(SLUG).orElseThrow().getId(), CLASS_A)
                        .orElseThrow().getPendingEdit())
                .as("the staged overlay is committed despite the failed read").isNotNull());
    }

    /**
     * A version-conflict rejection must NOT pay for a graph fetch. Write-first ordering exists partly for
     * this: the 409 is the common failure on a shared canvas, and read-first would fetch the whole graph on
     * every stale save just to discard it.
     */
    @Test
    void staleSave_isRejectedWithoutFetchingTheGraph() {
        Long current = seedCanvas();
        clearInvocations(tdb2);

        DiagramLayoutDto stale = new DiagramLayoutDto(
                current - 1, null, List.of(node(CLASS_A, 0, 0)), List.of());

        assertThatThrownBy(() -> diagramService.saveLayout(SLUG, stale))
                .isInstanceOf(DiagramServiceImpl.DiagramVersionConflictException.class);

        verify(tdb2, never()).fetchGraph(any());
    }

    /**
     * The structural guarantee the reviewer asked for: no PG connection is held across the Fuseki call. The
     * stub asserts, at fetch time, that no transaction is active — so the commit has already happened and
     * the connection is back in the pool before the HTTP call begins.
     */
    @Test
    void fusekiReadHappensOutsideAnyPgTransaction() {
        seedCanvas();
        AtomicBoolean txActiveDuringFetch = new AtomicBoolean(true);
        when(tdb2.fetchGraph(GRAPH)).thenAnswer(inv -> {
            txActiveDuringFetch.set(TransactionSynchronizationManager.isActualTransactionActive());
            return ModelFactory.createDefaultModel();
        });

        diagramService.stageOverlay(
                SLUG, DiagramMapper.NODE_ID_PREFIX + CLASS_A, broaderOverlay(CLASS_A, CLASS_B));

        assertThat(txActiveDuringFetch)
                .as("the graph fetch must not run inside the PG transaction").isFalse();
    }

    /** The same for the plain read path, which also held a connection across the fetch. */
    @Test
    void getDiagram_fusekiReadHappensOutsideAnyPgTransaction() {
        seedCanvas();
        AtomicBoolean txActiveDuringFetch = new AtomicBoolean(true);
        when(tdb2.fetchGraph(GRAPH)).thenAnswer(inv -> {
            txActiveDuringFetch.set(TransactionSynchronizationManager.isActualTransactionActive());
            return ModelFactory.createDefaultModel();
        });

        diagramService.getDiagram(SLUG);

        assertThat(txActiveDuringFetch)
                .as("the fat read's graph fetch must not run inside the PG transaction").isFalse();
    }

    @TestConfiguration
    static class Beans {

        /** Empty graph: these tests exercise versioning, not content joining. */
        @Bean JenaTDB2Repository jenaTDB2Repository() {
            return mock(JenaTDB2Repository.class);
        }

        @Bean OntologyDetailExtractor ontologyDetailExtractor() {
            return mock(OntologyDetailExtractor.class);
        }

        @Bean DiagramMapper diagramMapper() {
            return new DiagramMapper();
        }

        @Bean DiagramLayoutReconciler diagramLayoutReconciler(DiagramMapper mapper,
                                                              ConceptMetadataRepository conceptRepo) {
            return new DiagramLayoutReconciler(mapper, conceptRepo);
        }

        /**
         * Built with the PROXIED self so {@code commitLayout}/{@code commitOverlay} actually run in a
         * transaction — these tests use {@code NOT_SUPPORTED}, so there is no ambient one to fall back on.
         * The 8-arg test constructor ({@code self == this}) would leave each {@code saveAndFlush}
         * autocommitting on its own and the layout reconcile would violate the node unique constraint.
         */
        @Bean DiagramServiceImpl diagramServiceImpl(
                DiagramRepository diagramRepo, OntologyMetadataRepository ontologyRepo,
                ConceptMetadataRepository conceptRepo, OntologyDetailExtractor extractor,
                JenaTDB2Repository tdb2, DiagramLayoutReconciler reconciler, DiagramMapper mapper,
                @Lazy DiagramServiceImpl self) {
            return new DiagramServiceImpl(diagramRepo, ontologyRepo, conceptRepo, extractor, tdb2,
                    mock(DiagramMaterializeService.class), reconciler, mapper, self);
        }
    }
}