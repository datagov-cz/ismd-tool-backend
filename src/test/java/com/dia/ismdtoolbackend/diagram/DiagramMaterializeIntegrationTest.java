package com.dia.ismdtoolbackend.diagram;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.controller.dto.diagram.MaterializeResultDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEdgeEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.entity.DiagramPendingEditEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.DiagramNodeBacking;
import com.dia.ismdtoolbackend.enums.DiagramOp;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapperImpl;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptModel;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;
import com.dia.ismdtoolbackend.outbox.InMemoryTdb2;
import com.dia.ismdtoolbackend.outbox.OutboxConfig;
import com.dia.ismdtoolbackend.outbox.OutboxEntry;
import com.dia.ismdtoolbackend.outbox.OutboxEntryRepository;
import com.dia.ismdtoolbackend.outbox.OutboxRelay;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.outbox.PostgresIntegrationTestBase;
import com.dia.ismdtoolbackend.outbox.TransactionTemplateConfig;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramEdgeRepository;
import com.dia.ismdtoolbackend.repository.DiagramNodeRepository;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.impl.ConceptDeviationComparator;
import com.dia.ismdtoolbackend.service.impl.ConceptServiceImpl;
import com.dia.ismdtoolbackend.service.impl.DiagramChangeApplier;
import com.dia.ismdtoolbackend.service.impl.DiagramMaterializeService;
import com.dia.ismdtoolbackend.service.impl.MetadataTouchService;
import com.dia.ismdtoolbackend.service.impl.ReferencedConceptsEnricher;
import com.dia.ismdtoolbackend.service.impl.WorkingCopyDeviationServiceImpl;
import com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder;
import com.dia.ismdtoolbackend.utility.creator.ConceptCreator;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.editor.ConceptEditor;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.sys.JenaSystem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * End-to-end materialize (Převzít): the REAL {@link ConceptServiceImpl} (real editor/creator/mapper, real
 * PG repos via Testcontainers, real outbox + in-memory TDB2) driven through {@link DiagramMaterializeService}
 * and {@link DiagramChangeApplier}. Proves the guarantees that only surface against a live concept graph:
 * IRI never renamed on Převzít (V1), classification not dropped by a field-scoped edit (V4/V5), op-6 cascade
 * guard (V8), op-6 idempotent retry, per-change partial-ok, stale-base 409, and — the E-blocking
 * characterization — the op-6 / stale-base collision (adversarial review finding #3).
 *
 * <p>Harness mirrors {@code ConceptOutboxFlowIntegrationTest}: Spring-managed beans so {@code REQUIRES_NEW}
 * on the applier is actually proxied; {@code NOT_SUPPORTED} so each change commits on a real boundary.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@Import({DiagramMaterializeIntegrationTest.Beans.class, TransactionTemplateConfig.class,
        com.dia.ismdtoolbackend.config.JpaAuditingConfig.class})
@EntityScan(basePackageClasses = {OutboxEntry.class, ConceptMetadataEntity.class, DiagramEntity.class})
@EnableJpaRepositories(basePackageClasses = {OutboxEntryRepository.class, ConceptMetadataRepository.class,
        DiagramRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DiagramMaterializeIntegrationTest extends PostgresIntegrationTestBase {

    private static final String GRAPH = "https://slovnik.gov.cz/g";
    private static final String USER = "user123";
    private static final String VICTIM_GRAPH = "https://slovnik.gov.cz/victim";
    private static final String VICTIM_USER = "victim-user-999";

    @Autowired private ConceptMetadataRepository conceptRepo;
    @Autowired private OntologyMetadataRepository ontologyRepo;
    @Autowired private OutboxEntryRepository outboxRepo;
    @Autowired private DiagramRepository diagramRepo;
    @Autowired private DiagramNodeRepository nodeRepo;
    @Autowired private DiagramEdgeRepository edgeRepo;
    @Autowired private DiagramPendingEditRepository pendingEditRepo;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private ConceptServiceImpl conceptService;
    @Autowired private DiagramMaterializeService materializeService;
    @Autowired private OutboxConfig outboxConfig;
    @Autowired private InMemoryTdb2 tdb2;

    @BeforeAll
    static void initJena() {
        JenaSystem.init();
    }

    @BeforeEach
    void setUp() {
        // No authenticated principal by default: the materialize tests drive the service as a non-request
        // caller, which the ownership assertion deliberately lets through.
        SecurityContextHolder.clearContext();
        outboxConfig.setEnabled(true);
        outboxConfig.setBatchSize(100);
        txTemplate.executeWithoutResult(tx -> {
            outboxRepo.deleteAllInBatch();
            nodeRepo.deleteAllInBatch();
            diagramRepo.deleteAllInBatch();
            conceptRepo.deleteAllInBatch();
            ontologyRepo.deleteAllInBatch();
        });
        // Separate tx so the deletes are committed before the insert — avoids racing the FK/unique index.
        txTemplate.executeWithoutResult(tx -> {
            OntologyMetadataEntity ont = new OntologyMetadataEntity();
            ont.setSlug("g-ontology");
            ont.setGraphName(GRAPH);
            ont.setUserId(USER);
            ont.setIsPublished(false);
            ont.setCreatedAt(LocalDateTime.now());
            ontologyRepo.save(ont);
        });
        tdb2.reset();
    }

    // ---- concept-graph seeding via the real service ---------------------------------------------

    private ClassConceptModel classModel(String name, boolean isPublic) {
        ClassConceptModel m = new ClassConceptModel();
        m.setConceptType("třída");
        m.setType("objekt");
        m.setOntologyGraphName(GRAPH);
        m.setNamespace(GRAPH);
        m.setIsPublic(isPublic);
        m.setNameModel(name(name));
        return m;
    }

    private RelationshipConceptModel relModel(String name, String domain, String range) {
        RelationshipConceptModel m = new RelationshipConceptModel();
        m.setConceptType("vztah");
        m.setOntologyGraphName(GRAPH);
        m.setNamespace(GRAPH);
        m.setDomain(domain);
        m.setRange(range);
        m.setNameModel(name(name));
        return m;
    }

    private com.dia.ismdtoolbackend.models.concept.PropertyConceptModel propModel(String name, String domain) {
        com.dia.ismdtoolbackend.models.concept.PropertyConceptModel m =
                new com.dia.ismdtoolbackend.models.concept.PropertyConceptModel();
        m.setConceptType("vlastnost");
        m.setOntologyGraphName(GRAPH);
        m.setNamespace(GRAPH);
        m.setDomain(domain);
        m.setNameModel(name(name));
        return m;
    }

    private NameModel name(String cs) {
        NameModel nm = new NameModel();
        Map<String, String> names = new HashMap<>();
        names.put("cs", cs);
        nm.setName(names);
        return nm;
    }

    /** Create a concept through the real service and return its persisted metadata row. */
    private ConceptMetadataEntity create(ClassConceptModel m) {
        conceptService.createConcept(m, USER);
        return latestByName(m.getNameModel().getName().get("cs"));
    }

    private ConceptMetadataEntity create(RelationshipConceptModel m) {
        conceptService.createConcept(m, USER);
        return latestByName(m.getNameModel().getName().get("cs"));
    }

    private ConceptMetadataEntity create(com.dia.ismdtoolbackend.models.concept.PropertyConceptModel m) {
        conceptService.createConcept(m, USER);
        return latestByName(m.getNameModel().getName().get("cs"));
    }

    private ConceptMetadataEntity latestByName(String cs) {
        return conceptRepo.findAll().stream()
                .filter(c -> c.getConceptName() != null && c.getConceptName().contains(cs))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a)
                .orElseThrow(() -> new IllegalStateException("No concept row for " + cs));
    }

    // ---- cross-tenant fixture (B2) ---------------------------------------------------------------

    /**
     * A SECOND ontology on its own graph, owned by another user — the victim in the IDOR probes below.
     * Must be committed before a concept can be created in it ({@code ConceptServiceImpl.createConcept}
     * resolves the ontology by graph name).
     */
    private void seedVictimOntology() {
        txTemplate.executeWithoutResult(tx -> {
            OntologyMetadataEntity victim = new OntologyMetadataEntity();
            victim.setSlug("victim-ontology");
            victim.setGraphName(VICTIM_GRAPH);
            victim.setUserId(VICTIM_USER);
            victim.setIsPublished(false);
            victim.setCreatedAt(LocalDateTime.now());
            ontologyRepo.save(victim);
        });
    }

    /** Create a class in the VICTIM's graph, owned by the victim — never legitimately on this diagram. */
    private ConceptMetadataEntity createVictimClass(String name) {
        ClassConceptModel m = new ClassConceptModel();
        m.setConceptType("třída");
        m.setType("objekt");
        m.setOntologyGraphName(VICTIM_GRAPH);
        m.setNamespace(VICTIM_GRAPH);
        m.setIsPublic(true);
        m.setNameModel(name(name));
        conceptService.createConcept(m, VICTIM_USER);
        return latestByName(name);
    }

    private Model victimGraph() {
        return tdb2.dataset().getNamedModel(VICTIM_GRAPH);
    }

    /** Every triple about the concept, as stable strings — for a byte-identical before/after comparison. */
    private List<String> triplesOf(Model model, String conceptIri) {
        return model.listStatements(model.getResource(conceptIri), null, (org.apache.jena.rdf.model.RDFNode) null)
                .toList().stream()
                .map(Object::toString)
                .sorted()
                .toList();
    }

    // ---- diagram seeding ------------------------------------------------------------------------

    /** Stage an edit on a concept of the g-ontology (captures the base-updatedAt fingerprint). */
    private void stageNode(String conceptIri, DiagramPendingEdit overlay) {
        txTemplate.executeWithoutResult(tx -> {
            DiagramPendingEditEntity row = new DiagramPendingEditEntity();
            row.setDiagram(diagramRepo.findById(diagramId()).orElseThrow());
            row.setOntologyMetadata(ontologyRepo.findBySlug("g-ontology").orElseThrow());
            row.setConceptIri(conceptIri);
            overlay.setBaseUpdatedAt(conceptRepo.findByConceptIri(conceptIri)
                    .map(ConceptMetadataEntity::getUpdatedAt).orElse(null));
            row.setPendingEdit(overlay);
            pendingEditRepo.saveAndFlush(row);
        });
    }

    /** Replace a concept's staged edit in place — the state a concurrent change would leave behind. */
    private void restage(String conceptIri, DiagramPendingEdit overlay) {
        txTemplate.executeWithoutResult(tx -> {
            DiagramPendingEditEntity row = pendingEditRepo
                    .findByDiagramIdAndConceptIri(diagramId(), conceptIri)
                    .orElseGet(() -> {
                        DiagramPendingEditEntity fresh = new DiagramPendingEditEntity();
                        fresh.setDiagram(diagramRepo.findById(diagramId()).orElseThrow());
                        fresh.setOntologyMetadata(ontologyRepo.findBySlug("g-ontology").orElseThrow());
                        fresh.setConceptIri(conceptIri);
                        return fresh;
                    });
            row.setPendingEdit(overlay);
            pendingEditRepo.saveAndFlush(row);
        });
    }

    /** The staged edit for a concept, or null when nothing is staged. */
    private DiagramPendingEdit stagedEdit(String conceptIri) {
        return pendingEditRepo.findByDiagramIdAndConceptIri(diagramId(), conceptIri)
                .map(DiagramPendingEditEntity::getPendingEdit)
                .orElse(null);
    }

    private Long ontologyId() {
        return ontologyRepo.findBySlug("g-ontology").orElseThrow().getId();
    }

    /** The g-ontology's diagram, created on first use — staged edits now hang off a diagram. */
    private Long diagramId() {
        return diagramRepo.findByOntologyMetadataIdOrderByIdAsc(ontologyId()).stream()
                .findFirst()
                .map(DiagramEntity::getId)
                .orElseGet(() -> txTemplate.execute(tx -> {
                    DiagramEntity d = new DiagramEntity();
                    d.setOntologyMetadata(ontologyRepo.findBySlug("g-ontology").orElseThrow());
                    d.setName("Test diagram");
                    return diagramRepo.saveAndFlush(d).getId();
                }));
    }

    private Model graph() {
        return tdb2.dataset().getNamedModel(GRAPH);
    }

    /** The concept's current {@code rdfs:subClassOf} targets in the live graph. */
    private List<String> broaderOf(String conceptIri) {
        Model g = graph();
        return g.listStatements(g.getResource(conceptIri), org.apache.jena.vocabulary.RDFS.subClassOf,
                        (org.apache.jena.rdf.model.RDFNode) null)
                .toList().stream()
                .map(s -> s.getObject().toString())
                .toList();
    }

    // ---- tests ----------------------------------------------------------------------------------

    // V1: Převzít never renames the concept IRI (overlay is structural-only).
    // V4/V5: a domain-only overlay does NOT drop the public/private classification.
    @Test
    void materializeDomainOverlay_keepsIriAndClassification() {
        ConceptMetadataEntity a = create(classModel("Třída A", true));
        ConceptMetadataEntity b = create(classModel("Třída B", true));
        ConceptMetadataEntity rel = create(relModel("je u", a.getConceptIri(), a.getConceptIri()));
        String relIri = rel.getConceptIri();

        // Overlay: repoint the VZTAH's range to B (a structural, field-scoped change).
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setDomain(a.getConceptIri());
        overlay.setRange(b.getConceptIri());
        stageNode(relIri, overlay);

        MaterializeResultDto result = materializeService.materialize(diagramId(), ontologyId());

        assertThat(result.materialized()).hasSize(1);
        assertThat(result.materialized().get(0).op()).isEqualTo(DiagramOp.SWAP_DIRECTION);
        assertThat(result.failed()).isEmpty();

        // V1: the IRI is unchanged (no rename on Převzít).
        assertThat(conceptRepo.findById(rel.getId()).orElseThrow().getConceptIri()).isEqualTo(relIri);
        // V4/V5: the VZTAH still carries its classification type in RDF (not stripped by the field-scoped edit).
        Model g = graph();
        assertThat(g.getResource(relIri).listProperties(org.apache.jena.vocabulary.RDF.type).toList())
                .as("classification/type triples survive a structural-only materialize").isNotEmpty();
        // The staged edit was cleared on success.
        assertThat(stagedEdit(relIri)).isNull();
    }

    // Stale-base: the concept was edited (via normal /api/concept) after the overlay was staged → 409.
    @Test
    void materialize_staleBase_reports409() {
        ConceptMetadataEntity a = create(classModel("Stale A", true));
        ConceptMetadataEntity b = create(classModel("Stale B", true));

        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setBroaderConcept(List.of(b.getConceptIri()));
        stageNode(a.getConceptIri(), overlay);

        // Simulate a concurrent edit of A landing after the overlay was staged: the staged fingerprint no
        // longer matches the concept's current updatedAt. Rewrite the persisted overlay's baseUpdatedAt to a
        // value that differs from the row's — exactly the state a real /api/concept edit would leave behind.
        txTemplate.executeWithoutResult(tx -> {
            DiagramPendingEdit staged = stagedEdit(a.getConceptIri());
            staged.setBaseUpdatedAt(LocalDateTime.of(2000, 1, 1, 0, 0));   // clearly ≠ current updatedAt
            restage(a.getConceptIri(), staged);
        });

        MaterializeResultDto result = materializeService.materialize(diagramId(), ontologyId());

        assertThat(result.materialized()).isEmpty();
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).error()).isEqualTo("STALE_BASE");
        assertThat(result.failed().get(0).status()).isEqualTo(409);
    }

    // skippedStale: the referenced concept no longer exists.
    @Test
    void materialize_missingConcept_reportedSkippedStale() {
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setBroaderConcept(List.of("https://x/pojem/whatever"));
        stageNode("https://slovnik.gov.cz/g/pojem/ghost", overlay);

        MaterializeResultDto result = materializeService.materialize(diagramId(), ontologyId());

        assertThat(result.skippedStale()).hasSize(1);
        assertThat(result.materialized()).isEmpty();
    }

    // SMOKE FINDING #1 (Phase 4.5) — a hierarchy edge's id embeds its endpoints, so materializing a repoint
    // changes the edge's identity. Without re-keying, the membership row stays on the OLD id, stops matching
    // the projection, and the edge silently leaves the canvas after Převzít — taking its waypoints with it.
    @Test
    void materializeRepointedHierarchy_movesMembershipToTheNewEdgeId() {
        ConceptMetadataEntity child = create(classModel("Rekey Child", true));
        ConceptMetadataEntity oldBroader = create(classModel("Rekey Old Broader", true));
        ConceptMetadataEntity newBroader = create(classModel("Rekey New Broader", true));

        // The child already subclasses oldBroader in RDF...
        DiagramPendingEdit seed = new DiagramPendingEdit();
        seed.setBroaderConcept(List.of(oldBroader.getConceptIri()));
        stageNode(child.getConceptIri(), seed);
        materializeService.materialize(diagramId(), ontologyId());

        // ...and the user placed that edge on the canvas, with waypoints.
        String oldKey = "edge|SUBCLASS_OF|" + child.getConceptIri() + "|" + oldBroader.getConceptIri();
        String newKey = "edge|SUBCLASS_OF|" + child.getConceptIri() + "|" + newBroader.getConceptIri();
        placeEdge(oldKey, List.of(new EdgeWaypoint(40, 80)));

        // Now repoint it to newBroader and take the change.
        DiagramPendingEdit repoint = new DiagramPendingEdit();
        repoint.setBroaderConcept(List.of(newBroader.getConceptIri()));
        restage(child.getConceptIri(), repoint);
        MaterializeResultDto result = materializeService.materialize(diagramId(), ontologyId());

        assertThat(result.failed()).isEmpty();
        List<DiagramEdgeEntity> rows = edgeRepo.findByDiagramId(diagramId());
        assertThat(rows).as("membership follows the edge to its new identity").singleElement()
                .satisfies(row -> {
                    assertThat(row.getEdgeKey()).isEqualTo(newKey);
                    assertThat(row.getSegments()).containsExactly(new EdgeWaypoint(40, 80));
                });
    }

    /** An unplaced repoint writes no membership — re-keying must not invent a row. */
    @Test
    void materializeRepointedHierarchy_withNoMembership_writesNone() {
        ConceptMetadataEntity child = create(classModel("Bare Child", true));
        ConceptMetadataEntity oldBroader = create(classModel("Bare Old", true));
        ConceptMetadataEntity newBroader = create(classModel("Bare New", true));

        DiagramPendingEdit seed = new DiagramPendingEdit();
        seed.setBroaderConcept(List.of(oldBroader.getConceptIri()));
        stageNode(child.getConceptIri(), seed);
        materializeService.materialize(diagramId(), ontologyId());

        DiagramPendingEdit repoint = new DiagramPendingEdit();
        repoint.setBroaderConcept(List.of(newBroader.getConceptIri()));
        restage(child.getConceptIri(), repoint);
        materializeService.materialize(diagramId(), ontologyId());

        assertThat(edgeRepo.findByDiagramId(diagramId())).isEmpty();
    }

    /** Place an edge on the canvas: a membership row, optionally routed. */
    private void placeEdge(String edgeKey, List<EdgeWaypoint> segments) {
        txTemplate.executeWithoutResult(tx -> {
            DiagramEdgeEntity row = new DiagramEdgeEntity();
            row.setDiagram(diagramRepo.findById(diagramId()).orElseThrow());
            row.setEdgeKey(edgeKey);
            row.setSegments(segments);
            edgeRepo.saveAndFlush(row);
        });
    }

    // ADVERSARIAL REVIEW FINDING #3 — op-6 / stale-base collision, now FIXED (op-6 ordered last).
    // A single Převzít stages a CONVERT_TO_HIERARCHY on VZTAH V (adds broader on class A, deletes V) AND a
    // separate overlay on A. Op 6's editConcept on A bumps A's updatedAt; if op 6 ran first, A's own change
    // would then see a moved base and wrongly report STALE_BASE though the user never touched A externally.
    // The fix orders op-6 LAST, so A materializes (and clears) before op 6 bumps it → no false stale-base.
    @Test
    void op6AndTargetOverlay_inSameMaterialize_noFalseStaleBase() {
        ConceptMetadataEntity a = create(classModel("Coll A", true));
        ConceptMetadataEntity b = create(classModel("Coll B", true));
        ConceptMetadataEntity broader = create(classModel("Coll Broader", true));
        // VZTAH V with domain A, range B — convertible to a hierarchy on A.
        ConceptMetadataEntity v = create(relModel("v-rel", a.getConceptIri(), b.getConceptIri()));

        // Overlay 1: op-6 on V — add `broader` as super-class of A, then delete V. Staged FIRST (lower node
        // id), so an unordered work-list would apply it before A and trigger the collision.
        DiagramPendingEdit convert = new DiagramPendingEdit();
        DiagramPendingEdit.ConvertToHierarchy marker = new DiagramPendingEdit.ConvertToHierarchy();
        marker.setAddBroaderOn(a.getConceptIri());
        marker.setBroader(broader.getConceptIri());
        convert.setConvertToHierarchy(marker);
        stageNode(v.getConceptIri(), convert);

        // Overlay 2: an independent structural change on A itself (its own exactMatch).
        DiagramPendingEdit aOverlay = new DiagramPendingEdit();
        aOverlay.setExactMatch(List.of(b.getConceptIri()));
        stageNode(a.getConceptIri(), aOverlay);

        MaterializeResultDto result = materializeService.materialize(diagramId(), ontologyId());

        // Fix asserted: NO stale-base failure — A's own change is not collided by the op-6 bump.
        assertThat(result.failed())
                .as("no false STALE_BASE from the op-6 bump of A").isEmpty();
        // Both A's own change and the op-6 convert materialized.
        assertThat(result.materialized())
                .extracting(MaterializeResultDto.Materialized::op)
                .contains(DiagramOp.CHANGE_HIERARCHY_TYPE, DiagramOp.CONVERT_TO_HIERARCHY);
        assertThat(result.materialized())
                .anyMatch(m -> a.getConceptIri().equals(m.conceptIri()));
        // The op-6 convert deleted the VZTAH (no incoming refs → no cascade conflict).
        assertThat(graph().containsResource(graph().getResource(v.getConceptIri())))
                .as("VZTAH V deleted by the successful op-6 convert").isFalse();
    }

    // V8: op-6 blocked when another concept's domain/range points at the VZTAH (deleting it would cascade).
    @Test
    void op6_withIncomingReference_reportsCascadeConflict() {
        ConceptMetadataEntity a = create(classModel("Casc A", true));
        ConceptMetadataEntity broader = create(classModel("Casc Broader", true));
        ConceptMetadataEntity v = create(relModel("casc-rel", a.getConceptIri(), a.getConceptIri()));
        // A property W whose rdfs:domain IS the VZTAH V → findRelatedConceptUris(V) is non-empty.
        create(propModel("casc-prop", v.getConceptIri()));

        DiagramPendingEdit convert = new DiagramPendingEdit();
        DiagramPendingEdit.ConvertToHierarchy marker = new DiagramPendingEdit.ConvertToHierarchy();
        marker.setAddBroaderOn(a.getConceptIri());
        marker.setBroader(broader.getConceptIri());
        convert.setConvertToHierarchy(marker);
        stageNode(v.getConceptIri(), convert);

        MaterializeResultDto result = materializeService.materialize(diagramId(), ontologyId());

        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).error()).isEqualTo("CASCADE_CONFLICT");
        assertThat(result.failed().get(0).status()).isEqualTo(409);
        // All-or-nothing: V is NOT deleted, and no broader was added on A.
        assertThat(graph().containsResource(graph().getResource(v.getConceptIri())))
                .as("VZTAH V untouched — cascade guard blocked the convert").isTrue();
        // The edit stays staged for a retry after the user resolves the reference.
        assertThat(stagedEdit(v.getConceptIri())).isNotNull();
    }

    // Op 6 ADDS a super-class. The edit model's broaderConcept is a full replace, so without a read-merge
    // the convert silently wipes the target class's existing subClassOf links — unreported RDF data loss,
    // unrecoverable in-request because the VZTAH is deleted in the same transaction. The other op-6 tests
    // all use freshly-created classes with no hierarchy, where replace and add are indistinguishable.
    @Test
    void op6_preservesTargetClassExistingBroaderConcepts() {
        ConceptMetadataEntity a = create(classModel("Merge A", true));
        ConceptMetadataEntity b = create(classModel("Merge B", true));
        ConceptMetadataEntity existingParent = create(classModel("Merge Existing Parent", true));
        ConceptMetadataEntity newBroader = create(classModel("Merge New Broader", true));
        ConceptMetadataEntity v = create(relModel("merge-rel", a.getConceptIri(), b.getConceptIri()));

        // Give A a pre-existing super-class through the normal edit path.
        ClassConceptEditModel seed = new ClassConceptEditModel();
        seed.setConceptType(ConceptType.TRIDA.getValue());
        seed.setBroaderConcept(List.of(existingParent.getConceptIri()));
        conceptService.editConcept(a.getId(), seed);
        assertThat(broaderOf(a.getConceptIri())).containsExactly(existingParent.getConceptIri());

        // Op 6 on V, adding a DIFFERENT broader to the same class.
        DiagramPendingEdit convert = new DiagramPendingEdit();
        DiagramPendingEdit.ConvertToHierarchy marker = new DiagramPendingEdit.ConvertToHierarchy();
        marker.setAddBroaderOn(a.getConceptIri());
        marker.setBroader(newBroader.getConceptIri());
        convert.setConvertToHierarchy(marker);
        stageNode(v.getConceptIri(), convert);

        assertThat(materializeService.materialize(diagramId(), ontologyId()).materialized()).hasSize(1);

        assertThat(broaderOf(a.getConceptIri()))
                .as("op-6 adds its broader and keeps the class's existing hierarchy")
                .containsExactlyInAnyOrder(existingParent.getConceptIri(), newBroader.getConceptIri());
    }

    /** An already-present broader must not be duplicated, and must not disturb the existing set. */
    @Test
    void op6_broaderAlreadyPresent_isNoOpOnHierarchy() {
        ConceptMetadataEntity a = create(classModel("Dup A", true));
        ConceptMetadataEntity b = create(classModel("Dup B", true));
        ConceptMetadataEntity parent = create(classModel("Dup Parent", true));
        ConceptMetadataEntity v = create(relModel("dup-rel", a.getConceptIri(), b.getConceptIri()));

        ClassConceptEditModel seed = new ClassConceptEditModel();
        seed.setConceptType(ConceptType.TRIDA.getValue());
        seed.setBroaderConcept(List.of(parent.getConceptIri()));
        conceptService.editConcept(a.getId(), seed);

        // Convert names the broader A already has.
        DiagramPendingEdit convert = new DiagramPendingEdit();
        DiagramPendingEdit.ConvertToHierarchy marker = new DiagramPendingEdit.ConvertToHierarchy();
        marker.setAddBroaderOn(a.getConceptIri());
        marker.setBroader(parent.getConceptIri());
        convert.setConvertToHierarchy(marker);
        stageNode(v.getConceptIri(), convert);

        assertThat(materializeService.materialize(diagramId(), ontologyId()).materialized()).hasSize(1);

        assertThat(broaderOf(a.getConceptIri())).containsExactly(parent.getConceptIri());
    }

    // op-6 idempotent retry: after a successful convert clears the overlay, re-staging + re-materializing
    // the same convert tolerates the already-deleted VZTAH (skippedStale) rather than erroring.
    @Test
    void op6_retryAfterDelete_isIdempotent() {
        ConceptMetadataEntity a = create(classModel("Idem A", true));
        ConceptMetadataEntity b = create(classModel("Idem B", true));
        ConceptMetadataEntity broader = create(classModel("Idem Broader", true));
        ConceptMetadataEntity v = create(relModel("idem-rel", a.getConceptIri(), b.getConceptIri()));

        DiagramPendingEdit convert = new DiagramPendingEdit();
        DiagramPendingEdit.ConvertToHierarchy marker = new DiagramPendingEdit.ConvertToHierarchy();
        marker.setAddBroaderOn(a.getConceptIri());
        marker.setBroader(broader.getConceptIri());
        convert.setConvertToHierarchy(marker);
        stageNode(v.getConceptIri(), convert);

        // First materialize: succeeds, deletes V, clears the overlay.
        assertThat(materializeService.materialize(diagramId(), ontologyId()).materialized()).hasSize(1);
        assertThat(graph().containsResource(graph().getResource(v.getConceptIri()))).isFalse();

        // Re-stage the same convert on the now-deleted concept and materialize again.
        DiagramPendingEdit again = new DiagramPendingEdit();
        DiagramPendingEdit.ConvertToHierarchy m2 = new DiagramPendingEdit.ConvertToHierarchy();
        m2.setAddBroaderOn(a.getConceptIri());
        m2.setBroader(broader.getConceptIri());
        again.setConvertToHierarchy(m2);
        restage(v.getConceptIri(), again);

        MaterializeResultDto retry = materializeService.materialize(diagramId(), ontologyId());

        // The concept row is gone (delete removed it) → the retry resolves to skippedStale, not an error.
        assertThat(retry.skippedStale()).hasSize(1);
        assertThat(retry.failed()).isEmpty();
    }

    // Per-change partial-ok: two staged changes, one valid and one that fails validation → one materialized,
    // one failed, and the failed change's overlay is retained while the good one's is cleared.
    @Test
    void materialize_partialCommit_reportsPerChangeAndRetainsFailedOverlay() {
        ConceptMetadataEntity good = create(classModel("Partial Good", true));
        ConceptMetadataEntity target = create(classModel("Partial Target", true));
        ConceptMetadataEntity bad = create(classModel("Partial Bad", true));

        // Good: a valid subClassOf change on `good`.
        DiagramPendingEdit goodOverlay = new DiagramPendingEdit();
        goodOverlay.setBroaderConcept(List.of(target.getConceptIri()));
        stageNode(good.getConceptIri(), goodOverlay);

        // Bad: an op-6 convert whose target class (addBroaderOn) does not exist → the applier throws
        // ConceptValidationException ("Cílová třída … nebyla nalezena") → a per-change VALIDATION failure.
        DiagramPendingEdit badOverlay = new DiagramPendingEdit();
        DiagramPendingEdit.ConvertToHierarchy marker = new DiagramPendingEdit.ConvertToHierarchy();
        marker.setAddBroaderOn("https://slovnik.gov.cz/g/pojem/does-not-exist");
        marker.setBroader(target.getConceptIri());
        badOverlay.setConvertToHierarchy(marker);
        stageNode(bad.getConceptIri(), badOverlay);

        MaterializeResultDto result = materializeService.materialize(diagramId(), ontologyId());

        assertThat(result.materialized())
                .anyMatch(m -> good.getConceptIri().equals(m.conceptIri()));
        assertThat(result.failed())
                .anyMatch(f -> bad.getConceptIri().equals(f.conceptIri()));
        // The good change's edit was cleared; the failed one's is retained for a fix-and-retry.
        assertThat(stagedEdit(good.getConceptIri())).isNull();
        assertThat(stagedEdit(bad.getConceptIri())).isNotNull();
    }

    @Test
    void materialize_unexpectedFailure_reportsGenericMessageNotRawException() {
        ConceptMetadataEntity subject = create(classModel("Leak Probe", true));

        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setBroaderConcept(List.of("https://slovnik.gov.cz/g/pojem/leak-target"));
        stageNode(subject.getConceptIri(), overlay);

        // Force the roleless branch's sibling: corrupt the stored concept type so buildEdit's switch has no
        // matching arm for a structural overlay, surfacing an unclassified RuntimeException.
        txTemplate.executeWithoutResult(tx -> {
            ConceptMetadataEntity row = conceptRepo.findByConceptIri(subject.getConceptIri()).orElseThrow();
            row.setConceptType(ConceptType.KONCEPT);
            conceptRepo.saveAndFlush(row);
        });

        MaterializeResultDto result = materializeService.materialize(diagramId(), ontologyId());

        assertThat(result.failed()).hasSize(1);
        MaterializeResultDto.Failed failure = result.failed().get(0);
        // Whatever the classification, the client never sees raw exception text.
        assertThat(failure.message()).doesNotContain("Exception", "java.", "SQL", "select ", "insert ");
        if (failure.status() == 500) {
            assertThat(failure.message()).isEqualTo("Nastala neočekávaná chyba.");
        }
    }

    // ---- B2: cross-tenant IDOR ------------------------------------------------------------------
    // The diagram write endpoints authorize the ontology SLUG, but the concept IRIs travel inside the
    // request body. Without a graph-scope check, staging another user's concept as a node made materialize
    // edit — and via op 6 delete — concepts in that user's ontology. These pin each unguarded hop.

    /**
     * Hop 2 (apply): a foreign concept already persisted as a node (a bad row written before the ingress
     * guard existed) must be REFUSED at materialize, not applied. A foreign IRI is a rejected request, not
     * a stale reference — so it lands in {@code failed}, not {@code skippedStale}.
     */
    @Test
    void materialize_foreignGraphNode_refusedAndVictimConceptUntouched() {
        seedVictimOntology();
        ConceptMetadataEntity victimConcept = createVictimClass("Victim Secret");
        ConceptMetadataEntity localTarget = create(classModel("Local Target", true));
        List<String> before = triplesOf(victimGraph(), victimConcept.getConceptIri());
        assertThat(before).as("victim concept has triples to protect").isNotEmpty();

        // The attack: the victim's concept staged as a node on THIS diagram (whose ontology is GRAPH).
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setBroaderConcept(List.of(localTarget.getConceptIri()));
        stageNode(victimConcept.getConceptIri(), overlay);

        MaterializeResultDto result = materializeService.materialize(diagramId(), ontologyId());

        assertThat(result.materialized())
                .as("a foreign concept must never be materialized").isEmpty();
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).error()).isEqualTo("FOREIGN_CONCEPT");
        assertThat(result.failed().get(0).status()).isEqualTo(400);
        // The victim's RDF is byte-identical — the edit never reached their graph.
        assertThat(triplesOf(victimGraph(), victimConcept.getConceptIri()))
                .as("victim concept's triples unchanged").isEqualTo(before);
    }

    /**
     * Hop 3 (op 6): the worst primitive — {@code addBroaderOn} is a raw IRI from the overlay body and the
     * target need never have been on the canvas, so op 6 alone could edit AND delete an arbitrary concept.
     * The foreign target must be refused, and the VZTAH must survive (the delete is gated on the edit).
     */
    @Test
    void op6_foreignAddBroaderOn_refusedAndNothingDeleted() {
        seedVictimOntology();
        ConceptMetadataEntity victimClass = createVictimClass("Victim Class");
        ConceptMetadataEntity a = create(classModel("Op6 Foreign A", true));
        ConceptMetadataEntity b = create(classModel("Op6 Foreign B", true));
        ConceptMetadataEntity v = create(relModel("op6-foreign-rel", a.getConceptIri(), b.getConceptIri()));
        List<String> victimBefore = triplesOf(victimGraph(), victimClass.getConceptIri());

        // Op 6 on a LOCAL vztah, but pointing addBroaderOn at the VICTIM's class.
        DiagramPendingEdit convert = new DiagramPendingEdit();
        DiagramPendingEdit.ConvertToHierarchy marker = new DiagramPendingEdit.ConvertToHierarchy();
        marker.setAddBroaderOn(victimClass.getConceptIri());
        marker.setBroader(a.getConceptIri());
        convert.setConvertToHierarchy(marker);
        stageNode(v.getConceptIri(), convert);

        MaterializeResultDto result = materializeService.materialize(diagramId(), ontologyId());

        assertThat(result.materialized()).isEmpty();
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).error()).isEqualTo("FOREIGN_CONCEPT");
        // The victim's class was not edited...
        assertThat(triplesOf(victimGraph(), victimClass.getConceptIri()))
                .as("victim class untouched by the refused op-6").isEqualTo(victimBefore);
        // ...and the local VZTAH was NOT deleted (all-or-nothing: the delete follows the edit).
        assertThat(graph().containsResource(graph().getResource(v.getConceptIri())))
                .as("VZTAH survives a refused convert").isTrue();
    }

    /**
     * Op 6's other raw IRI is the opposite case: {@code broader} is only REFERENCED, becoming the object
     * of an {@code rdfs:subClassOf} written into our own graph, so a foreign one is allowed — being a
     * subclass of another ontology's class is the point of the foreign-node feature. Only
     * {@code addBroaderOn}, the class actually edited, must be ours.
     */
    @Test
    void op6_foreignBroader_isApplied() {
        seedVictimOntology();
        ConceptMetadataEntity victimClass = createVictimClass("Victim Broader");
        ConceptMetadataEntity a = create(classModel("Op6 Broader A", true));
        ConceptMetadataEntity b = create(classModel("Op6 Broader B", true));
        ConceptMetadataEntity v = create(relModel("op6-broader-rel", a.getConceptIri(), b.getConceptIri()));

        DiagramPendingEdit convert = new DiagramPendingEdit();
        DiagramPendingEdit.ConvertToHierarchy marker = new DiagramPendingEdit.ConvertToHierarchy();
        marker.setAddBroaderOn(a.getConceptIri());              // local target
        marker.setBroader(victimClass.getConceptIri());          // foreign broader
        convert.setConvertToHierarchy(marker);
        stageNode(v.getConceptIri(), convert);

        MaterializeResultDto result = materializeService.materialize(diagramId(), ontologyId());

        assertThat(result.failed()).isEmpty();
        // Our class gained the foreign super-class — a triple in OUR graph, naming theirs.
        assertThat(broaderOf(a.getConceptIri())).contains(victimClass.getConceptIri());
        // ...and op 6 completed: the VZTAH it replaces is gone.
        assertThat(graph().containsResource(graph().getResource(v.getConceptIri()))).isFalse();
    }

    /**
     * The guard that remains: op 6's {@code addBroaderOn} names the class the edit WRITES, so a foreign
     * one is the cross-tenant reach. Paired with the test above, this is what proves the rule is an
     * asymmetry between the two endpoints and not a blanket allow.
     */
    @Test
    void op6_foreignAddBroaderOn_refused() {
        seedVictimOntology();
        ConceptMetadataEntity victimClass = createVictimClass("Victim Target");
        String victimBefore = victimClass.getConceptIri();
        ConceptMetadataEntity a = create(classModel("Op6 Target A", true));
        ConceptMetadataEntity b = create(classModel("Op6 Target B", true));
        ConceptMetadataEntity v = create(relModel("op6-target-rel", a.getConceptIri(), b.getConceptIri()));

        DiagramPendingEdit convert = new DiagramPendingEdit();
        DiagramPendingEdit.ConvertToHierarchy marker = new DiagramPendingEdit.ConvertToHierarchy();
        marker.setAddBroaderOn(victimClass.getConceptIri());     // foreign target — refused
        marker.setBroader(a.getConceptIri());
        convert.setConvertToHierarchy(marker);
        stageNode(v.getConceptIri(), convert);

        MaterializeResultDto result = materializeService.materialize(diagramId(), ontologyId());

        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).error()).isEqualTo("FOREIGN_CONCEPT");
        assertThat(broaderOf(victimBefore)).doesNotContain(a.getConceptIri());
        assertThat(graph().containsResource(graph().getResource(v.getConceptIri())))
                .as("all-or-nothing: the VZTAH survives a refused convert").isTrue();
    }

    // ---- B2 layer 2: ownership assertion inside ConceptServiceImpl -------------------------------
    // Defence in depth. The diagram guards above scope by GRAPH; this one scopes by OWNER at the layer
    // that performs the write, so the next service-to-service caller of editConcept/deleteConcept cannot
    // silently reintroduce the hole. Driven directly against the service, bypassing the diagram entirely.

    /** With an authenticated non-owner in the context, edit and delete are refused. */
    @Test
    void editAndDelete_byNonOwner_areRefusedAtTheServiceLayer() {
        seedVictimOntology();
        ConceptMetadataEntity victimConcept = createVictimClass("Service Layer Victim");
        List<String> before = triplesOf(victimGraph(), victimConcept.getConceptIri());

        authenticateAs("attacker-user", false);
        try {
            ClassConceptEditModel edit = new ClassConceptEditModel();
            edit.setConceptType(ConceptType.TRIDA.getValue());
            edit.setBroaderConcept(List.of("https://slovnik.gov.cz/g/pojem/anything"));

            assertThatThrownBy(() -> conceptService.editConcept(victimConcept.getId(), edit))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> conceptService.deleteConcept(victimConcept.getId()))
                    .isInstanceOf(AccessDeniedException.class);
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(triplesOf(victimGraph(), victimConcept.getConceptIri()))
                .as("victim concept untouched by the refused service-layer calls").isEqualTo(before);
    }

    /** The owner is unaffected — the assertion gates non-owners, not the legitimate edit path. */
    @Test
    void edit_byOwner_isPermitted() {
        ConceptMetadataEntity a = create(classModel("Owner Edit A", true));
        ConceptMetadataEntity parent = create(classModel("Owner Edit Parent", true));

        authenticateAs(USER, false);
        try {
            ClassConceptEditModel edit = new ClassConceptEditModel();
            edit.setConceptType(ConceptType.TRIDA.getValue());
            edit.setBroaderConcept(List.of(parent.getConceptIri()));
            conceptService.editConcept(a.getId(), edit);
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(broaderOf(a.getConceptIri())).containsExactly(parent.getConceptIri());
    }

    /** An admin may modify another user's concept, matching {@code canModifyConcept}'s admin bypass. */
    @Test
    void edit_byAdmin_isPermittedOnAnotherUsersConcept() {
        seedVictimOntology();
        ConceptMetadataEntity victimConcept = createVictimClass("Admin Editable");
        ConceptMetadataEntity parent = createVictimClass("Admin Editable Parent");

        authenticateAs("admin-user", true);
        try {
            ClassConceptEditModel edit = new ClassConceptEditModel();
            edit.setConceptType(ConceptType.TRIDA.getValue());
            edit.setBroaderConcept(List.of(parent.getConceptIri()));
            conceptService.editConcept(victimConcept.getId(), edit);
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(victimGraph().listStatements(
                        victimGraph().getResource(victimConcept.getConceptIri()),
                        org.apache.jena.vocabulary.RDFS.subClassOf, (org.apache.jena.rdf.model.RDFNode) null)
                .toList()).isNotEmpty();
    }

    /** Put an authenticated principal in the SecurityContext, the way the JWT filter would. */
    private void authenticateAs(String userId, boolean admin) {
        SecurityUser user = new SecurityUser(userId, userId,
                admin ? List.of("ROLE_ADMIN") : List.of("ROLE_USER"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @TestConfiguration
    static class Beans {
        @Bean OutboxConfig outboxConfig() {
            OutboxConfig c = new OutboxConfig();
            c.setEnabled(true);
            return c;
        }
        @Bean InMemoryTdb2 inMemoryTdb2() { return new InMemoryTdb2(); }
        @Bean @Primary ConceptMetadataMapper conceptMetadataMapper() { return new ConceptMetadataMapperImpl(); }
        @Bean OutboxWriter outboxWriter(OutboxEntryRepository r) { return new OutboxWriter(r); }
        @Bean OutboxRelay outboxRelay(OutboxEntryRepository r, InMemoryTdb2 t, OutboxConfig c) {
            return new OutboxRelay(r, t, c);
        }
        @Bean OutboxRelayTrigger outboxRelayTrigger(OutboxRelay relay) { return new OutboxRelayTrigger(relay); }

        @Bean MetadataTouchService metadataTouchService(
                ConceptMetadataRepository conceptRepo, OntologyMetadataRepository ontologyRepo) {
            return new MetadataTouchService(conceptRepo, ontologyRepo);
        }

        @Bean ConceptServiceImpl conceptServiceImpl(
                ConceptMetadataRepository conceptRepo, OntologyMetadataRepository ontologyRepo,
                MetadataTouchService touchService,
                ConceptMetadataMapper mapper, InMemoryTdb2 tdb2,
                OutboxConfig outboxConfig, OutboxWriter writer, OutboxRelayTrigger trigger) {
            return new ConceptServiceImpl(
                    conceptRepo, ontologyRepo, touchService, mapper,
                    new ConceptCreator(), new ConceptEditor(), tdb2,
                    mock(OntologyDetailExtractor.class),
                    mock(com.dia.ismdtoolbackend.repository.CommentRepository.class),
                    mock(com.dia.ismdtoolbackend.client.NkdSparqlClient.class),
                    mock(ConceptDeviationComparator.class),
                    mock(RppSnapshotHolder.class),
                    mock(ReferencedConceptsEnricher.class),
                    outboxConfig, writer, trigger,
                    mock(com.dia.ismdtoolbackend.service.NkdSnapshotService.class),
                    new com.dia.ismdtoolbackend.service.snapshot.NkdLinkDetector(),
                    new com.dia.ismdtoolbackend.utility.published.WorkingCopySyncFields(),
                    mock(com.dia.ismdtoolbackend.service.snapshot.NkdSnapshotWarmer.class),
                    new com.dia.ismdtoolbackend.config.NkdConfig(),
                    mock(WorkingCopyDeviationServiceImpl.class));
        }

        @Bean DiagramChangeApplier diagramChangeApplier(
                ConceptServiceImpl conceptService, ConceptMetadataRepository conceptRepo,
                DiagramPendingEditRepository pendingEditRepo, DiagramEdgeRepository edgeRepo,
                InMemoryTdb2 tdb2) {
            return new DiagramChangeApplier(conceptService, conceptRepo, pendingEditRepo, edgeRepo, tdb2);
        }

        @Bean DiagramMaterializeService diagramMaterializeService(
                DiagramChangeApplier applier, DiagramPendingEditRepository pendingEditRepo) {
            return new DiagramMaterializeService(applier, pendingEditRepo);
        }
    }
}
