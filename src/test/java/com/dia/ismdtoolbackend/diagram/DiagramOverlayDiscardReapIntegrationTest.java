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
 * Removing a VLASTNOST from the canvas is a LAYOUT change — the concept still exists, it is just no longer
 * drawn. The reap in {@link DiagramLayoutReconciler#reconcileNodes} is what enacts that removal, and its
 * carve-out keys on {@code overlayTargets}: the IRIs the payload <em>addressed</em>, not the IRIs that
 * ended up carrying an overlay.
 *
 * <p>A contentless {@code overlays[]} entry — {@code {"conceptIri": ...}} with no edit fields — is a
 * DISCARD. It addresses the concept, clears the overlay, and leaves the row bare. Because the IRI is in
 * {@code overlayTargets} regardless, the reap skips a row that no longer has anything to protect, so the
 * property survives a save that omitted it from {@code nodes[]} and comes back on the next GET as an
 * orphaned {@code propertyNode} anchored at the origin.
 *
 * <p>Harness mirrors {@link DiagramInvisibleOverlayIntegrationTest}: live content is stubbed with real
 * domain/range so property rows actually project.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@Import({DiagramOverlayDiscardReapIntegrationTest.Beans.class, TransactionTemplateConfig.class,
        com.dia.ismdtoolbackend.config.JpaAuditingConfig.class})
@EntityScan(basePackageClasses = {OutboxEntry.class, ConceptMetadataEntity.class, DiagramEntity.class})
@EnableJpaRepositories(basePackageClasses = {OutboxEntryRepository.class, ConceptMetadataRepository.class,
        DiagramRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DiagramOverlayDiscardReapIntegrationTest extends PostgresIntegrationTestBase {

    private static final String GRAPH = "https://slovnik.gov.cz/vg";
    private static final String SLUG = "overlay-discard-ontology";
    private static final String USER = "user123";
    private static final String CLASS_A = GRAPH + "/pojem/trida-a";
    private static final String CLASS_B = GRAPH + "/pojem/trida-b";
    /** A VLASTNOST whose live domain is A — renders as a row inside A, never as a node. */
    private static final String PROP = GRAPH + "/pojem/vlastnost-a";

    @Autowired private ConceptMetadataRepository conceptRepo;
    @Autowired private OntologyMetadataRepository ontologyRepo;
    @Autowired private DiagramRepository diagramRepo;
    @Autowired private DiagramNodeRepository nodeRepo;
    @Autowired private DiagramPendingEditRepository pendingEditRepo;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private DiagramServiceImpl diagramService;
    @Autowired private JenaTDB2Repository tdb2;
    @Autowired private OntologyDetailExtractor extractor;

    @BeforeEach
    void setUp() {
        reset(tdb2, extractor);
        Model nonEmpty = ModelFactory.createDefaultModel();
        nonEmpty.add(nonEmpty.createResource(CLASS_A),
                org.apache.jena.vocabulary.RDF.type,
                nonEmpty.createResource("http://www.w3.org/2002/07/owl#Class"));
        when(tdb2.fetchGraph(any())).thenReturn(nonEmpty);
        when(extractor.applyOFNTransformations(any())).thenAnswer(inv -> inv.getArgument(0));
        stubLiveContent(concept(CLASS_A, "Třída A"), concept(CLASS_B, "Třída B"), liveProp());

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
            seedConcept(saved, PROP, "vlastnost A", ConceptType.VLASTNOST);
        });
    }

    // ---- the bug --------------------------------------------------------------------------------

    /**
     * The exact shape of the reported payload: the property is omitted from {@code nodes[]} (removed from
     * the canvas) while {@code overlays[]} carries a contentless entry for it (discard the staged edit).
     * The two instructions are independent; neither vetoes the other.
     */
    @Test
    void discardingAnOverlayOnAPropertyOmittedFromNodes_removesTheStagedEdit() {
        // Stage a domain overlay on the property, then discard it in the same save that drops the
        // property from the canvas.
        save(List.of(CLASS_A, CLASS_B), overlayWithDomain(PROP, CLASS_B));
        assertThat(isStaged(PROP)).as("staging records an edit for the property").isTrue();
        assertThat(rowExists(PROP)).as("and adds no layout row — a VLASTNOST is not a canvas node").isFalse();

        DiagramDto after = save(List.of(CLASS_A, CLASS_B), discard(PROP));

        assertThat(isStaged(PROP))
                .as("a contentless entry deletes the staged edit rather than blanking it")
                .isFalse();
        assertThat(nodeIds(after))
                .as("and nothing comes back as an orphaned propertyNode on the next read")
                .doesNotContain(DiagramMapper.NODE_ID_PREFIX + PROP);
        assertThat(after.pendingEdits()).isEmpty();
    }

    /**
     * The same discard on a property that was never staged at all. The {@code node == null} branch already
     * skips this case, so it is the control proving the failure above is the pre-existing-row path.
     */
    @Test
    void discardingAnOverlayOnAPropertyWithNoRow_provisionsNothing() {
        save(List.of(CLASS_A, CLASS_B));

        DiagramDto after = save(List.of(CLASS_A, CLASS_B), discard(PROP));

        assertThat(rowExists(PROP)).as("a discard on a concept with no row is a no-op").isFalse();
        assertThat(nodeIds(after)).doesNotContain(DiagramMapper.NODE_ID_PREFIX + PROP);
    }

    // ---- the FE report: a plain class node omitted from nodes[] ---------------------------------

    /**
     * The second report from FE integration: no overlays anywhere, save N classes, then save N-1. Canvas
     * membership is a full replace, so the omitted class must be gone from both the row set and the read.
     */
    @Test
    void aClassOmittedFromNodes_isReapedAndAbsentFromTheRead() {
        save(List.of(CLASS_A, CLASS_B));
        assertThat(rowExists(CLASS_B)).isTrue();

        DiagramDto after = save(List.of(CLASS_A));

        assertThat(rowExists(CLASS_B))
                .as("membership is a full replace — an omitted class is reaped")
                .isFalse();
        assertThat(nodeIds(after))
                .as("the write's own response must not echo back the removed class")
                .doesNotContain(DiagramMapper.NODE_ID_PREFIX + CLASS_B);
    }

    /** The same removal observed through a fresh GET, in case the write response and the read diverge. */
    @Test
    void aClassOmittedFromNodes_isAbsentFromASubsequentGet() {
        save(List.of(CLASS_A, CLASS_B));
        save(List.of(CLASS_A));

        DiagramDto fetched = diagramService.getDiagram(SLUG, diagramId());

        assertThat(nodeIds(fetched))
                .as("a later GET must not resurrect the removed class")
                .doesNotContain(DiagramMapper.NODE_ID_PREFIX + CLASS_B);
    }

    // ---- curated property rows -------------------------------------------------------------------

    /**
     * The core of the curation model: the canvas is a SUBSET of the ontology. A class placed for the first
     * time renders no property rows even though its VLASTNOST is live and domiciled on it — the user has
     * not placed it yet.
     */
    @Test
    void aClassPlacedForTheFirstTime_rendersNoPropertyRows() {
        DiagramDto after = save(List.of(CLASS_A, CLASS_B));

        assertThat(propertyIris(after, CLASS_A))
                .as("strict curation — a live property is not auto-populated onto a newly placed class")
                .isEmpty();
    }

    /** Placing the property is a deliberate act; it round-trips through the write response and a GET. */
    @Test
    void placingAPropertyOnItsClass_rendersItAndRoundTrips() {
        DiagramDto after = saveWithProperties(Map.of(CLASS_A, List.of(PROP)), CLASS_A, CLASS_B);

        assertThat(propertyIris(after, CLASS_A))
                .as("a placed property renders as a row in its class cell")
                .containsExactly(PROP);
        assertThat(propertyIris(diagramService.getDiagram(SLUG, diagramId()), CLASS_A))
                .as("and survives a fresh GET")
                .containsExactly(PROP);
    }

    /**
     * The scenario that decided visible-set over hidden-complement: a property created in the form page
     * after the diagram was curated must NOT appear on the canvas on its own.
     */
    @Test
    void aPropertyCreatedAfterCuration_staysOffTheCanvas() {
        saveWithProperties(Map.of(CLASS_A, List.of(PROP)), CLASS_A, CLASS_B);

        // A second VLASTNOST appears in the ontology, domiciled on A — created in the form page, never placed.
        String created = GRAPH + "/pojem/vlastnost-nova";
        txTemplate.executeWithoutResult(tx -> seedConcept(
                ontologyRepo.findBySlug(SLUG).orElseThrow(), created, "nová vlastnost", ConceptType.VLASTNOST));
        stubLiveContent(concept(CLASS_A, "Třída A"), concept(CLASS_B, "Třída B"), liveProp(),
                ConceptDetailModel.builder().iri(created).name(Map.of("cs", "nová vlastnost"))
                        .domain(CLASS_A).build());

        assertThat(propertyIris(diagramService.getDiagram(SLUG, diagramId()), CLASS_A))
                .as("a property created elsewhere stays off the canvas until placed")
                .containsExactly(PROP);
    }

    /** Removing a property from the array is a layout act — the concept itself is untouched. */
    @Test
    void removingAPlacedProperty_isLayoutOnlyAndLeavesTheConcept() {
        saveWithProperties(Map.of(CLASS_A, List.of(PROP)), CLASS_A, CLASS_B);

        DiagramDto after = saveWithProperties(Map.of(), CLASS_A, CLASS_B);

        assertThat(propertyIris(after, CLASS_A))
                .as("full replace — an omitted property stops rendering")
                .isEmpty();
        assertThat(conceptRepo.findByConceptIri(PROP))
                .as("the concept still exists; only the canvas changed")
                .isPresent();
        assertThat(after.pendingEdits().size())
                .as("visibility is layout-only and never counts as staged work")
                .isZero();
    }

    /**
     * Option (b): hiding a property that carries a staged overlay removes the ROW but must not hide the
     * staged work — it moves to {@code pendingEdits[]}, so nothing goes invisible.
     */
    @Test
    void hidingAPropertyThatCarriesAnOverlay_keepsTheOverlayReachable() {
        saveWithProperties(Map.of(CLASS_A, List.of(PROP)), CLASS_A, CLASS_B);
        save(List.of(CLASS_A, CLASS_B), overlayWithDomain(PROP, CLASS_A));

        DiagramDto after = saveWithProperties(Map.of(), CLASS_A, CLASS_B);

        assertThat(propertyIris(after, CLASS_A)).as("the row is gone from the class cell").isEmpty();
        assertThat(nodeIds(after))
                .as("a VLASTNOST is never a canvas node")
                .doesNotContain(DiagramMapper.NODE_ID_PREFIX + PROP);
        assertThat(after.pendingEdits())
                .as("the staged edit stays reachable through pendingEdits[]")
                .extracting(DiagramDto.PendingEditEntry::iri)
                .contains(PROP);
        assertThat(after.pendingEdits().size())
                .as("hiding does not discard the staged edit")
                .isEqualTo(1);
    }

    // ---- the invariant the split protects --------------------------------------------------------

    /**
     * A REAL overlay on a concept absent from {@code nodes[]} survives a Save: a property never travels in
     * {@code nodes[]}, and layout writes cannot reach staged work. Only an explicit contentless entry
     * removes it.
     */
    @Test
    void aRealOverlayOnAPropertyAbsentFromNodes_survivesTheSave() {
        DiagramDto after = save(List.of(CLASS_A, CLASS_B), overlayWithDomain(PROP, CLASS_B));

        assertThat(isStaged(PROP))
                .as("a staged edit is never removed merely because its concept is absent from nodes[]")
                .isTrue();
        assertThat(after.pendingEdits()).hasSize(1);
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private void stubLiveContent(ConceptDetailModel... concepts) {
        when(extractor.extractOntologyDetail(any())).thenReturn(
                OntologyDetailModel.builder().concepts(List.of(concepts)).build());
    }

    private ConceptDetailModel concept(String iri, String name) {
        return ConceptDetailModel.builder().iri(iri).name(Map.of("cs", name)).build();
    }

    /** The live VLASTNOST: domain A — projects as a row inside A while A is on canvas. */
    private ConceptDetailModel liveProp() {
        return ConceptDetailModel.builder()
                .iri(PROP).name(Map.of("cs", "vlastnost A")).domain(CLASS_A).build();
    }

    /** A contentless overlay entry — every edit field null. This is a DISCARD. */
    private DiagramLayoutDto.Overlay discard(String conceptIri) {
        return new DiagramLayoutDto.Overlay(DiagramMapper.NODE_ID_PREFIX + conceptIri,
                null, null, null, null, null);
    }

    private DiagramLayoutDto.Overlay overlayWithDomain(String conceptIri, String domain) {
        return new DiagramLayoutDto.Overlay(DiagramMapper.NODE_ID_PREFIX + conceptIri,
                domain, null, null, null, null);
    }

    /** Read inside a transaction — the class runs NOT_SUPPORTED, so {@code nodes} is lazy out here. */
    private boolean rowExists(String conceptIri) {
        return Boolean.TRUE.equals(txTemplate.execute(tx -> diagramRepo.findByOntologyMetadataIdOrderByIdAsc(ontologyId()).stream().findFirst()
                .map(d -> d.getNodes().stream().anyMatch(n -> conceptIri.equals(n.getConceptIri())))
                .orElse(false)));
    }

    /** Whether the concept currently carries a staged edit. */
    private boolean isStaged(String conceptIri) {
        return Boolean.TRUE.equals(txTemplate.execute(tx -> pendingEditRepo
                .findByDiagramIdAndConceptIri(diagramId(), conceptIri)
                .isPresent()));
    }

    private List<String> nodeIds(DiagramDto diagram) {
        return diagram.nodes().stream().map(DiagramDto.Node::id).toList();
    }

    /** The property-row IRIs rendered inside one class node. */
    private List<String> propertyIris(DiagramDto diagram, String classIri) {
        return diagram.nodes().stream()
                .filter(n -> (DiagramMapper.NODE_ID_PREFIX + classIri).equals(n.id()))
                .findFirst()
                .map(n -> n.data().properties().stream().map(DiagramDto.PropertyRow::iri).toList())
                .orElseThrow(() -> new AssertionError("class not on canvas: " + classIri));
    }

    /** Save the given classes, each rendering exactly the property IRIs listed for it. */
    private DiagramDto saveWithProperties(Map<String, List<String>> propertiesByClass, String... classes) {
        List<DiagramLayoutDto.Node> nodes = new ArrayList<>();
        double x = 0;
        for (String iri : classes) {
            nodes.add(new DiagramLayoutDto.Node(DiagramMapper.NODE_ID_PREFIX + iri,
                    new PositionDto(x, 0.0), null, false,
                    propertiesByClass.getOrDefault(iri, List.of())));
            x += 100;
        }
        return diagramService.saveLayout(SLUG, diagramId(), new DiagramLayoutDto(
                storedVersion(), null, nodes, List.of(), null));
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
        return new DiagramLayoutDto.Node(DiagramMapper.NODE_ID_PREFIX + iri, new PositionDto(x, y),
                null, false, List.of());
    }

    private Long storedVersion() {
        return diagramRepo.findByOntologyMetadataIdOrderByIdAsc(ontologyId()).stream().findFirst().map(DiagramEntity::getVersion).orElse(null);
    }

    /** Save with exactly these classes on the canvas, carrying these overlays. */
    private DiagramDto save(List<String> canvasClasses, DiagramLayoutDto.Overlay... overlays) {
        List<DiagramLayoutDto.Node> nodes = new ArrayList<>();
        double x = 0;
        for (String iri : canvasClasses) {
            nodes.add(node(iri, x, 0));
            x += 100;
        }
        return diagramService.saveLayout(SLUG, diagramId(), new DiagramLayoutDto(
                storedVersion(), null, nodes, List.of(),
                overlays.length == 0 ? null : List.of(overlays)));
    }

    @TestConfiguration
    static class Beans {

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
                                                              ConceptMetadataRepository conceptRepo,
                                                              DiagramPendingEditRepository pendingEditRepo) {
            return new DiagramLayoutReconciler(mapper, conceptRepo, pendingEditRepo);
        }

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