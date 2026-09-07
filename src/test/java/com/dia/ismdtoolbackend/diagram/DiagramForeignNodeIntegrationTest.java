package com.dia.ismdtoolbackend.diagram;

import com.dia.ismdtoolbackend.outbox.TransactionTemplateConfig;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.PositionDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.outbox.OutboxEntry;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.outbox.PostgresIntegrationTestBase;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.outbox.OutboxEntryRepository;
import com.dia.ismdtoolbackend.service.impl.DiagramLayoutReconciler;
import com.dia.ismdtoolbackend.service.impl.DiagramMaterializeService;
import com.dia.ismdtoolbackend.service.impl.DiagramServiceImpl;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Foreign concepts on the canvas: a concept from ANOTHER ontology, placed for context and rendered
 * read-only, so the user can draw a relationship from their own concept to it.
 *
 * <p>Two guarantees are under test, and they pull in opposite directions:
 *
 * <ol>
 *   <li><b>A foreign node renders like a real one</b> — its label and type come from its OWN graph, which
 *       the read fetches in addition to the diagram's. Without that it is indistinguishable from a
 *       concept deleted underneath the canvas.</li>
 *   <li><b>A foreign concept is referenced, never written.</b> The flag exempts node PLACEMENT from the
 *       graph guard and nothing else; an overlay targeting a foreign concept is still refused. That is
 *       what keeps the cross-tenant write fix intact, and it is the assertion that matters most here.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@Import({DiagramForeignNodeIntegrationTest.Beans.class, TransactionTemplateConfig.class,
        com.dia.ismdtoolbackend.config.JpaAuditingConfig.class})
