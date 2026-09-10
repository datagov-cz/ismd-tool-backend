package com.dia.ismdtoolbackend.diagram;

import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramConceptUsageDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramConceptUsageKind;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEdgeEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.entity.DiagramPendingEditEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.outbox.OutboxEntry;
import com.dia.ismdtoolbackend.outbox.OutboxEntryRepository;
import com.dia.ismdtoolbackend.outbox.PostgresIntegrationTestBase;
import com.dia.ismdtoolbackend.outbox.TransactionTemplateConfig;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.impl.DiagramConceptUsageService;
import com.dia.ismdtoolbackend.service.impl.ReferencedConceptResolutionEngine;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "Which diagrams draw this concept?" — the concept detail page's canvas cross-reference.
 *
 * <p>The question sounds like one lookup but is three, because canvas membership is recorded in three
 * different places: a TŘÍDA is a {@code diagram_nodes} row, a VZTAH is a {@code diagram_edges} row keyed
 * by its own IRI, and a VLASTNOST is an entry inside its host class's {@code visible_properties_json}.
 * A test that only covers classes would pass while the feature silently reports nothing for two thirds
 * of the concepts it is asked about, so each shape is asserted on its own.
 *
 * <p>The other axis is the overlay merge: what a canvas SHOWS is live RDF ⊕ that diagram's staged edit,
 * so two diagrams can legitimately disagree about the same concept.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@Import({DiagramConceptUsageIntegrationTest.Beans.class, TransactionTemplateConfig.class,
        com.dia.ismdtoolbackend.config.JpaAuditingConfig.class})
