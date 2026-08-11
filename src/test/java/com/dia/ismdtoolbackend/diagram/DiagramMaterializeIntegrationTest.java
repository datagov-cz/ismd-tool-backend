package com.dia.ismdtoolbackend.diagram;

import com.dia.ismdtoolbackend.controller.dto.diagram.MaterializeResultDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
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
import com.dia.ismdtoolbackend.repository.DiagramNodeRepository;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.impl.ConceptDeviationComparator;
import com.dia.ismdtoolbackend.service.impl.ConceptServiceImpl;
import com.dia.ismdtoolbackend.service.impl.DiagramChangeApplier;
import com.dia.ismdtoolbackend.service.impl.DiagramMaterializeService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Autowired private ConceptMetadataRepository conceptRepo;
    @Autowired private OntologyMetadataRepository ontologyRepo;
    @Autowired private OutboxEntryRepository outboxRepo;
    @Autowired private DiagramRepository diagramRepo;
    @Autowired private DiagramNodeRepository nodeRepo;
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

    // ---- diagram seeding ------------------------------------------------------------------------

    /** Provision the diagram and stage an overlay on one node (captures the base-updatedAt fingerprint). */
    private Long stageNode(String conceptIri, DiagramPendingEdit overlay) {
        return txTemplate.execute(tx -> {
            DiagramEntity diagram = diagramRepo.findByOntologyMetadataSlug("g-ontology")
                    .orElseGet(() -> {
                        DiagramEntity d = new DiagramEntity();
                        d.setOntologyMetadata(ontologyRepo.findBySlug("g-ontology").orElseThrow());
                        return diagramRepo.save(d);
                    });
            DiagramNodeEntity node = new DiagramNodeEntity();
            node.setDiagram(diagram);
            node.setBacking(DiagramNodeBacking.ISMD_CONCEPT);
            node.setConceptIri(conceptIri);
            node.setPosX(0.0);
            node.setPosY(0.0);
            if (overlay != null) {
                overlay.setBaseUpdatedAt(conceptRepo.findByConceptIri(conceptIri)
                        .map(ConceptMetadataEntity::getUpdatedAt).orElse(null));
                node.setPendingEdit(overlay);
            }
            diagram.addNode(node);
            DiagramNodeEntity saved = nodeRepo.saveAndFlush(node);
            return saved.getId();
        });
    }

    private Long diagramId() {
        return diagramRepo.findByOntologyMetadataSlug("g-ontology").orElseThrow().getId();
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

        MaterializeResultDto result = materializeService.materialize(diagramId());

        assertThat(result.materialized()).hasSize(1);
        assertThat(result.materialized().get(0).op()).isEqualTo(DiagramOp.SWAP_DIRECTION);
        assertThat(result.failed()).isEmpty();

        // V1: the IRI is unchanged (no rename on Převzít).
        assertThat(conceptRepo.findById(rel.getId()).orElseThrow().getConceptIri()).isEqualTo(relIri);
        // V4/V5: the VZTAH still carries its classification type in RDF (not stripped by the field-scoped edit).
        Model g = graph();
        assertThat(g.getResource(relIri).listProperties(org.apache.jena.vocabulary.RDF.type).toList())
                .as("classification/type triples survive a structural-only materialize").isNotEmpty();
        // The staged overlay was cleared on success.
        assertThat(nodeRepo.findByDiagramIdAndConceptIri(diagramId(), relIri).orElseThrow().getPendingEdit())
                .isNull();
    }

    // Stale-base: the concept was edited (via normal /api/concept) after the overlay was staged → 409.
    @Test
    void materialize_staleBase_reports409() {
        ConceptMetadataEntity a = create(classModel("Stale A", true));
        ConceptMetadataEntity b = create(classModel("Stale B", true));

        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setBroaderConcept(List.of(b.getConceptIri()));
        Long nodeId = stageNode(a.getConceptIri(), overlay);

        // Simulate a concurrent edit of A landing after the overlay was staged: the staged fingerprint no
        // longer matches the concept's current updatedAt. Rewrite the persisted overlay's baseUpdatedAt to a
        // value that differs from the row's — exactly the state a real /api/concept edit would leave behind.
        txTemplate.executeWithoutResult(tx -> {
            DiagramNodeEntity node = nodeRepo.findById(nodeId).orElseThrow();
            DiagramPendingEdit staged = node.getPendingEdit();
            staged.setBaseUpdatedAt(LocalDateTime.of(2000, 1, 1, 0, 0));   // clearly ≠ current updatedAt
            node.setPendingEdit(staged);
            nodeRepo.saveAndFlush(node);
        });

        MaterializeResultDto result = materializeService.materialize(diagramId());

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

        MaterializeResultDto result = materializeService.materialize(diagramId());

        assertThat(result.skippedStale()).hasSize(1);
        assertThat(result.materialized()).isEmpty();
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

        MaterializeResultDto result = materializeService.materialize(diagramId());

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

        MaterializeResultDto result = materializeService.materialize(diagramId());

        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).error()).isEqualTo("CASCADE_CONFLICT");
        assertThat(result.failed().get(0).status()).isEqualTo(409);
        // All-or-nothing: V is NOT deleted, and no broader was added on A.
        assertThat(graph().containsResource(graph().getResource(v.getConceptIri())))
                .as("VZTAH V untouched — cascade guard blocked the convert").isTrue();
        // Overlay stays staged for a retry after the user resolves the reference.
        assertThat(nodeRepo.findByDiagramIdAndConceptIri(diagramId(), v.getConceptIri())
                .orElseThrow().getPendingEdit()).isNotNull();
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

        assertThat(materializeService.materialize(diagramId()).materialized()).hasSize(1);

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

        assertThat(materializeService.materialize(diagramId()).materialized()).hasSize(1);

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
        Long nodeId = stageNode(v.getConceptIri(), convert);

        // First materialize: succeeds, deletes V, clears the overlay.
        assertThat(materializeService.materialize(diagramId()).materialized()).hasSize(1);
        assertThat(graph().containsResource(graph().getResource(v.getConceptIri()))).isFalse();

        // Re-stage the same convert on the now-orphaned node and materialize again.
        txTemplate.executeWithoutResult(tx -> {
            DiagramNodeEntity node = nodeRepo.findById(nodeId).orElseThrow();
            DiagramPendingEdit again = new DiagramPendingEdit();
            DiagramPendingEdit.ConvertToHierarchy m2 = new DiagramPendingEdit.ConvertToHierarchy();
            m2.setAddBroaderOn(a.getConceptIri());
            m2.setBroader(broader.getConceptIri());
            again.setConvertToHierarchy(m2);
            node.setPendingEdit(again);
            nodeRepo.saveAndFlush(node);
        });

        MaterializeResultDto retry = materializeService.materialize(diagramId());

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

        MaterializeResultDto result = materializeService.materialize(diagramId());

        assertThat(result.materialized())
                .anyMatch(m -> good.getConceptIri().equals(m.conceptIri()));
        assertThat(result.failed())
                .anyMatch(f -> bad.getConceptIri().equals(f.conceptIri()));
        // The good change's overlay was cleared; the failed one's is retained for a fix-and-retry.
        assertThat(nodeRepo.findByDiagramIdAndConceptIri(diagramId(), good.getConceptIri())
                .orElseThrow().getPendingEdit()).isNull();
        assertThat(nodeRepo.findByDiagramIdAndConceptIri(diagramId(), bad.getConceptIri())
                .orElseThrow().getPendingEdit()).isNotNull();
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

        MaterializeResultDto result = materializeService.materialize(diagramId());

        assertThat(result.failed()).hasSize(1);
        MaterializeResultDto.Failed failure = result.failed().get(0);
        // Whatever the classification, the client never sees raw exception text.
        assertThat(failure.message()).doesNotContain("Exception", "java.", "SQL", "select ", "insert ");
        if (failure.status() == 500) {
            assertThat(failure.message()).isEqualTo("Nastala neočekávaná chyba.");
        }
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

        @Bean ConceptServiceImpl conceptServiceImpl(
                ConceptMetadataRepository conceptRepo, OntologyMetadataRepository ontologyRepo,
                ConceptMetadataMapper mapper, InMemoryTdb2 tdb2,
                OutboxConfig outboxConfig, OutboxWriter writer, OutboxRelayTrigger trigger) {
            return new ConceptServiceImpl(
                    conceptRepo, ontologyRepo, mapper,
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
                DiagramNodeRepository nodeRepo, InMemoryTdb2 tdb2) {
            return new DiagramChangeApplier(conceptService, conceptRepo, nodeRepo, tdb2);
        }

        @Bean DiagramMaterializeService diagramMaterializeService(
                DiagramChangeApplier applier, DiagramNodeRepository nodeRepo) {
            return new DiagramMaterializeService(applier, nodeRepo);
        }
    }
}