@EntityScan(basePackageClasses = {OutboxEntry.class, ConceptMetadataEntity.class, DiagramEntity.class})
@EnableJpaRepositories(basePackageClasses = {OutboxEntryRepository.class, ConceptMetadataRepository.class,
        DiagramRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DiagramForeignNodeIntegrationTest extends PostgresIntegrationTestBase {

    private static final String GRAPH = "https://slovnik.gov.cz/mine";
    private static final String SLUG = "foreign-node-ontology";
    private static final String USER = "user123";
    private static final String MY_CLASS = GRAPH + "/pojem/zamestnanec";

    /** Another ontology entirely — owned by someone else, never written by this diagram. */
    private static final String OTHER_GRAPH = "https://slovnik.gov.cz/theirs";
    private static final String OTHER_SLUG = "other-ontology";
    private static final String OTHER_USER = "someone-else-999";
    private static final String FOREIGN_CLASS = OTHER_GRAPH + "/pojem/osoba";
    /** A second concept of that same foreign ontology, so a link between two foreign nodes is testable. */
    private static final String FOREIGN_PARENT = OTHER_GRAPH + "/pojem/subjekt";

    /** An NKD IRI: external, published, and with no PG row of its own. */
    private static final String NKD_CLASS = "https://slovnik.gov.cz/legislativni/sbirka/pojem/osoba";

    @Autowired private ConceptMetadataRepository conceptRepo;
    @Autowired private OntologyMetadataRepository ontologyRepo;
    @Autowired private DiagramRepository diagramRepo;
    @Autowired private DiagramPendingEditRepository pendingEditRepo;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private DiagramServiceImpl diagramService;
    @Autowired private JenaTDB2Repository tdb2;
    @Autowired private OntologyDetailExtractor extractor;

    @BeforeEach
    void setUp() {
        reset(tdb2, extractor);
        when(tdb2.fetchGraph(any())).thenReturn(nonEmptyModel());
        when(extractor.applyOFNTransformations(any())).thenAnswer(inv -> inv.getArgument(0));

        txTemplate.executeWithoutResult(tx -> {
            pendingEditRepo.deleteAllInBatch();
            diagramRepo.deleteAllInBatch();
            conceptRepo.deleteAllInBatch();
            ontologyRepo.deleteAllInBatch();
        });
        txTemplate.executeWithoutResult(tx -> {
            OntologyMetadataEntity mine = ontology(SLUG, GRAPH, USER);
            seedConcept(mine, MY_CLASS, "Zaměstnanec", ConceptType.TRIDA, GRAPH);
            OntologyMetadataEntity theirs = ontology(OTHER_SLUG, OTHER_GRAPH, OTHER_USER);
            seedConcept(theirs, FOREIGN_CLASS, "Osoba", ConceptType.TRIDA, OTHER_GRAPH);
            seedConcept(theirs, FOREIGN_PARENT, "Subjekt", ConceptType.TRIDA, OTHER_GRAPH);
        });
    }

    // ---- rendering ------------------------------------------------------------------------------

    /**
     * The point of the whole feature: a foreign node carries its real label, fetched from the graph that
     * owns it. Before the foreign-graph read existed it came back label-less and {@code stale}, i.e.
     * identical to a concept someone had deleted.
     */
    @Test
    void foreignNode_rendersWithItsOwnGraphsLabel() {
        stubGraph(GRAPH, concept(MY_CLASS, "Zaměstnanec"));
        stubGraph(OTHER_GRAPH, concept(FOREIGN_CLASS, "Osoba"));

        DiagramDto read = save(node(MY_CLASS, 0, 0, false), node(FOREIGN_CLASS, 300, 0, true));

        DiagramDto.Node foreign = nodeFor(read, FOREIGN_CLASS);
        assertThat(foreign.data().label())
                .as("label comes from the foreign concept's OWN graph, not this diagram's")
                .containsEntry("cs", "Osoba");
        assertThat(foreign.data().stale())
                .as("a foreign concept that exists is not stale — that state means 'deleted'")
                .isFalse();
        assertThat(foreign.data().readOnly())
                .as("the FE must render it non-editable")
                .isTrue();
    }

    /**
     * A link BETWEEN two foreign concepts is not drawn. Every edge the canvas renders is asserted from a
     * concept this ontology owns; a foreign concept's own {@code rdfs:subClassOf} belongs to the graph
     * that owns it, and drawing it here would present another ontology's structure as this diagram's —
     * an edge no overlay can ever stage, unstage or reroute, because the overlay guard refuses a foreign
     * subject.
     */
    @Test
    void hierarchyBetweenTwoForeignConcepts_isNotProjected() {
        stubGraph(GRAPH, concept(MY_CLASS, "Zaměstnanec"));
        stubGraph(OTHER_GRAPH,
                subClassOf(concept(FOREIGN_CLASS, "Osoba"), FOREIGN_PARENT),
                concept(FOREIGN_PARENT, "Subjekt"));

        DiagramDto read = save(node(MY_CLASS, 0, 0, false),
                node(FOREIGN_CLASS, 300, 0, true),
                node(FOREIGN_PARENT, 600, 0, true));

        assertThat(read.edges())
                .as("the foreign graph's own hierarchy is not this diagram's to draw")
                .isEmpty();
    }

    /**
     * The converse, so the rule above is a scope limit and not a blanket suppression: a link asserted
     * FROM an owned concept TO a foreign one is exactly what the feature exists to draw.
     */
    @Test
    void hierarchyFromAnOwnedConceptToAForeignOne_isProjected() {
        stubGraph(GRAPH, subClassOf(concept(MY_CLASS, "Zaměstnanec"), FOREIGN_CLASS));
        stubGraph(OTHER_GRAPH, concept(FOREIGN_CLASS, "Osoba"));

        DiagramDto read = save(node(MY_CLASS, 0, 0, false), node(FOREIGN_CLASS, 300, 0, true));

        assertThat(read.edges())
                .as("our concept's own subClassOf to a foreign target still draws")
                .hasSize(1);
        assertThat(read.edges().get(0).source()).isEqualTo(DiagramMapper.NODE_ID_PREFIX + MY_CLASS);
        assertThat(read.edges().get(0).target()).isEqualTo(DiagramMapper.NODE_ID_PREFIX + FOREIGN_CLASS);
    }

    /** An own-ontology node is unaffected: not read-only, and still read from its own graph. */
    @Test
    void ownNode_isNotMarkedReadOnly() {
        stubGraph(GRAPH, concept(MY_CLASS, "Zaměstnanec"));

        DiagramDto read = save(node(MY_CLASS, 0, 0, false));

        assertThat(nodeFor(read, MY_CLASS).data().readOnly()).isFalse();
    }

    /**
     * An NKD IRI has no PG row and no local graph. It must still be placeable and still render as a
     * node — the alternative is that the user cannot reference published NKD concepts at all.
     */
    @Test
    void nkdIri_withNoPgRow_isAcceptedAsForeign() {
        stubGraph(GRAPH, concept(MY_CLASS, "Zaměstnanec"));

        DiagramDto read = save(node(MY_CLASS, 0, 0, false), node(NKD_CLASS, 300, 0, true));

        assertThat(nodeFor(read, NKD_CLASS)).isNotNull();
        assertThat(nodeFor(read, NKD_CLASS).data().readOnly()).isTrue();
    }

    /**
     * A foreign graph that cannot be read must not take the whole canvas down with it. The diagram's own
     * content is what the response exists to deliver.
     */
    @Test
    void unreadableForeignGraph_degradesToStale_ratherThanFailingTheRead() {
        stubGraph(GRAPH, concept(MY_CLASS, "Zaměstnanec"));
        // Stub the foreign graph to a WORKING baseline first, then break it: without this the node
        // would render stale merely because the fixture never stubbed OTHER_GRAPH, and the assertion
        // below would hold with the throw removed.
        stubGraph(OTHER_GRAPH, concept(FOREIGN_CLASS, "Osoba"));
        when(tdb2.fetchGraph(eq(OTHER_GRAPH))).thenThrow(new RuntimeException("Fuseki down"));

        DiagramDto read = save(node(MY_CLASS, 0, 0, false), node(FOREIGN_CLASS, 300, 0, true));

        assertThat(nodeFor(read, MY_CLASS).data().label()).containsEntry("cs", "Zaměstnanec");
        assertThat(nodeFor(read, FOREIGN_CLASS).data().stale())
                .as("the foreign node degrades, the canvas still renders")
                .isTrue();
    }

    // ---- the guard ------------------------------------------------------------------------------

    /**
     * The flag exempts PLACEMENT only. An overlay targeting a foreign concept is still refused, because
     * materializing it would write another ontology's RDF — the cross-tenant defect this guard exists to
     * prevent. This is the most important assertion in the class.
     */
    @Test
    void overlayTargetingAForeignConcept_isStillRejected() {
        stubGraph(GRAPH, concept(MY_CLASS, "Zaměstnanec"));

        DiagramLayoutDto layout = new DiagramLayoutDto(
                storedVersion(), null,
                List.of(node(MY_CLASS, 0, 0, false), node(FOREIGN_CLASS, 300, 0, true)),
                List.of(),
                List.of(new DiagramLayoutDto.Overlay(
                        FOREIGN_CLASS, MY_CLASS, null, null, null, null)));

        assertThatThrownBy(() -> diagramService.saveLayout(SLUG, diagramId(), layout))
                .as("placing a foreign concept is allowed; staging an edit ON one never is")
                .isInstanceOf(ConceptValidationException.class);

        assertThat(pendingEditRepo.findByDiagramId(diagramId()))
                .as("and nothing is staged")
                .isEmpty();
    }

    /** Without the flag the old guard still applies — a foreign IRI is a plain 400. */
    @Test
    void foreignIriWithoutTheFlag_isStillRejected() {
        stubGraph(GRAPH, concept(MY_CLASS, "Zaměstnanec"));

        assertThatThrownBy(() -> save(node(MY_CLASS, 0, 0, false), node(FOREIGN_CLASS, 300, 0, false)))
                .isInstanceOf(ConceptValidationException.class);
    }

    /**
     * The flag is a claim about the concept, and a false claim is a bug worth surfacing: marking an
     * own-ontology concept foreign would render it read-only and silently un-editable.
     */
    @Test
    void ownConceptFlaggedForeign_isRejected() {
        stubGraph(GRAPH, concept(MY_CLASS, "Zaměstnanec"));

        assertThatThrownBy(() -> save(node(MY_CLASS, 0, 0, true)))
                .isInstanceOf(ConceptValidationException.class);
    }

    // ---- fixtures -------------------------------------------------------------------------------

    /** Route each graph's fetch to its own concept set, so a foreign read is genuinely a second fetch. */
    private void stubGraph(String graphName, ConceptDetailModel... concepts) {
        Model marker = ModelFactory.createDefaultModel();
        marker.add(marker.createResource(graphName + "#marker"),
                org.apache.jena.vocabulary.RDF.type,
                marker.createResource("http://www.w3.org/2002/07/owl#Ontology"));
        when(tdb2.fetchGraph(eq(graphName))).thenReturn(marker);
        when(extractor.extractOntologyDetail(eq(marker))).thenReturn(
                OntologyDetailModel.builder().concepts(List.of(concepts)).build());
    }

    private Model nonEmptyModel() {
        Model m = ModelFactory.createDefaultModel();
        m.add(m.createResource(GRAPH + "#marker"),
                org.apache.jena.vocabulary.RDF.type,
                m.createResource("http://www.w3.org/2002/07/owl#Ontology"));
        return m;
    }

    private ConceptDetailModel concept(String iri, String name) {
        return ConceptDetailModel.builder().iri(iri).name(Map.of("cs", name)).build();
    }

    /** The same concept, carrying an {@code rdfs:subClassOf} to {@code broader}. */
    private ConceptDetailModel subClassOf(ConceptDetailModel c, String broader) {
        c.setBroaderClasses(List.of(broader));
        return c;
    }

    private DiagramLayoutDto.Node node(String iri, double x, double y, boolean foreign) {
        return new DiagramLayoutDto.Node(DiagramMapper.NODE_ID_PREFIX + iri,
                new PositionDto(x, y), null, false, List.of(), foreign);
    }

    /**
     * Save with every hierarchy edge these fixtures could project already placed on the canvas, so the
     * assertions test PROJECTION (is this edge ours to draw?) and not membership. Placing an edge that
     * never projects is harmless — the row simply matches nothing — but omitting one would make
     * "not drawn" vacuously true and hide a projection regression.
     */
    /** The projector's composite key for a hierarchy edge; EdgeProjector itself is package-private. */
    private static String subclassEdgeId(String source, String target) {
        return "edge|SUBCLASS_OF|" + source + "|" + target;
    }

    private DiagramDto save(DiagramLayoutDto.Node... nodes) {
        List<DiagramLayoutDto.Edge> placed = List.of(
                new DiagramLayoutDto.Edge(subclassEdgeId(MY_CLASS, FOREIGN_CLASS), null),
                new DiagramLayoutDto.Edge(subclassEdgeId(FOREIGN_CLASS, MY_CLASS), null),
                new DiagramLayoutDto.Edge(subclassEdgeId(FOREIGN_CLASS, FOREIGN_PARENT), null));
        return diagramService.saveLayout(SLUG, diagramId(),
                new DiagramLayoutDto(storedVersion(), null, List.of(nodes), placed, null));
    }

    private DiagramDto.Node nodeFor(DiagramDto diagram, String conceptIri) {
        return diagram.nodes().stream()
                .filter(n -> conceptIri.equals(n.data().iri()))
                .findFirst()
                .orElse(null);
    }

    private OntologyMetadataEntity ontology(String slug, String graphName, String userId) {
        OntologyMetadataEntity o = new OntologyMetadataEntity();
        o.setSlug(slug);
        o.setGraphName(graphName);
        o.setUserId(userId);
        o.setIsPublished(false);
        o.setCreatedAt(LocalDateTime.now());
        return ontologyRepo.save(o);
    }

    private void seedConcept(OntologyMetadataEntity ontology, String iri, String name,
                             ConceptType type, String graphName) {
        ConceptMetadataEntity c = new ConceptMetadataEntity();
        c.setConceptIri(iri);
        c.setConceptName(name);
        c.setConceptType(type);
        c.setGraphName(graphName);
        c.setUserId(ontology.getUserId());
        c.setOntologyMetadata(ontology);
        c.setSlug(iri.substring(iri.lastIndexOf('/') + 1));
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        conceptRepo.save(c);
    }

    private Long ontologyId() {
        return ontologyRepo.findBySlug(SLUG).orElseThrow().getId();
    }

    private Long storedVersion() {
        return diagramRepo.findById(diagramId()).map(DiagramEntity::getVersion).orElse(null);
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

        @Bean DiagramServiceImpl diagramServiceImpl(
                DiagramRepository diagramRepo, OntologyMetadataRepository ontologyRepo,
                ConceptMetadataRepository conceptRepo, OntologyDetailExtractor extractor,
                JenaTDB2Repository tdb2, DiagramLayoutReconciler reconciler,
                DiagramPendingEditRepository pendingEditRepo, DiagramMapper mapper,
                @Lazy DiagramServiceImpl self) {
            return new DiagramServiceImpl(diagramRepo, ontologyRepo, conceptRepo, extractor, tdb2,
                    mock(DiagramMaterializeService.class), reconciler, pendingEditRepo, mapper, self);
        }
    }
}