@EntityScan(basePackageClasses = {OutboxEntry.class, ConceptMetadataEntity.class, DiagramEntity.class})
@EnableJpaRepositories(basePackageClasses = {OutboxEntryRepository.class, ConceptMetadataRepository.class,
        DiagramRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DiagramConceptUsageIntegrationTest extends PostgresIntegrationTestBase {

    private static final String GRAPH = "https://slovnik.gov.cz/usage";
    private static final String SLUG = "usage-ontology";
    private static final String USER = "user123";

    private static final String CLASS_IRI = GRAPH + "/pojem/zamestnanec";
    private static final String OTHER_CLASS_IRI = GRAPH + "/pojem/organizace";
    private static final String PARENT_CLASS_IRI = GRAPH + "/pojem/osoba";
    private static final String VZTAH_IRI = GRAPH + "/pojem/je-zamestnan-u";
    private static final String VLASTNOST_IRI = GRAPH + "/pojem/datum-narozeni";

    @Autowired private ConceptMetadataRepository conceptRepo;
    @Autowired private OntologyMetadataRepository ontologyRepo;
    @Autowired private DiagramRepository diagramRepo;
    @Autowired private DiagramPendingEditRepository pendingEditRepo;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private DiagramConceptUsageService usageService;
    @Autowired private JenaTDB2Repository tdb2;
    @Autowired private ReferencedConceptResolutionEngine resolutionEngine;

    @BeforeEach
    void setUp() {
        reset(tdb2, resolutionEngine);
        when(tdb2.fetchConceptHierarchy(any(), any()))
                .thenReturn(JenaTDB2Repository.ConceptHierarchyLinks.empty());
        // Resolve every requested IRI to a stub, so an assertion about WHICH IRIs were resolved is not
        // silently satisfied by an empty map.
        when(resolutionEngine.resolveAll(any())).thenAnswer(inv -> {
            List<String> iris = inv.getArgument(0);
            return iris.stream().collect(java.util.stream.Collectors.toMap(i -> i, this::stub));
        });

        txTemplate.executeWithoutResult(tx -> {
            pendingEditRepo.deleteAllInBatch();
            diagramRepo.deleteAllInBatch();
            conceptRepo.deleteAllInBatch();
            ontologyRepo.deleteAllInBatch();
        });
        txTemplate.executeWithoutResult(tx -> {
            OntologyMetadataEntity o = ontology();
            seedConcept(o, CLASS_IRI, "Zaměstnanec", ConceptType.TRIDA);
            seedConcept(o, OTHER_CLASS_IRI, "Organizace", ConceptType.TRIDA);
            seedConcept(o, PARENT_CLASS_IRI, "Osoba", ConceptType.TRIDA);
            seedConcept(o, VZTAH_IRI, "je zaměstnán u", ConceptType.VZTAH);
            seedConcept(o, VLASTNOST_IRI, "datum narození", ConceptType.VLASTNOST);
        });
    }

    // ---- the three membership shapes -------------------------------------------------------------

    /** A class is a node row — the shape everyone thinks of, and the only one a naive query would find. */
    @Test
    void classOnACanvas_isReportedAsANode() {
        Long diagramId = diagram("Hlavní diagram", d -> node(d, CLASS_IRI));

        DiagramConceptUsageDto usage = usageService.usageForSlug(slugOf(CLASS_IRI));

        assertThat(usage.placements()).singleElement().satisfies(p -> {
            assertThat(p.diagramId()).isEqualTo(diagramId);
            assertThat(p.diagramName()).isEqualTo("Hlavní diagram");
            assertThat(p.ontologySlug()).as("the FE builds the link from this + diagramId").isEqualTo(SLUG);
            assertThat(p.kind()).isEqualTo(DiagramConceptUsageKind.NODE);
        });
    }

    /**
     * A VZTAH is never a node row — its membership is an edge row keyed by its own concept IRI. Looking
     * for it in {@code diagram_nodes} finds nothing, which is why this is its own query and its own test.
     */
    @Test
    void relationshipOnACanvas_isReportedAsAnEdge() {
        Long diagramId = diagram("Vztahový pohled", d -> edge(d, VZTAH_IRI));

        DiagramConceptUsageDto usage = usageService.usageForSlug(slugOf(VZTAH_IRI));

        assertThat(usage.placements()).singleElement().satisfies(p -> {
            assertThat(p.diagramId()).isEqualTo(diagramId);
            assertThat(p.kind()).isEqualTo(DiagramConceptUsageKind.EDGE);
        });
    }

    /**
     * A VLASTNOST is neither node nor edge: it is drawn as a row inside its host class's cell, so its
     * membership lives in that node's visible-properties list. The host is reported because otherwise
     * the user is told "it is on this diagram" with no way to find it.
     */
    @Test
    void propertyOnACanvas_isReportedAsAPropertyRowWithItsHostClass() {
        Long diagramId = diagram("Vlastnostní pohled", d -> node(d, CLASS_IRI, List.of(VLASTNOST_IRI)));

        DiagramConceptUsageDto usage = usageService.usageForSlug(slugOf(VLASTNOST_IRI));

        assertThat(usage.placements()).singleElement().satisfies(p -> {
            assertThat(p.diagramId()).isEqualTo(diagramId);
            assertThat(p.kind()).isEqualTo(DiagramConceptUsageKind.PROPERTY_ROW);
            assertThat(p.hostClass()).isNotNull();
            assertThat(p.hostClass().iri())
                    .as("where on the canvas to look for the row").isEqualTo(CLASS_IRI);
        });
    }

    /**
     * The property's host node existing is NOT enough — the class must actually list the property. An
     * uncurated class shows no rows, so reporting it would send the user to a diagram that does not
     * draw the concept at all.
     */
    @Test
    void propertyNotListedByItsHostClass_isNotReported() {
        diagram("Nekurátorovaný", d -> node(d, CLASS_IRI, List.of(OTHER_CLASS_IRI)));

        DiagramConceptUsageDto usage = usageService.usageForSlug(slugOf(VLASTNOST_IRI));

        assertThat(usage.placements())
                .as("a node whose list omits the property does not draw it")
                .isEmpty();
    }

    // ---- the answer's shape ----------------------------------------------------------------------

    /** "On no diagram" is a normal answer the page renders, not a 404. */
    @Test
    void conceptOnNoCanvas_returnsEmptyPlacements_notAnError() {
        diagram("Jiný diagram", d -> node(d, OTHER_CLASS_IRI));

        DiagramConceptUsageDto usage = usageService.usageForSlug(slugOf(CLASS_IRI));

        assertThat(usage.conceptIri()).isEqualTo(CLASS_IRI);
        assertThat(usage.conceptName()).containsEntry("cs", "Zaměstnanec");
        assertThat(usage.placements()).isEmpty();
    }

    /** A concept on nothing must not pay for a hierarchy fetch — the empty answer is decided in PG. */
    @Test
    void conceptOnNoCanvas_doesNotTouchTheGraph() {
        usageService.usageForSlug(slugOf(CLASS_IRI));

        verify(tdb2, never()).fetchConceptHierarchy(any(), any());
        verify(resolutionEngine, never()).resolveAll(any());
    }

    @Test
    void unknownSlug_isRejected() {
        assertThatThrownBy(() -> usageService.usageForSlug("neexistujici-pojem"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    /** Every diagram drawing the concept is listed — the page's whole purpose is "which ones". */
    @Test
    void aConceptOnManyCanvases_listsThemAll() {
        Long first = diagram("První", d -> node(d, CLASS_IRI));
        Long second = diagram("Druhý", d -> node(d, CLASS_IRI));

        DiagramConceptUsageDto usage = usageService.usageForSlug(slugOf(CLASS_IRI));

        assertThat(usage.placements()).extracting(DiagramConceptUsageDto.Placement::diagramId)
                .containsExactlyInAnyOrder(first, second);
    }

    /**
     * Cost must not scale with the number of diagrams. Three canvases is still ONE hierarchy fetch and
     * ONE resolve batch — the property that makes this endpoint viable on a hub concept, and the one a
     * refactor toward "resolve per placement" would silently destroy.
     */
    @Test
    void manyCanvases_costOneGraphFetchAndOneResolveBatch() {
        diagram("A", d -> node(d, CLASS_IRI));
        diagram("B", d -> node(d, CLASS_IRI));
        diagram("C", d -> node(d, CLASS_IRI));

        usageService.usageForSlug(slugOf(CLASS_IRI));

        verify(tdb2).fetchConceptHierarchy(any(), any());
        verify(resolutionEngine).resolveAll(any());
    }

    // ---- live structure and the per-diagram overlay -----------------------------------------------

    /** Hierarchy comes from RDF and is returned resolved, not as bare IRIs. */
    @Test
    void hierarchyIsReportedResolved() {
        when(tdb2.fetchConceptHierarchy(any(), any())).thenReturn(
                new JenaTDB2Repository.ConceptHierarchyLinks(List.of(PARENT_CLASS_IRI), List.of()));
        diagram("S hierarchií", d -> node(d, CLASS_IRI));

        DiagramConceptUsageDto usage = usageService.usageForSlug(slugOf(CLASS_IRI));

        assertThat(usage.placements()).singleElement().satisfies(p -> {
            assertThat(p.broader()).singleElement()
                    .satisfies(b -> assertThat(b.iri()).isEqualTo(PARENT_CLASS_IRI));
            assertThat(p.pending()).as("nothing staged here").isFalse();
        });
    }

    /**
     * The reason placements carry structure at all: a diagram shows live RDF ⊕ its OWN staged edit, so
     * a canvas with a staged domain change reports the staged value while an untouched canvas reports
     * the live one. Reporting one answer for both would misrepresent whichever canvas lost.
     */
    @Test
    void eachCanvasReportsItsOwnStagedStructure() {
        when(tdb2.fetchConceptHierarchy(any(), any())).thenReturn(
                new JenaTDB2Repository.ConceptHierarchyLinks(List.of(PARENT_CLASS_IRI), List.of()));
        Long staged = diagram("Se změnou", d -> edge(d, VZTAH_IRI));
        Long untouched = diagram("Beze změny", d -> edge(d, VZTAH_IRI));

        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setBroaderConcept(List.of(OTHER_CLASS_IRI));
        stage(staged, VZTAH_IRI, overlay);

        DiagramConceptUsageDto usage = usageService.usageForSlug(slugOf(VZTAH_IRI));

        assertThat(placement(usage, staged)).satisfies(p -> {
            assertThat(p.broader()).singleElement()
                    .satisfies(b -> assertThat(b.iri()).isEqualTo(OTHER_CLASS_IRI));
            assertThat(p.pending()).as("this canvas has uncommitted work").isTrue();
        });
        assertThat(placement(usage, untouched)).satisfies(p -> {
            assertThat(p.broader()).singleElement()
                    .satisfies(b -> assertThat(b.iri()).isEqualTo(PARENT_CLASS_IRI));
            assertThat(p.pending()).isFalse();
        });
    }

    /**
     * An overlay overrides field by field, not wholesale. A staged hierarchy change must not blank the
     * domain the canvas still shows, which is what a naive "if overlay present, use overlay" would do.
     */
    @Test
    void anOverlayOverridesOnlyTheFieldsItCarries() {
        when(tdb2.fetchConceptHierarchy(any(), any())).thenReturn(
                new JenaTDB2Repository.ConceptHierarchyLinks(List.of(PARENT_CLASS_IRI), List.of()));
        Long diagramId = diagram("Částečná změna", d -> edge(d, VZTAH_IRI));

        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setDomain(OTHER_CLASS_IRI);          // domain staged; hierarchy untouched
        stage(diagramId, VZTAH_IRI, overlay);

        DiagramConceptUsageDto usage = usageService.usageForSlug(slugOf(VZTAH_IRI));

        assertThat(placement(usage, diagramId)).satisfies(p -> {
            assertThat(p.domain()).isNotNull();
            assertThat(p.domain().iri()).isEqualTo(OTHER_CLASS_IRI);
            assertThat(p.broader()).as("live hierarchy survives a domain-only overlay")
                    .singleElement().satisfies(b -> assertThat(b.iri()).isEqualTo(PARENT_CLASS_IRI));
        });
    }

    /** An explicitly-empty staged list means "clear this predicate" — a real value, not "unset". */
    @Test
    void anOverlayCanStageAnEmptyHierarchy() {
        when(tdb2.fetchConceptHierarchy(any(), any())).thenReturn(
                new JenaTDB2Repository.ConceptHierarchyLinks(List.of(PARENT_CLASS_IRI), List.of()));
        Long diagramId = diagram("Vyprázdněná hierarchie", d -> node(d, CLASS_IRI));

        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setBroaderConcept(List.of());
        stage(diagramId, CLASS_IRI, overlay);

        DiagramConceptUsageDto usage = usageService.usageForSlug(slugOf(CLASS_IRI));

        assertThat(placement(usage, diagramId).broader())
                .as("staged [] clears the predicate rather than falling back to live")
                .isEmpty();
    }

    /**
     * An overlay staged on a DIFFERENT concept must not leak onto this one, and one staged on another
     * diagram must not leak across canvases. Both are keyed by (diagram, concept).
     */
    @Test
    void overlaysOfOtherConceptsAndOtherDiagrams_doNotLeak() {
        Long mine = diagram("Můj", d -> node(d, CLASS_IRI));
        Long theirs = diagram("Cizí", d -> node(d, OTHER_CLASS_IRI));

        DiagramPendingEdit otherConcept = new DiagramPendingEdit();
        otherConcept.setBroaderConcept(List.of(OTHER_CLASS_IRI));
        stage(mine, VZTAH_IRI, otherConcept);          // same diagram, different concept

        DiagramPendingEdit otherDiagram = new DiagramPendingEdit();
        otherDiagram.setBroaderConcept(List.of(OTHER_CLASS_IRI));
        stage(theirs, CLASS_IRI, otherDiagram);        // same concept, different diagram

        DiagramConceptUsageDto usage = usageService.usageForSlug(slugOf(CLASS_IRI));

        assertThat(placement(usage, mine).pending())
                .as("neither staged edit belongs to (this diagram, this concept)")
                .isFalse();
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private DiagramConceptUsageDto.Placement placement(DiagramConceptUsageDto usage, Long diagramId) {
        return usage.placements().stream()
                .filter(p -> diagramId.equals(p.diagramId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no placement for diagram " + diagramId));
    }

    private ResolvedConceptDto stub(String iri) {
        return ResolvedConceptDto.builder().iri(iri).conceptSlug(slugOf(iri)).build();
    }

    private static String slugOf(String iri) {
        return iri.substring(iri.lastIndexOf('/') + 1);
    }

    /** Build a diagram and let the caller populate it, returning its id. */
    private Long diagram(String name, java.util.function.Consumer<DiagramEntity> populate) {
        return txTemplate.execute(tx -> {
            DiagramEntity d = new DiagramEntity();
            d.setName(name);
            d.setOntologyMetadata(ontologyRepo.findBySlug(SLUG).orElseThrow());
            populate.accept(d);
            return diagramRepo.saveAndFlush(d).getId();
        });
    }

    private void node(DiagramEntity diagram, String conceptIri) {
        node(diagram, conceptIri, List.of());
    }

    private void node(DiagramEntity diagram, String conceptIri, List<String> visibleProperties) {
        DiagramNodeEntity n = new DiagramNodeEntity();
        n.setConceptIri(conceptIri);
        n.setPosX(0.0);
        n.setPosY(0.0);
        n.setVisibleProperties(visibleProperties);
        diagram.addNode(n);
    }

    /** A VZTAH edge is keyed by its own concept IRI — that IS its canvas membership. */
    private void edge(DiagramEntity diagram, String conceptIri) {
        DiagramEdgeEntity e = new DiagramEdgeEntity();
        e.setEdgeKey(conceptIri);
        diagram.addEdge(e);
    }

    private void stage(Long diagramId, String conceptIri, DiagramPendingEdit edit) {
        txTemplate.executeWithoutResult(tx -> {
            DiagramPendingEditEntity row = new DiagramPendingEditEntity();
            row.setDiagram(diagramRepo.findById(diagramId).orElseThrow());
            row.setOntologyMetadata(ontologyRepo.findBySlug(SLUG).orElseThrow());
            row.setConceptIri(conceptIri);
            row.setPendingEdit(edit);
            pendingEditRepo.saveAndFlush(row);
        });
    }

    private OntologyMetadataEntity ontology() {
        OntologyMetadataEntity o = new OntologyMetadataEntity();
        o.setSlug(SLUG);
        o.setGraphName(GRAPH);
        o.setUserId(USER);
        o.setIsPublished(false);
        o.setCreatedAt(LocalDateTime.now());
        return ontologyRepo.save(o);
    }

    private void seedConcept(OntologyMetadataEntity ontology, String iri, String name, ConceptType type) {
        ConceptMetadataEntity c = new ConceptMetadataEntity();
        c.setConceptIri(iri);
        c.setConceptName(name);
        c.setConceptType(type);
        c.setGraphName(GRAPH);
        c.setUserId(ontology.getUserId());
        c.setOntologyMetadata(ontology);
        c.setSlug(slugOf(iri));
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        conceptRepo.save(c);
    }

    @TestConfiguration
    static class Beans {

        @Bean JenaTDB2Repository jenaTDB2Repository() {
            return mock(JenaTDB2Repository.class);
        }

        @Bean ReferencedConceptResolutionEngine referencedConceptResolutionEngine() {
            return mock(ReferencedConceptResolutionEngine.class);
        }

        @Bean DiagramConceptUsageService diagramConceptUsageService(
                DiagramRepository diagramRepo, DiagramPendingEditRepository pendingEditRepo,
                ConceptMetadataRepository conceptRepo, JenaTDB2Repository tdb2,
                ReferencedConceptResolutionEngine engine) {
            return new DiagramConceptUsageService(diagramRepo, pendingEditRepo, conceptRepo, tdb2, engine);
        }
    }
}