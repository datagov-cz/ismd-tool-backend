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
import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Edges are explicit canvas membership, exactly like nodes: a {@code diagram_edges} row means "drawn", and
 * an edge the user has not placed is not drawn even though its triple exists and both endpoints are on the
 * canvas. A projectable-but-unplaced edge is the same state as a class that exists in the ontology but has
 * not been dragged onto the canvas — it waits in the sidebar.
 *
 * <p>Two rules meet here and are asserted together:
 *
 * <ol>
 *   <li><b>Membership decides whether an edge is drawn</b> — this is what finally lets two classes sit on a
 *       canvas WITHOUT the relationship between them, which the projection-only model could not express.</li>
 *   <li><b>Projection still decides whether it CAN be drawn</b> — so an edge can never be orphaned on one
 *       end. Losing an endpoint un-draws it whatever its row says.</li>
 * </ol>
 *
 * <p>The waypoint rules live alongside membership because they share the row: {@code segments} omitted keeps
 * the stored routing, {@code []} clears it. Neither affects membership.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@Import({DiagramEdgeMembershipIntegrationTest.Beans.class, TransactionTemplateConfig.class,
        com.dia.ismdtoolbackend.config.JpaAuditingConfig.class})
@EntityScan(basePackageClasses = {OutboxEntry.class, ConceptMetadataEntity.class, DiagramEntity.class})
@EnableJpaRepositories(basePackageClasses = {OutboxEntryRepository.class, ConceptMetadataRepository.class,
        DiagramRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DiagramEdgeMembershipIntegrationTest extends PostgresIntegrationTestBase {

    private static final String GRAPH = "https://slovnik.gov.cz/em";
    private static final String SLUG = "edge-membership-ontology";
    private static final String USER = "user123";
    private static final String CLASS_A = GRAPH + "/pojem/trida-a";
    private static final String CLASS_B = GRAPH + "/pojem/trida-b";
    /** A VZTAH, live domain A → range B; its edge id is its own concept IRI. */
    private static final String REL = GRAPH + "/pojem/vztah-a-b";
    /** The composite id of the hierarchy edge A ⊑ B (EdgeProjector is package-private). */
    private static final String SUBCLASS_EDGE = "edge|SUBCLASS_OF|" + CLASS_A + "|" + CLASS_B;

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
        Model nonEmpty = ModelFactory.createDefaultModel();
        nonEmpty.add(nonEmpty.createResource(CLASS_A),
                org.apache.jena.vocabulary.RDF.type,
                nonEmpty.createResource("http://www.w3.org/2002/07/owl#Class"));
        when(tdb2.fetchGraph(any())).thenReturn(nonEmpty);
        when(extractor.applyOFNTransformations(any())).thenAnswer(inv -> inv.getArgument(0));

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
            seedConcept(saved, REL, "vztah A-B", ConceptType.VZTAH);
        });
    }

    // ---- membership -----------------------------------------------------------------------------

    /**
     * The capability this model exists to provide: both endpoint classes on the canvas, the relationship
     * live in RDF, and the edge still not drawn — because the user has not placed it. The old
     * projection-only model could not express this at all.
     */
    @Test
    void aProjectableEdgeThatWasNeverPlaced_isNotDrawn() {
        stubLiveContent(concept(CLASS_A, "Třída A"), concept(CLASS_B, "Třída B"), liveRel());

        DiagramDto read = save(List.of());

        assertThat(read.nodes())
                .as("both classes ARE on the canvas")
                .hasSize(2);
        assertThat(read.edges())
                .as("the relationship exists in RDF but was never added to the canvas")
                .isEmpty();
    }

    /** Placing the edge draws it — same payload as above, plus the membership entry. */
    @Test
    void aPlacedEdge_isDrawn() {
        stubLiveContent(concept(CLASS_A, "Třída A"), concept(CLASS_B, "Třída B"), liveRel());

        DiagramDto read = save(List.of(new DiagramLayoutDto.Edge(REL, null)));

        assertThat(read.edges())
                .extracting(DiagramDto.Edge::id)
                .containsExactly(REL);
    }

    /** A hierarchy edge is placed by its composite id, not a concept IRI — the same rule, other key shape. */
    @Test
    void aPlacedHierarchyEdge_isDrawn() {
        stubLiveContent(
                ConceptDetailModel.builder().iri(CLASS_A).name(Map.of("cs", "Třída A"))
                        .broaderClasses(List.of(CLASS_B)).build(),
                concept(CLASS_B, "Třída B"));

        DiagramDto placed = save(List.of(new DiagramLayoutDto.Edge(SUBCLASS_EDGE, null)));
        assertThat(placed.edges()).extracting(DiagramDto.Edge::id).containsExactly(SUBCLASS_EDGE);

        DiagramDto removed = save(List.of());
        assertThat(removed.edges())
                .as("and removing it from edges[] takes it off the canvas")
                .isEmpty();
    }

    /**
     * Removal without touching RDF — the user's original ask. The triple is untouched, so the edge is still
     * projectable and can be re-added later; it is simply not on this canvas.
     */
    @Test
    void omittingAPlacedEdge_removesItFromTheCanvasWithoutTouchingRdf() {
        stubLiveContent(concept(CLASS_A, "Třída A"), concept(CLASS_B, "Třída B"), liveRel());

        save(List.of(new DiagramLayoutDto.Edge(REL, null)));
        DiagramDto after = save(List.of());

        assertThat(after.edges()).isEmpty();
        assertThat(conceptRepo.findByConceptIri(REL))
                .as("removal is presentation-only: the relationship concept is untouched")
                .isPresent();

        DiagramDto readdedAfterwards = save(List.of(new DiagramLayoutDto.Edge(REL, null)));
        assertThat(readdedAfterwards.edges())
                .as("and it can be put back, because the triple never went away")
                .hasSize(1);
    }

    /**
     * A null {@code edges} is a no-op. A client that does not manage edges must not clear them by omission
     * — the failure this whole change started from was exactly a payload that silently dropped edge state.
     */
    @Test
    void nullEdges_leaveTheCanvasUntouched() {
        stubLiveContent(concept(CLASS_A, "Třída A"), concept(CLASS_B, "Třída B"), liveRel());

        save(List.of(new DiagramLayoutDto.Edge(REL, null)));
        DiagramDto after = save(null);

        assertThat(after.edges())
                .as("omitting the field says nothing about membership")
                .extracting(DiagramDto.Edge::id)
                .containsExactly(REL);
    }

    // ---- projection still governs ----------------------------------------------------------------

    /**
     * Membership can never override projection: an edge whose endpoint leaves the canvas is not drawn even
     * though its row still says "placed". This is the "never orphaned on one end" invariant — a dangling
     * edge is not renderable, so no membership statement may resurrect one.
     */
    @Test
    void aPlacedEdgeWhoseEndpointLeaves_isNotDrawn() {
        stubLiveContent(concept(CLASS_A, "Třída A"), concept(CLASS_B, "Třída B"), liveRel());
        save(List.of(new DiagramLayoutDto.Edge(REL, null)));

        // B leaves the canvas; the edge row survives untouched.
        DiagramDto after = saveWith(List.of(CLASS_A), List.of(new DiagramLayoutDto.Edge(REL, null)));

        assertThat(after.edges())
                .as("an edge missing an endpoint cannot be drawn, whatever its membership row says")
                .isEmpty();
    }

    // ---- waypoints share the row -----------------------------------------------------------------

    /**
     * The reported bug: a save that echoes an edge back WITHOUT its segments must not discard the routing.
     * The entry is a membership statement; omitting {@code segments} says nothing about geometry.
     */
    @Test
    void omittedSegments_keepTheStoredRouting() {
        stubLiveContent(concept(CLASS_A, "Třída A"), concept(CLASS_B, "Třída B"), liveRel());

        save(List.of(new DiagramLayoutDto.Edge(REL, List.of(new EdgeWaypoint(12.5, -4)))));
        DiagramDto after = save(List.of(new DiagramLayoutDto.Edge(REL, null)));

        assertThat(after.edges()).singleElement()
                .satisfies(e -> assertThat(e.segments())
                        .as("hand-drawn geometry survives a membership-only save")
                        .containsExactly(new EdgeWaypoint(12.5, -4)));
    }

    /** An explicit empty list is the deliberate clear — distinct from omitting the field. */
    @Test
    void emptySegments_clearTheRoutingButKeepTheEdge() {
        stubLiveContent(concept(CLASS_A, "Třída A"), concept(CLASS_B, "Třída B"), liveRel());

        save(List.of(new DiagramLayoutDto.Edge(REL, List.of(new EdgeWaypoint(12.5, -4)))));
        DiagramDto after = save(List.of(new DiagramLayoutDto.Edge(REL, List.of())));

        assertThat(after.edges()).singleElement()
                .satisfies(e -> {
                    assertThat(e.id()).as("still on the canvas").isEqualTo(REL);
                    assertThat(e.segments()).as("[] is an explicit clear").isNull();
                });
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private void stubLiveContent(ConceptDetailModel... concepts) {
        when(extractor.extractOntologyDetail(any())).thenReturn(
                OntologyDetailModel.builder().concepts(List.of(concepts)).build());
    }

    private ConceptDetailModel concept(String iri, String name) {
        return ConceptDetailModel.builder().iri(iri).name(Map.of("cs", name)).build();
    }

    /** The live VZTAH: domain A, range B — projectable as an edge while both classes are on canvas. */
    private ConceptDetailModel liveRel() {
        return ConceptDetailModel.builder()
                .iri(REL).name(Map.of("cs", "vztah A-B")).domain(CLASS_A).range(CLASS_B).build();
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

    private DiagramLayoutDto.Node node(String iri, double x) {
        return new DiagramLayoutDto.Node(DiagramMapper.NODE_ID_PREFIX + iri,
                new PositionDto(x, 0.0), null, false, List.of());
    }

    /** Save with both classes on the canvas and the given edge membership. */
    private DiagramDto save(List<DiagramLayoutDto.Edge> edges) {
        return saveWith(List.of(CLASS_A, CLASS_B), edges);
    }

    private DiagramDto saveWith(List<String> canvasClasses, List<DiagramLayoutDto.Edge> edges) {
        List<DiagramLayoutDto.Node> nodes = new java.util.ArrayList<>();
        double x = 0;
        for (String iri : canvasClasses) {
            nodes.add(node(iri, x));
            x += 100;
        }
        return diagramService.saveLayout(SLUG, diagramId(),
                new DiagramLayoutDto(storedVersion(), null, nodes, edges, null));
    }

    private Long storedVersion() {
        return diagramRepo.findByOntologyMetadataIdOrderByIdAsc(ontologyId()).stream()
                .findFirst().map(DiagramEntity::getVersion).orElse(null);
    }

    private Long ontologyId() {
        return ontologyRepo.findBySlug(SLUG).orElseThrow().getId();
    }

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
}
