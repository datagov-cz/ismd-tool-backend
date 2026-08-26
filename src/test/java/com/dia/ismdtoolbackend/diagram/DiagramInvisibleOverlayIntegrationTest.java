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
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.impl.DiagramLayoutReconciler;
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
 * still staged, still counted in {@code pendingChangeCount}, with nothing on the canvas to explain it.
 *
 * <p>This is the invariant a single read/write DTO would depend on: "every staged overlay is visible in the
 * read it belongs to". These tests establish whether it currently holds. Unlike
 * {@link DiagramOverlayVersionIntegrationTest}, live content is stubbed with real concepts so edges actually
 * project — an empty graph would make every edge absent for the wrong reason.
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
        return diagramRepo.findByOntologyMetadataSlug(SLUG).map(DiagramEntity::getVersion).orElse(null);
    }

    /** Save with exactly these classes on the canvas, carrying these overlays. */
    private DiagramDto save(List<String> canvasClasses, DiagramLayoutDto.Overlay... overlays) {
        List<DiagramLayoutDto.Node> nodes = new java.util.ArrayList<>();
        double x = 0;
        for (String iri : canvasClasses) {
            nodes.add(node(iri, x, 0));
            x += 100;
        }
        return diagramService.saveLayout(SLUG, new DiagramLayoutDto(
                storedVersion(), null, nodes, List.of(),
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
        return diagramService.saveLayout(SLUG, new DiagramLayoutDto(
                storedVersion(), null, nodes, List.of(),
                overlays.length == 0 ? null : List.of(overlays)));
    }

    private DiagramDto.Edge edgeFor(DiagramDto diagram, String conceptIri) {
        return diagram.edges().stream()
                .filter(e -> conceptIri.equals(e.id()))
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
        assertThat(staged.pendingChangeCount()).isEqualTo(1);

        // The user removes C from the canvas. ReactFlow drops the edge; the FE sends neither C nor overlays.
        DiagramDto after = save(List.of(CLASS_A, CLASS_B));

        assertThat(edgeFor(after, REL))
                .as("C is off-canvas so the edge is no longer projected")
                .isNull();
        assertThat(after.pendingChangeCount())
                .as("the staged change survives removing C — the overlay lives on REL's row, not on the edge")
                .isEqualTo(1);

        // NOT invisible: the overlay-carrying row is emitted as a node in its own right, so the read does
        // still carry the staged edit even though no edge is drawn for it.
        DiagramDto.Node relNode = after.nodes().stream()
                .filter(n -> (DiagramMapper.NODE_ID_PREFIX + REL).equals(n.id()))
                .findFirst().orElse(null);
        assertThat(relNode)
                .as("the read emits the VZTAH's overlay-carrying row as a node, so the overlay is reachable")
                .isNotNull();
        assertThat(relNode.data().pendingEdit().getRange()).isEqualTo(CLASS_C);
        assertThat(relNode.data().hasPendingEdits()).isTrue();
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
        assertThat(after.pendingChangeCount()).isEqualTo(1);

        // Same as the VZTAH: the overlay-carrying row is emitted as a node, so it is still reachable.
        DiagramDto.Node propNode = after.nodes().stream()
                .filter(n -> (DiagramMapper.NODE_ID_PREFIX + PROP).equals(n.id()))
                .findFirst().orElse(null);
        assertThat(propNode)
                .as("the read emits the VLASTNOST's overlay-carrying row as a node")
                .isNotNull();
        assertThat(propNode.data().pendingEdit().getDomain()).isEqualTo(CLASS_C);
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
        assertThat(after.pendingChangeCount()).isEqualTo(1);
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
                                                              ConceptMetadataRepository conceptRepo) {
            return new DiagramLayoutReconciler(mapper, conceptRepo);
        }

        /** Proxied self so {@code commitLayout} runs in a transaction — see the version test for why. */
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