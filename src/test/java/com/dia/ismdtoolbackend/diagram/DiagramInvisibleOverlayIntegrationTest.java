package com.dia.ismdtoolbackend.diagram;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.PositionDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.outbox.OutboxEntry;
import com.dia.ismdtoolbackend.outbox.OutboxEntryRepository;
import com.dia.ismdtoolbackend.outbox.PostgresIntegrationTestBase;
import com.dia.ismdtoolbackend.outbox.TransactionTemplateConfig;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramNodeRepository;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.impl.DiagramLayoutReconciler;
import com.dia.ismdtoolbackend.service.OntologyLabelLookup;
import com.dia.ismdtoolbackend.service.impl.DiagramMaterializeService;
import com.dia.ismdtoolbackend.service.impl.DiagramServiceImpl;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Can a Save leave a staged overlay that no read can show? The overlay lives on the CONCEPT's node row, not
 * on the edge it draws, so removing the class it points at removes the edge while the overlay survives —
 * still staged, still applied by Převzít, with nothing on the canvas to explain it.
 *
 * <p>The invariant that closes this: <b>every staged overlay is reachable in the read it belongs to</b>.
 * {@code pendingEdits[]} lists them all — rendered or not — so an edit has one stable home no matter how
 * often canvas membership changes during a session. The {@code pendingEdit} on a node, edge or property
 * row is a copy for the element that draws it, never the only home. This is also what keeps
 * {@code overlays[]} safe to be additive.
 *
 * <p>Unlike {@link DiagramOverlayVersionIntegrationTest}, live content is stubbed with real concepts so
 * edges actually project — an empty graph would make every edge absent for the wrong reason.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@Import({DiagramInvisibleOverlayIntegrationTest.Beans.class, TransactionTemplateConfig.class,
        com.dia.ismdtoolbackend.config.JpaAuditingConfig.class})
