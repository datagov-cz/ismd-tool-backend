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
 *   <li><b>A foreign concept is referenced, never written.</b> Placement is free and foreignness is
 *       derived from the concept's graph, never declared by the client. What stays guarded is every
 *       endpoint the edit WRITES — the overlay's subject, its {@code domain}, op 6's
 *       {@code addBroaderOn} — while the endpoints it merely POINTS AT ({@code range},
 *       {@code broaderConcept}, {@code exactMatch}, op 6's {@code broader}) may be foreign, since those
 *       become objects of triples in our own graph. That asymmetry is what keeps the cross-tenant write
 *       fix intact while still letting the link be drawn, and it is what matters most here.</li>
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
    /** A VZTAH this ontology owns — the subject of an overlay pointing at a foreign range. */
    private static final String MY_VZTAH = GRAPH + "/pojem/ma-osobu";

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
            seedConcept(mine, MY_VZTAH, "má osobu", ConceptType.VZTAH, GRAPH);
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

        DiagramDto read = save(node(MY_CLASS, 0, 0), node(FOREIGN_CLASS, 300, 0));

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

        DiagramDto read = save(node(MY_CLASS, 0, 0),
                node(FOREIGN_CLASS, 300, 0),
                node(FOREIGN_PARENT, 600, 0));

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

        DiagramDto read = save(node(MY_CLASS, 0, 0), node(FOREIGN_CLASS, 300, 0));

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

        DiagramDto read = save(node(MY_CLASS, 0, 0));

        assertThat(nodeFor(read, MY_CLASS).data().readOnly()).isFalse();
    }

    /**
     * An NKD IRI has no PG row and no local graph. It must still be placeable and still render as a
     * node — the alternative is that the user cannot reference published NKD concepts at all.
     */
    @Test
    void nkdIri_withNoPgRow_isAcceptedAsForeign() {
        stubGraph(GRAPH, concept(MY_CLASS, "Zaměstnanec"));

        DiagramDto read = save(node(MY_CLASS, 0, 0), node(NKD_CLASS, 300, 0));

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

        DiagramDto read = save(node(MY_CLASS, 0, 0), node(FOREIGN_CLASS, 300, 0));

        assertThat(nodeFor(read, MY_CLASS).data().label()).containsEntry("cs", "Zaměstnanec");
        assertThat(nodeFor(read, FOREIGN_CLASS).data().stale())
                .as("the foreign node degrades, the canvas still renders")
                .isTrue();
    }

    // ---- the guard ------------------------------------------------------------------------------

    /**
     * Free placement does not imply a free edit. An overlay whose SUBJECT is a foreign concept is
     * refused, because materializing it would write another ontology's RDF — the cross-tenant defect
     * this guard exists to prevent. This is the most important assertion in the class.
     */
    @Test
    void overlayOnAForeignConcept_isStillRejected() {
        stubGraph(GRAPH, concept(MY_CLASS, "Zaměstnanec"));

        DiagramLayoutDto layout = new DiagramLayoutDto(
                storedVersion(), null,
                List.of(node(MY_CLASS, 0, 0), node(FOREIGN_CLASS, 300, 0)),
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

    /**
     * Foreignness is DERIVED from the concept's graph, never declared by the client. The write body says
     * nothing about it, so a foreign node placed by a client that only echoes back what it read — which
     * is every client, since the read exposes the flag under {@code data.readOnly} and the FE rebuilds
     * the Save body from canvas state — is still stored read-only rather than rejected as cross-tenant.
     */
    @Test
    void foreignnessIsDerivedFromTheGraph_notDeclaredByTheClient() {
        stubGraph(GRAPH, concept(MY_CLASS, "Zaměstnanec"));
        stubGraph(OTHER_GRAPH, concept(FOREIGN_CLASS, "Osoba"));

        DiagramDto read = save(node(MY_CLASS, 0, 0), node(FOREIGN_CLASS, 300, 0));

        assertThat(nodeFor(read, FOREIGN_CLASS).data().readOnly())
                .as("another ontology's concept is read-only however the client asked for it")
                .isTrue();
        assertThat(nodeFor(read, MY_CLASS).data().readOnly())
                .as("and our own concept stays editable — the derivation is not blanket read-only")
                .isFalse();
    }

    /**
     * A concept that changes hands is re-derived on the next save, not frozen at placement time. Stored
     * foreignness is a cache of the graph relation, so it may never contradict it.
     */
    @Test
    void aNodeStoredForeign_becomesEditableOnceItsConceptIsOurs() {
        stubGraph(GRAPH, concept(MY_CLASS, "Zaměstnanec"), concept(FOREIGN_CLASS, "Osoba"));
        save(node(MY_CLASS, 0, 0), node(FOREIGN_CLASS, 300, 0));

        // The concept moves into our ontology; the node row still says foreign.
        txTemplate.executeWithoutResult(tx -> {
            ConceptMetadataEntity c = conceptRepo.findByConceptIri(FOREIGN_CLASS).orElseThrow();
            c.setGraphName(GRAPH);
            c.setOntologyMetadata(ontologyRepo.findBySlug(SLUG).orElseThrow());
            conceptRepo.save(c);
        });

        DiagramDto read = save(node(MY_CLASS, 0, 0), node(FOREIGN_CLASS, 300, 0));

        assertThat(nodeFor(read, FOREIGN_CLASS).data().readOnly())
                .as("re-derived on save — a stale read-only flag would lock a concept we now own")
                .isFalse();
    }

    /**
     * The feature this whole class exists for: a VZTAH our ontology owns, pointing AT a foreign concept.
     * The range is only referenced — it becomes the object of a triple in our own graph — so it may be
     * foreign, and staging it is the supported way to draw that link.
     */
    @Test
    void overlayWithAForeignRange_isStaged() {
        stubGraph(GRAPH, concept(MY_CLASS, "Zaměstnanec"), concept(MY_VZTAH, "má osobu"));

        DiagramLayoutDto layout = new DiagramLayoutDto(
                storedVersion(), null,
                List.of(node(MY_CLASS, 0, 0), node(FOREIGN_CLASS, 300, 0)),
                List.of(),
                List.of(new DiagramLayoutDto.Overlay(
                        MY_VZTAH, MY_CLASS, FOREIGN_CLASS, null, null, null)));

        diagramService.saveLayout(SLUG, diagramId(), layout);

        assertThat(pendingEditRepo.findByDiagramIdAndConceptIri(diagramId(), MY_VZTAH))
                .as("our vztah may point at another ontology's concept")
                .isPresent();
    }

    /**
     * The mirror image, and the reason range and domain cannot share one rule: the domain is the concept
     * the link hangs OFF, so a foreign one would write a triple into an ontology we were never authorized
     * for — the diagram endpoints authorize this slug only.
     */
    @Test
    void overlayWithAForeignDomain_isRejected() {
        stubGraph(GRAPH, concept(MY_CLASS, "Zaměstnanec"), concept(MY_VZTAH, "má osobu"));

        DiagramLayoutDto layout = new DiagramLayoutDto(
                storedVersion(), null,
                List.of(node(MY_CLASS, 0, 0), node(FOREIGN_CLASS, 300, 0)),
                List.of(),
                List.of(new DiagramLayoutDto.Overlay(
                        MY_VZTAH, FOREIGN_CLASS, MY_CLASS, null, null, null)));

        assertThatThrownBy(() -> diagramService.saveLayout(SLUG, diagramId(), layout))
                .as("the origin of the link must be ours")
                .isInstanceOf(ConceptValidationException.class);

        assertThat(pendingEditRepo.findByDiagramId(diagramId())).isEmpty();
    }

    /** A hierarchy target is referenced, not written — our class may be a subclass of a foreign one. */
    @Test
    void overlayWithAForeignHierarchyTarget_isStaged() {
        stubGraph(GRAPH, concept(MY_CLASS, "Zaměstnanec"));

        DiagramLayoutDto layout = new DiagramLayoutDto(
                storedVersion(), null,
                List.of(node(MY_CLASS, 0, 0), node(FOREIGN_CLASS, 300, 0)),
                List.of(),
                List.of(new DiagramLayoutDto.Overlay(
                        MY_CLASS, null, null, List.of(FOREIGN_CLASS), null, null)));

        diagramService.saveLayout(SLUG, diagramId(), layout);

        assertThat(pendingEditRepo.findByDiagramIdAndConceptIri(diagramId(), MY_CLASS))
                .as("subClassOf a foreign class writes only our own graph")
                .isPresent();
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

    private DiagramLayoutDto.Node node(String iri, double x, double y) {
        return new DiagramLayoutDto.Node(DiagramMapper.NODE_ID_PREFIX + iri,
                new PositionDto(x, y), null, false, List.of());
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