@EntityScan(basePackageClasses = {OutboxEntry.class, ConceptMetadataEntity.class, DiagramEntity.class})
@EnableJpaRepositories(basePackageClasses = {OutboxEntryRepository.class, ConceptMetadataRepository.class,
        DiagramRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DiagramInvisibleOverlayIntegrationTest extends PostgresIntegrationTestBase {

    private static final String GRAPH = "https://slovnik.gov.cz/vg";
    private static final String SLUG = "invisible-overlay-ontology";
    private static final String USER = "user123";
    private static final String CLASS_A = GRAPH + "/pojem/trida-a";
    private static final String CLASS_B = GRAPH + "/pojem/trida-b";
    private static final String CLASS_C = GRAPH + "/pojem/trida-c";
    /** A VZTAH, live domain A → range B. Renders as an edge, never travels in nodes[]. */
    private static final String REL = GRAPH + "/pojem/vztah-a-b";
    /** A VLASTNOST whose live domain is A. Renders as a row inside A, never as a node. */
    private static final String PROP = GRAPH + "/pojem/vlastnost-a";

    @Autowired private ConceptMetadataRepository conceptRepo;
    @Autowired private OntologyMetadataRepository ontologyRepo;
    @Autowired private DiagramRepository diagramRepo;
    @Autowired private DiagramNodeRepository nodeRepo;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private DiagramServiceImpl diagramService;
    @Autowired private JenaTDB2Repository tdb2;
    @Autowired private OntologyDetailExtractor extractor;

    @BeforeEach
    void setUp() {
        reset(tdb2, extractor);
        // liveConcepts() short-circuits on an EMPTY model, and a bare createResource() adds no statement —
        // the model needs a real triple or the extractor stub below is never consulted.
        Model nonEmpty = ModelFactory.createDefaultModel();
        nonEmpty.add(nonEmpty.createResource(CLASS_A),
                org.apache.jena.vocabulary.RDF.type,
                nonEmpty.createResource("http://www.w3.org/2002/07/owl#Class"));
        when(tdb2.fetchGraph(any())).thenReturn(nonEmpty);
        when(extractor.applyOFNTransformations(any())).thenAnswer(inv -> inv.getArgument(0));
        stubLiveContent(concept(CLASS_A, "Třída A"), concept(CLASS_B, "Třída B"), concept(CLASS_C, "Třída C"));

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
            seedConcept(saved, CLASS_A, "Třída A", ConceptType.TRIDA);
            seedConcept(saved, CLASS_B, "Třída B", ConceptType.TRIDA);
            seedConcept(saved, CLASS_C, "Třída C", ConceptType.TRIDA);
            seedConcept(saved, REL, "vztah A-B", ConceptType.VZTAH);
            seedConcept(saved, PROP, "vlastnost A", ConceptType.VLASTNOST);
        });
    }

    // ---- fixtures -------------------------------------------------------------------------------

    /** What the extractor reports as live RDF for this graph. */
    private void stubLiveContent(ConceptDetailModel... concepts) {
        when(extractor.extractOntologyDetail(any())).thenReturn(
                OntologyDetailModel.builder().concepts(List.of(concepts)).build());
    }

    private ConceptDetailModel concept(String iri, String name) {
        return ConceptDetailModel.builder().iri(iri).name(Map.of("cs", name)).build();
    }

    /** The live VZTAH: domain A, range B — projects as an edge A→B while both are on canvas. */
    private ConceptDetailModel liveRel() {
        return ConceptDetailModel.builder()
                .iri(REL).name(Map.of("cs", "vztah A-B")).domain(CLASS_A).range(CLASS_B).build();
    }

    /** The live VLASTNOST: domain A — projects as a row inside A while A is on canvas. */
    private ConceptDetailModel liveProp() {
        return ConceptDetailModel.builder()
                .iri(PROP).name(Map.of("cs", "vlastnost A")).domain(CLASS_A).build();
    }

    private void seedConcept(OntologyMetadataEntity ontology, String iri, String name, ConceptType type) {
        ConceptMetadataEntity c = new ConceptMetadataEntity();
        c.setConceptIri(iri);
        c.setConceptName(name);
        c.setConceptType(type);
        c.setGraphName(GRAPH);
        c.setUserId(USER);
        c.setOntologyMetadata(ontology);
        c.setSlug(iri.substring(iri.lastIndexOf('/') + 1));
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        conceptRepo.save(c);
    }

    private DiagramLayoutDto.Node node(String iri, double x, double y) {
        return new DiagramLayoutDto.Node(DiagramMapper.NODE_ID_PREFIX + iri, new PositionDto(x, y), null, false, List.of());
    }

    /** The version a fresh GET would report — null before the first save provisions the diagram row. */
    private Long storedVersion() {
        return diagramRepo.findByOntologyMetadataIdOrderByIdAsc(ontologyId()).stream().findFirst().map(DiagramEntity::getVersion).orElse(null);
    }

    /** Save with exactly these classes on the canvas, carrying these overlays. */
    private DiagramDto save(List<String> canvasClasses, DiagramLayoutDto.Overlay... overlays) {
        List<DiagramLayoutDto.Node> nodes = new java.util.ArrayList<>();
        double x = 0;
        for (String iri : canvasClasses) {
            nodes.add(node(iri, x, 0));
            x += 100;
        }
        return diagramService.saveLayout(SLUG, diagramId(), new DiagramLayoutDto(
                storedVersion(), null, nodes, PLACED_EDGES,
                overlays.length == 0 ? null : List.of(overlays)));
    }

    /** As {@link #save}, with {@link #PROP} placed in {@code hostClass}'s cell — rows are curated. */
    private DiagramDto saveWithPropertyOn(String hostClass, List<String> canvasClasses,
                                          DiagramLayoutDto.Overlay... overlays) {
        List<DiagramLayoutDto.Node> nodes = new java.util.ArrayList<>();
        double x = 0;
        for (String iri : canvasClasses) {
            nodes.add(new DiagramLayoutDto.Node(DiagramMapper.NODE_ID_PREFIX + iri, new PositionDto(x, 0.0),
                    null, false, iri.equals(hostClass) ? List.of(PROP) : List.of()));
            x += 100;
        }
        return diagramService.saveLayout(SLUG, diagramId(), new DiagramLayoutDto(
                storedVersion(), null, nodes, PLACED_EDGES,
                overlays.length == 0 ? null : List.of(overlays)));
    }

    /**
     * Edge membership for these fixtures: REL is placed on the canvas, so the tests below exercise overlay
     * VISIBILITY rather than edge membership. An edge absent from edges[] is simply not on the canvas, which
     * would make every assertion here vacuously pass.
     */
    private static final List<DiagramLayoutDto.Edge> PLACED_EDGES =
            List.of(new DiagramLayoutDto.Edge(REL, null));

    private DiagramDto.Edge edgeFor(DiagramDto diagram, String conceptIri) {
        return diagram.edges().stream()
                .filter(e -> conceptIri.equals(e.id()))
                .findFirst()
                .orElse(null);
    }

    private List<String> nodeIds(DiagramDto diagram) {
        return diagram.nodes().stream().map(DiagramDto.Node::id).toList();
    }

    private DiagramDto.PendingEditEntry pendingEditFor(DiagramDto diagram, String conceptIri) {
        return diagram.pendingEdits().stream()
                .filter(e -> conceptIri.equals(e.iri()))
                .findFirst()
                .orElse(null);
    }

    // ---- case 1: VZTAH whose staged range points at a class removed from the canvas ------------

    /**
     * Stage {@code range: C} on the relationship while C is on the canvas, then remove C. The edge stops
     * being drawn ({@code drawable(C)} fails), but the overlay lives on REL's own node row and survives the
     * reap — so the read reports a pending change the canvas cannot show.
     */
    @Test
    void removingTheClassAStagedRangePointsAt_leavesTheOverlayStagedButInvisible() {
        stubLiveContent(concept(CLASS_A, "Třída A"), concept(CLASS_B, "Třída B"),
                concept(CLASS_C, "Třída C"), liveRel());

        // A, B, C on canvas; stage the relationship's range onto C. The edge draws A→C.
        save(List.of(CLASS_A, CLASS_B, CLASS_C));
        DiagramDto staged = save(List.of(CLASS_A, CLASS_B, CLASS_C),
                new DiagramLayoutDto.Overlay(DiagramMapper.NODE_ID_PREFIX + REL,
                        null, CLASS_C, null, null, null));

        assertThat(edgeFor(staged, REL))
                .as("with C on canvas the overlaid edge A→C is drawn")
                .isNotNull();
        assertThat(staged.pendingEdits()).hasSize(1);

        // The user removes C from the canvas. ReactFlow drops the edge; the FE sends neither C nor overlays.
        DiagramDto after = save(List.of(CLASS_A, CLASS_B));

        assertThat(edgeFor(after, REL))
                .as("C is off-canvas so the edge is no longer projected")
                .isNull();
        assertThat(after.pendingEdits().size())
                .as("the staged change survives removing C — the overlay lives on REL's row, not on the edge")
                .isEqualTo(1);

        // NOT invisible: a VZTAH is never a canvas node, so the staged edit reaches the client through
        // pendingEdits[] — the channel for overlays the canvas renders nowhere.
        assertThat(nodeIds(after))
                .as("a VZTAH is never emitted in nodes[] — the FE renders relationships from edges[]")
                .doesNotContain(DiagramMapper.NODE_ID_PREFIX + REL);

        DiagramDto.PendingEditEntry entry = pendingEditFor(after, REL);
        assertThat(entry)
                .as("the read carries the VZTAH's staged edit in pendingEdits[], so it stays reachable")
                .isNotNull();
        assertThat(entry.pendingEdit().getRange()).isEqualTo(CLASS_C);
    }

    // ---- case 2: VLASTNOST whose staged domain points at a class removed from the canvas -------

    /**
     * The same shape for a property: stage {@code domain: C}, then remove C. The row disappears with its
     * class, and the property's overlay survives on its own node row.
     */
    @Test
    void removingTheClassAStagedDomainPointsAt_leavesThePropertyOverlayInvisible() {
        stubLiveContent(concept(CLASS_A, "Třída A"), concept(CLASS_B, "Třída B"),
                concept(CLASS_C, "Třída C"), liveProp());

        save(List.of(CLASS_A, CLASS_B, CLASS_C));
        // Place the property on C: rows are curated, so staging domain=C alone would render nothing and the
        // test would pass for the wrong reason. Placing it is what the user's drag does.
        DiagramDto staged = saveWithPropertyOn(CLASS_C, List.of(CLASS_A, CLASS_B, CLASS_C),
                new DiagramLayoutDto.Overlay(DiagramMapper.NODE_ID_PREFIX + PROP,
                        CLASS_C, null, null, null, null));

        DiagramDto.Node classC = staged.nodes().stream()
                .filter(n -> (DiagramMapper.NODE_ID_PREFIX + CLASS_C).equals(n.id()))
                .findFirst().orElseThrow();
        assertThat(classC.data().properties())
                .as("with C on canvas the property renders as a row inside it")
                .anyMatch(p -> PROP.equals(p.iri()));

        DiagramDto after = save(List.of(CLASS_A, CLASS_B));

        assertThat(after.nodes())
                .as("no class renders the property as a ROW once its staged domain is off-canvas")
                .allSatisfy(n -> assertThat(n.data().properties()).noneMatch(p -> PROP.equals(p.iri())));
        assertThat(after.pendingEdits()).hasSize(1);

        // Same as the VZTAH: a VLASTNOST is never a canvas node, so the staged edit reaches the client
        // through pendingEdits[].
        assertThat(nodeIds(after))
                .as("a VLASTNOST is never emitted in nodes[] — the FE renders it as a row in its class")
                .doesNotContain(DiagramMapper.NODE_ID_PREFIX + PROP);

        DiagramDto.PendingEditEntry entry = pendingEditFor(after, PROP);
        assertThat(entry)
                .as("the read carries the VLASTNOST's staged edit in pendingEdits[]")
                .isNotNull();
        assertThat(entry.pendingEdit().getDomain()).isEqualTo(CLASS_C);
    }

    // ---- the contrast: a staged overlay whose targets stay on canvas remains visible -----------

    /**
     * The control. Nothing about staging is inherently invisible — the overlay disappears only because the
     * class it points at left the canvas.
     */
    @Test
    void aStagedOverlayWhoseTargetStaysOnCanvas_remainsVisible() {
        stubLiveContent(concept(CLASS_A, "Třída A"), concept(CLASS_B, "Třída B"),
                concept(CLASS_C, "Třída C"), liveRel());

        save(List.of(CLASS_A, CLASS_B, CLASS_C));
        save(List.of(CLASS_A, CLASS_B, CLASS_C),
                new DiagramLayoutDto.Overlay(DiagramMapper.NODE_ID_PREFIX + REL,
                        null, CLASS_C, null, null, null));

        DiagramDto after = save(List.of(CLASS_A, CLASS_B, CLASS_C));

        DiagramDto.Edge edge = edgeFor(after, REL);
        assertThat(edge).as("C stays on canvas, so the overlaid edge keeps rendering").isNotNull();
        assertThat(edge.target()).isEqualTo(DiagramMapper.NODE_ID_PREFIX + CLASS_C);
        assertThat(edge.data().hasPendingEdits()).isTrue();
        assertThat(after.pendingEdits())
                .as("the edit is listed even while its edge renders — one stable home, plus a copy on the "
                        + "element that draws it")
                .extracting(DiagramDto.PendingEditEntry::iri)
                .containsExactly(REL);
    }

    // ---- nodes[] is the canvas; pendingEdits[] is every staged edit, canvas or not --------------

    /**
     * The Step 0 guarantee. A VZTAH and a VLASTNOST both carry staged overlays while every class stays on
     * canvas: neither may appear in {@code nodes[]}, because the FE renders relationships from
     * {@code edges[]} and properties from {@code data.properties[]}. A node-shaped copy at the origin is
     * a second, position-less duplicate of something already on screen.
     */
    @Test
    void relationshipsAndPropertiesAreNeverEmittedAsCanvasNodes() {
        stubLiveContent(concept(CLASS_A, "Třída A"), concept(CLASS_B, "Třída B"),
                concept(CLASS_C, "Třída C"), liveRel(), liveProp());

        DiagramDto after = saveWithPropertyOn(CLASS_A, List.of(CLASS_A, CLASS_B, CLASS_C),
                new DiagramLayoutDto.Overlay(DiagramMapper.NODE_ID_PREFIX + REL,
                        null, CLASS_C, null, null, null),
                new DiagramLayoutDto.Overlay(DiagramMapper.NODE_ID_PREFIX + PROP,
                        CLASS_A, null, null, null, null));

        assertThat(after.nodes())
                .as("nodes[] is the canvas: classes only, whatever else carries an overlay")
                .allSatisfy(n -> assertThat(n.data().conceptType()).isEqualTo(ConceptType.TRIDA));
        assertThat(nodeIds(after))
                .doesNotContain(DiagramMapper.NODE_ID_PREFIX + REL, DiagramMapper.NODE_ID_PREFIX + PROP);

        // Both are rendered — the VZTAH as an edge, the VLASTNOST as a row in A — so neither is orphaned.
        assertThat(edgeFor(after, REL)).as("the VZTAH renders as an edge").isNotNull();
        assertThat(after.pendingEdits())
                .as("pendingEdits[] is complete: both are listed even though the canvas draws both")
                .extracting(DiagramDto.PendingEditEntry::iri)
                .containsExactlyInAnyOrder(REL, PROP);
    }

    /**
     * A class the user takes off the canvas without discarding its overlay. Layout removal is pure
     * visuals, so the staged edit must survive — and stay reachable, since no canvas element renders it.
     */
    @Test
    void aClassRemovedFromCanvasKeepsItsOverlayReachableInPendingEdits() {
        stubLiveContent(concept(CLASS_A, "Třída A"), concept(CLASS_B, "Třída B"),
                concept(CLASS_C, "Třída C"));

        save(List.of(CLASS_A, CLASS_B, CLASS_C),
                new DiagramLayoutDto.Overlay(DiagramMapper.NODE_ID_PREFIX + CLASS_C,
                        null, null, List.of(CLASS_A), null, null));

        DiagramDto after = save(List.of(CLASS_A, CLASS_B));

        assertThat(nodeIds(after))
                .as("membership is a full replace — the class is off the canvas")
                .doesNotContain(DiagramMapper.NODE_ID_PREFIX + CLASS_C);

        DiagramDto.PendingEditEntry entry = pendingEditFor(after, CLASS_C);
        assertThat(entry)
                .as("its staged edit survives and is reachable — removal carries no RDF intent")
                .isNotNull();
        assertThat(entry.pendingEdit().getBroaderConcept()).containsExactly(CLASS_A);
        assertThat(entry.conceptType()).isEqualTo(ConceptType.TRIDA);
        assertThat(after.pendingEdits()).hasSize(1);
    }

    /**
     * {@code pendingEdits[]} is COMPLETE — it lists every staged edit regardless of what the canvas
     * renders, so a client can hold one stable list instead of re-deriving which of four places owns an
     * edit each time membership changes. The copies on nodes, edges and property rows are a rendering
     * convenience and must never be the only home for an edit.
     */
    @Test
    void pendingEditsListsEveryStagedEditIncludingRenderedOnes() {
        stubLiveContent(concept(CLASS_A, "Třída A"), concept(CLASS_B, "Třída B"),
                concept(CLASS_C, "Třída C"), liveRel(), liveProp());

        // REL renders as an edge (both endpoints on canvas); PROP's staged domain is C, then removed.
        saveWithPropertyOn(CLASS_C, List.of(CLASS_A, CLASS_B, CLASS_C),
                new DiagramLayoutDto.Overlay(DiagramMapper.NODE_ID_PREFIX + REL,
                        null, CLASS_B, null, null, null),
                new DiagramLayoutDto.Overlay(DiagramMapper.NODE_ID_PREFIX + PROP,
                        CLASS_C, null, null, null, null));

        DiagramDto after = save(List.of(CLASS_A, CLASS_B));

        assertThat(after.pendingEdits())
                .as("both the rendered edit (REL, drawn as an edge) and the invisible one (PROP) are listed")
                .extracting(DiagramDto.PendingEditEntry::iri)
                .containsExactlyInAnyOrder(REL, PROP);

        // Everything the canvas draws with a staged edit must ALSO be in the list — the copy on the element
        // never replaces the entry, so dragging a concept on or off screen cannot move where the edit lives.
        List<String> renderedWithEdits = new ArrayList<>();
        after.nodes().stream().filter(n -> n.data().hasPendingEdits())
                .forEach(n -> renderedWithEdits.add(n.data().iri()));
        after.edges().stream()
                .filter(e -> e.data() != null && Boolean.TRUE.equals(e.data().hasPendingEdits()))
                .forEach(e -> renderedWithEdits.add(e.data().iri()));
        after.nodes().stream().flatMap(n -> n.data().properties().stream())
                .filter(DiagramDto.PropertyRow::hasPendingEdits)
                .forEach(row -> renderedWithEdits.add(row.iri()));

        assertThat(renderedWithEdits).as("this fixture deliberately keeps staged work on screen").isNotEmpty();
        assertThat(after.pendingEdits()).extracting(DiagramDto.PendingEditEntry::iri)
                .as("no staged edit lives only on the element that draws it")
                .containsAll(renderedWithEdits);
    }

    /**
     * The case that distinguishes "complete" from "complement": a CLASS that carries a staged edit and
     * stays on the canvas. It is emitted as a node with its {@code pendingEdit} attached, and it must be
     * listed here too — otherwise dragging it off canvas would move the edit into this array and dragging
     * it back would move it out, which is exactly the churn a stable list exists to prevent.
     */
    @Test
    void aStagedEditOnAClassThatStaysOnCanvasIsStillListed() {
        stubLiveContent(concept(CLASS_A, "Třída A"), concept(CLASS_B, "Třída B"),
                concept(CLASS_C, "Třída C"));

        DiagramDto after = save(List.of(CLASS_A, CLASS_B, CLASS_C),
                new DiagramLayoutDto.Overlay(DiagramMapper.NODE_ID_PREFIX + CLASS_C,
                        null, null, List.of(CLASS_A), null, null));

        DiagramDto.Node classC = after.nodes().stream()
                .filter(n -> (DiagramMapper.NODE_ID_PREFIX + CLASS_C).equals(n.id()))
                .findFirst().orElseThrow();
        assertThat(classC.data().hasPendingEdits())
                .as("the class renders as a node carrying its overlay")
                .isTrue();

        assertThat(after.pendingEdits())
                .as("and is listed regardless — membership must never decide where an edit lives")
                .extracting(DiagramDto.PendingEditEntry::iri)
                .containsExactly(CLASS_C);
    }

    @TestConfiguration
    static class Beans {

        @Bean JenaTDB2Repository jenaTDB2Repository() {
            return mock(JenaTDB2Repository.class);
        }

        /** Stubbed per-test so live content can carry real domain/range and edges actually project. */
        @Bean OntologyDetailExtractor ontologyDetailExtractor() {
            return mock(OntologyDetailExtractor.class);
        }

        @Bean DiagramMapper diagramMapper() {
            return new DiagramMapper();
        }

        @Bean DiagramLayoutReconciler diagramLayoutReconciler(DiagramMapper mapper,
                                                              ConceptMetadataRepository conceptRepo,
                                                              DiagramPendingEditRepository pendingEditRepo) {
            return new DiagramLayoutReconciler(mapper, conceptRepo, pendingEditRepo);
        }

        /** Proxied self so {@code commitLayout} runs in a transaction — see the version test for why. */
        @Bean OntologyLabelLookup ontologyLabelLookup() {
            return mock(OntologyLabelLookup.class);
        }

        @Bean DiagramServiceImpl diagramServiceImpl(
                DiagramRepository diagramRepo, OntologyMetadataRepository ontologyRepo,
                ConceptMetadataRepository conceptRepo, OntologyDetailExtractor extractor,
                JenaTDB2Repository tdb2, DiagramLayoutReconciler reconciler,
                DiagramPendingEditRepository pendingEditRepo, DiagramMapper mapper,
                OntologyLabelLookup labelLookup, @Lazy DiagramServiceImpl self) {
            return new DiagramServiceImpl(diagramRepo, ontologyRepo, conceptRepo, extractor, tdb2,
                    mock(DiagramMaterializeService.class), reconciler, pendingEditRepo, mapper, labelLookup, self);
        }
    }

    private Long ontologyId() {
        return ontologyRepo.findBySlug(SLUG).orElseThrow().getId();
    }

    /** The ontology's diagram, created on first use — every write is now addressed by diagram id. */
    private Long diagramId() {
        return diagramRepo.findByOntologyMetadataIdOrderByIdAsc(ontologyId()).stream()
                .findFirst()
                .map(DiagramEntity::getId)
                .orElseGet(() -> txTemplate.execute(tx -> {
                    DiagramEntity d = new DiagramEntity();
                    d.setOntologyMetadata(ontologyRepo.findBySlug(SLUG).orElseThrow());
                    d.setName("Test diagram");
                    return diagramRepo.saveAndFlush(d).getId();
                }));
    }
}