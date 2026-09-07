package com.dia.ismdtoolbackend.diagram;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramConflictDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramPendingEditEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.exception.DiagramEditConflictException;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
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
import com.dia.ismdtoolbackend.service.DiagramService.ConflictResolution;
import com.dia.ismdtoolbackend.service.impl.DiagramLayoutReconciler;
import com.dia.ismdtoolbackend.service.impl.DiagramMaterializeService;
import com.dia.ismdtoolbackend.service.impl.DiagramServiceImpl;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
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
import org.springframework.context.annotation.Lazy;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * Cross-diagram conflict detection at Převzít.
 *
 * <p>Staged edits are per-diagram, so two canvases of one ontology can hold competing intent for the same
 * concept. Materializing one side moves that concept's {@code updatedAt} — the fingerprint the other
 * side's edit was stamped against — so the sibling would afterwards fail {@code STALE_BASE}, one concept
 * per attempt, with nothing explaining what moved underneath it. Detection reports the whole collision
 * before anything is written, so the user resolves it in a single decision.
 *
 * <p>Two properties matter most here and are asserted directly:
 *
 * <ol>
 *   <li><b>Detection runs before any write.</b> It must precede the per-change {@code REQUIRES_NEW} loop,
 *       or the changes ahead of the collision would already be committed to RDF when the 409 fires.</li>
 *   <li><b>A conflict is "the same concept staged twice", not "staged with different values".</b> Equal
 *       values still collide, because the first materialize bumps the fingerprint the second is pinned
 *       to — so treating them as compatible would trade a clear 409 for a confusing one later.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@Import({DiagramConflictIntegrationTest.Beans.class, TransactionTemplateConfig.class,
        com.dia.ismdtoolbackend.config.JpaAuditingConfig.class})
@EntityScan(basePackageClasses = {OutboxEntry.class, ConceptMetadataEntity.class, DiagramEntity.class})
@EnableJpaRepositories(basePackageClasses = {OutboxEntryRepository.class, ConceptMetadataRepository.class,
        DiagramRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DiagramConflictIntegrationTest extends PostgresIntegrationTestBase {

    private static final String GRAPH = "https://slovnik.gov.cz/conflict";
    private static final String SLUG = "conflict-ontology";
    private static final String USER = "user123";
    private static final String CLASS_A = GRAPH + "/pojem/trida-a";
    private static final String CLASS_B = GRAPH + "/pojem/trida-b";
    private static final String REL = GRAPH + "/pojem/vztah";

    /** A second ontology, to prove the conflict query never reaches across slovníky. */
    private static final String OTHER_GRAPH = "https://slovnik.gov.cz/conflict-other";
    private static final String OTHER_SLUG = "conflict-other-ontology";

    @Autowired private ConceptMetadataRepository conceptRepo;
    @Autowired private OntologyMetadataRepository ontologyRepo;
    @Autowired private DiagramRepository diagramRepo;
    @Autowired private DiagramPendingEditRepository pendingEditRepo;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private DiagramServiceImpl diagramService;
    @Autowired private DiagramMaterializeService materializeService;

    @BeforeEach
    void setUp() {
        reset(materializeService);
        txTemplate.executeWithoutResult(tx -> {
            pendingEditRepo.deleteAllInBatch();
            diagramRepo.deleteAllInBatch();
            conceptRepo.deleteAllInBatch();
            ontologyRepo.deleteAllInBatch();
        });
        txTemplate.executeWithoutResult(tx -> {
            OntologyMetadataEntity o = ontology(SLUG, GRAPH);
            seedConcept(o, CLASS_A, "Třída A", ConceptType.TRIDA);
            seedConcept(o, CLASS_B, "Třída B", ConceptType.TRIDA);
            seedConcept(o, REL, "vztah", ConceptType.VZTAH);
            ontology(OTHER_SLUG, OTHER_GRAPH);
        });
    }

    // ---- detection ------------------------------------------------------------------------------

    /** The headline case: the same concept staged on two canvases refuses to materialize. */
    @Test
    void sameConceptStagedOnTwoDiagrams_refusesWithAReport() {
        Long mine = diagram("Hlavní diagram");
        Long theirs = diagram("Pohled HR");
        stage(mine, REL, range(CLASS_A));
        stage(theirs, REL, range(CLASS_B));

        assertThatThrownBy(() -> diagramService.materialize(SLUG, mine, null))
                .isInstanceOf(DiagramEditConflictException.class)
                .satisfies(e -> {
                    DiagramConflictDto report = ((DiagramEditConflictException) e).getReport();
                    assertThat(report.conflicts()).singleElement()
                            .satisfies(c -> {
                                assertThat(c.conceptIri()).isEqualTo(REL);
                                assertThat(c.mine().getRange())
                                        .as("what THIS diagram staged")
                                        .isEqualTo(CLASS_A);
                                assertThat(c.theirs()).singleElement().satisfies(t -> {
                                    assertThat(t.diagramId()).isEqualTo(theirs);
                                    assertThat(t.diagramName())
                                            .as("the report must name the other canvas, or the user "
                                                    + "cannot tell which one to look at")
                                            .isEqualTo("Pohled HR");
                                    assertThat(t.pendingEdit().getRange()).isEqualTo(CLASS_B);
                                });
                            });
                });
    }

    /**
     * Nothing is written when the conflict fires — both sides' staged work survives untouched. This is
     * what makes the 409 safe to retry after the user chooses.
     */
    @Test
    void aRefusedMaterialize_leavesBothSidesStaged() {
        Long mine = diagram("Hlavní diagram");
        Long theirs = diagram("Pohled HR");
        stage(mine, REL, range(CLASS_A));
        stage(theirs, REL, range(CLASS_B));

        assertThatThrownBy(() -> diagramService.materialize(SLUG, mine, null))
                .isInstanceOf(DiagramEditConflictException.class);

        assertThat(pendingEditRepo.findByDiagramId(mine)).hasSize(1);
        assertThat(pendingEditRepo.findByDiagramId(theirs)).hasSize(1);
        verify(materializeService, never()).materialize(any(), any());
    }

    /**
     * Identical values still conflict. The first materialize bumps the concept's {@code updatedAt}, and
     * the sibling's fingerprint is stamped on first appearance and never refreshed — so the second side
     * would fail STALE_BASE regardless of the values agreeing.
     */
    @Test
    void identicalStagedValues_stillConflict() {
        Long mine = diagram("Hlavní diagram");
        Long theirs = diagram("Pohled HR");
        stage(mine, REL, range(CLASS_A));
        stage(theirs, REL, range(CLASS_A));   // the SAME value

        assertThatThrownBy(() -> diagramService.materialize(SLUG, mine, null))
                .as("a conflict is 'staged on both', not 'staged differently'")
                .isInstanceOf(DiagramEditConflictException.class);
    }

    /** Different concepts on two canvases are not a conflict — they cannot disturb each other. */
    @Test
    void differentConceptsOnTwoDiagrams_doNotConflict() {
        Long mine = diagram("Hlavní diagram");
        Long theirs = diagram("Pohled HR");
        stage(mine, CLASS_A, exactMatch(CLASS_B));
        stage(theirs, CLASS_B, exactMatch(CLASS_A));

        diagramService.materialize(SLUG, mine, null);

        verify(materializeService).materialize(eq(mine), eq(ontologyId()));
    }

    /** A lone diagram never conflicts with itself. */
    @Test
    void singleDiagram_neverConflicts() {
        Long only = diagram("Hlavní diagram");
        stage(only, REL, range(CLASS_A));

        diagramService.materialize(SLUG, only, null);

        verify(materializeService).materialize(eq(only), eq(ontologyId()));
    }

    /**
     * The conflict query is scoped to one ontology. Two slovníky staging the same-named concept are
     * unrelated, and a cross-ontology match would block a materialize for no reason.
     */
    @Test
    void aSiblingInAnotherOntology_isNotAConflict() {
        Long mine = diagram("Hlavní diagram");
        stage(mine, REL, range(CLASS_A));

        Long elsewhere = txTemplate.execute(tx -> {
            DiagramEntity d = new DiagramEntity();
            d.setOntologyMetadata(ontologyRepo.findBySlug(OTHER_SLUG).orElseThrow());
            d.setName("Cizí diagram");
            return diagramRepo.saveAndFlush(d).getId();
        });
        stageOn(elsewhere, OTHER_SLUG, REL, range(CLASS_A));

        diagramService.materialize(SLUG, mine, null);

        verify(materializeService)
                .materialize(eq(mine), eq(ontologyId()));
    }

    // ---- resolution -----------------------------------------------------------------------------

    /** DISCARD_THEIRS clears the sibling's edit and lets this diagram proceed. */
    @Test
    void discardTheirs_removesTheSiblingsEditAndProceeds() {
        Long mine = diagram("Hlavní diagram");
        Long theirs = diagram("Pohled HR");
        stage(mine, REL, range(CLASS_A));
        stage(theirs, REL, range(CLASS_B));

        diagramService.materialize(SLUG, mine, ConflictResolution.DISCARD_THEIRS);

        assertThat(pendingEditRepo.findByDiagramIdAndConceptIri(theirs, REL))
                .as("the sibling's competing edit is gone")
                .isEmpty();
    }

    /** DISCARD_MINE drops this diagram's own edit, leaving the sibling's intact. */
    @Test
    void discardMine_removesOwnEditAndLeavesTheSibling() {
        Long mine = diagram("Hlavní diagram");
        Long theirs = diagram("Pohled HR");
        stage(mine, REL, range(CLASS_A));
        stage(theirs, REL, range(CLASS_B));

        diagramService.materialize(SLUG, mine, ConflictResolution.DISCARD_MINE);

        assertThat(pendingEditRepo.findByDiagramIdAndConceptIri(mine, REL))
                .as("this diagram's conflicting edit is abandoned")
                .isEmpty();
        assertThat(pendingEditRepo.findByDiagramIdAndConceptIri(theirs, REL))
                .as("the sibling's is untouched — the user chose to keep it")
                .isPresent();
    }

    /**
     * The mirror of the test below, on the discarding side: choosing to abandon MY conflicting edit
     * abandons only that one. A resolution scoped to everything this diagram staged would throw away
     * uncontested work the user never offered up — and unlike the sibling case, every concept in that
     * set does have a row here, so the over-wide delete would really land.
     */
    @Test
    void discardMine_leavesOwnNonConflictingEdits() {
        Long mine = diagram("Hlavní diagram");
        Long theirs = diagram("Pohled HR");
        stage(mine, REL, range(CLASS_A));            // contested
        stage(mine, CLASS_A, exactMatch(CLASS_B));   // mine alone — nobody contests it
        stage(theirs, REL, range(CLASS_B));

        diagramService.materialize(SLUG, mine, ConflictResolution.DISCARD_MINE);

        assertThat(pendingEditRepo.findByDiagramIdAndConceptIri(mine, REL))
                .as("the contested edit is the one abandoned")
                .isEmpty();
        assertThat(pendingEditRepo.findByDiagramIdAndConceptIri(mine, CLASS_A))
                .as("uncontested work survives — resolving a conflict is not 'discard everything'")
                .isPresent();
    }

    /**
     * Resolution touches ONLY the concepts actually in conflict. A sibling's unrelated staged work is
     * not collateral — discarding a whole canvas's edits would be a much larger act than the user
     * agreed to.
     */
    @Test
    void discardTheirs_leavesTheSiblingsNonConflictingEdits() {
        Long mine = diagram("Hlavní diagram");
        Long theirs = diagram("Pohled HR");

        // BOTH concepts are staged here, but only REL is staged on the sibling too. The distinction
        // matters: a resolution scoped to "everything this diagram staged" rather than "the concepts
        // actually in conflict" would also reap the sibling's CLASS_A edit, which no one contested.
        stage(mine, REL, range(CLASS_A));
        stage(mine, CLASS_B, exactMatch(CLASS_A));
        stage(theirs, REL, range(CLASS_B));          // conflicts
        stage(theirs, CLASS_B, exactMatch(CLASS_A)); // ALSO staged by mine, but see below

        // Re-stage so only REL is genuinely contested: drop the sibling's CLASS_B edit and give it an
        // uncontested one instead.
        txTemplate.executeWithoutResult(tx ->
                pendingEditRepo.deleteByDiagramIdAndConceptIri(theirs, CLASS_B));
        stage(theirs, CLASS_A, exactMatch(CLASS_B)); // uncontested: mine never stages CLASS_A

        diagramService.materialize(SLUG, mine, ConflictResolution.DISCARD_THEIRS);

        assertThat(pendingEditRepo.findByDiagramIdAndConceptIri(theirs, REL))
                .as("the contested concept is discarded")
                .isEmpty();
        assertThat(pendingEditRepo.findByDiagramIdAndConceptIri(theirs, CLASS_A))
                .as("an edit no one contested survives — resolution is scoped to the conflict set, "
                        + "not to everything the materializing diagram happens to have staged")
                .isPresent();
    }

    /**
     * Three canvases staging the same concept. The conflict set is "every OTHER diagram of this
     * ontology", not "the other diagram" — so one DISCARD_THEIRS clears BOTH siblings in a single pass.
     * A resolution that addressed one sibling at a time (which the report's {@code theirs()} list invites,
     * since it names each diagram separately) would leave the second collision standing, and
     * {@code requireNoRemainingConflict} would then re-throw a 409 the user has already answered.
     */
    @Test
    void discardTheirs_clearsEverySiblingStagingTheSameConcept() {
        Long mine = diagram("Hlavní diagram");
        Long theirsOne = diagram("Pohled HR");
        Long theirsTwo = diagram("Pohled Finance");
        stage(mine, REL, range(CLASS_A));
        stage(theirsOne, REL, range(CLASS_B));
        stage(theirsTwo, REL, range(CLASS_A));
        stage(theirsTwo, CLASS_A, exactMatch(CLASS_B)); // uncontested: mine never stages CLASS_A

        diagramService.materialize(SLUG, mine, ConflictResolution.DISCARD_THEIRS);

        assertThat(pendingEditRepo.findByDiagramIdAndConceptIri(theirsOne, REL))
                .as("the first sibling's competing edit is gone")
                .isEmpty();
        assertThat(pendingEditRepo.findByDiagramIdAndConceptIri(theirsTwo, REL))
                .as("so is the second's — resolving against one sibling at a time would strand this one")
                .isEmpty();
        assertThat(pendingEditRepo.findByDiagramIdAndConceptIri(theirsTwo, CLASS_A))
                .as("still scoped to the conflict set, however many siblings it spans")
                .isPresent();
        assertThat(pendingEditRepo.findByDiagramIdAndConceptIri(mine, REL))
                .as("my edit is the one kept — that is what DISCARD_THEIRS means")
                .isPresent();
        verify(materializeService).materialize(eq(mine), eq(ontologyId()));
    }

    /**
     * The same three canvases, resolved the other way. DISCARD_MINE gives up only MY edit; both siblings
     * keep theirs and neither is materialized — materialize reads its work-list per diagram id, so the
     * siblings' staged work stays staged for whoever owns those canvases to apply themselves.
     */
    @Test
    void discardMine_leavesEverySiblingStagedAndMaterializesOnlyThisDiagram() {
        Long mine = diagram("Hlavní diagram");
        Long theirsOne = diagram("Pohled HR");
        Long theirsTwo = diagram("Pohled Finance");
        stage(mine, REL, range(CLASS_A));
        stage(mine, CLASS_B, exactMatch(CLASS_A)); // mine alone — nobody contests it
        stage(theirsOne, REL, range(CLASS_B));
        stage(theirsTwo, REL, range(CLASS_A));

        diagramService.materialize(SLUG, mine, ConflictResolution.DISCARD_MINE);

        assertThat(pendingEditRepo.findByDiagramIdAndConceptIri(mine, REL))
                .as("only my own contested edit is abandoned")
                .isEmpty();
        assertThat(pendingEditRepo.findByDiagramIdAndConceptIri(mine, CLASS_B))
                .as("my uncontested work survives")
                .isPresent();
        assertThat(pendingEditRepo.findByDiagramId(theirsOne))
                .as("the first sibling keeps its edit — DISCARD_MINE must not reach across canvases")
                .hasSize(1);
        assertThat(pendingEditRepo.findByDiagramId(theirsTwo))
                .as("and so does the second")
                .hasSize(1);
        verify(materializeService).materialize(eq(mine), eq(ontologyId()));
        verify(materializeService, never()).materialize(eq(theirsOne), any());
        verify(materializeService, never()).materialize(eq(theirsTwo), any());
    }

    // ---- authorization --------------------------------------------------------------------------

    /**
     * The ontology slug is authorized by the endpoint; the diagram id is not. Reaching another ontology's
     * diagram through this slug must fail before anything is read or written — on EVERY id-bearing path,
     * not just the one. They share {@code requireDiagramOf}, so covering only materialize would let the
     * call be dropped from any of the other three undetected.
     */
    @Test
    void addressingADiagramOfAnotherOntology_isRefusedOnEveryIdBearingPath() {
        Long elsewhere = txTemplate.execute(tx -> {
            DiagramEntity d = new DiagramEntity();
            d.setOntologyMetadata(ontologyRepo.findBySlug(OTHER_SLUG).orElseThrow());
            d.setName("Cizí diagram");
            return diagramRepo.saveAndFlush(d).getId();
        });

        assertThatThrownBy(() -> diagramService.materialize(SLUG, elsewhere, null))
                .as("materialize")
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> diagramService.getDiagram(SLUG, elsewhere))
                .as("detail")
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> diagramService.saveLayout(SLUG, elsewhere,
                new DiagramLayoutDto(0L, null, List.of(), null, null)))
                .as("layout save")
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> diagramService.deleteDiagram(SLUG, elsewhere))
                .as("delete")
                .isInstanceOf(EntityNotFoundException.class);

        assertThat(diagramRepo.findById(elsewhere))
                .as("the other ontology's diagram is untouched by any of the four attempts")
                .isPresent();
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private Long ontologyId() {
        return ontologyRepo.findBySlug(SLUG).orElseThrow().getId();
    }

    private DiagramPendingEdit range(String iri) {
        DiagramPendingEdit e = new DiagramPendingEdit();
        e.setRange(iri);
        return e;
    }

    private DiagramPendingEdit exactMatch(String iri) {
        DiagramPendingEdit e = new DiagramPendingEdit();
        e.setExactMatch(List.of(iri));
        return e;
    }

    private Long diagram(String name) {
        return txTemplate.execute(tx -> {
            DiagramEntity d = new DiagramEntity();
            d.setOntologyMetadata(ontologyRepo.findBySlug(SLUG).orElseThrow());
            d.setName(name);
            return diagramRepo.saveAndFlush(d).getId();
        });
    }

    private void stage(Long diagramId, String conceptIri, DiagramPendingEdit edit) {
        stageOn(diagramId, SLUG, conceptIri, edit);
    }

    private void stageOn(Long diagramId, String ontologySlug, String conceptIri, DiagramPendingEdit edit) {
        txTemplate.executeWithoutResult(tx -> {
            DiagramPendingEditEntity row = new DiagramPendingEditEntity();
            row.setDiagram(diagramRepo.findById(diagramId).orElseThrow());
            row.setOntologyMetadata(ontologyRepo.findBySlug(ontologySlug).orElseThrow());
            row.setConceptIri(conceptIri);
            row.setPendingEdit(edit);
            pendingEditRepo.saveAndFlush(row);
        });
    }

    private OntologyMetadataEntity ontology(String slug, String graphName) {
        OntologyMetadataEntity o = new OntologyMetadataEntity();
        o.setSlug(slug);
        o.setGraphName(graphName);
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
        c.setUserId(USER);
        c.setOntologyMetadata(ontology);
        c.setSlug(iri.substring(iri.lastIndexOf('/') + 1));
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        conceptRepo.save(c);
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

        /**
         * Materialize itself is mocked: this class is about what happens BEFORE the fan-out — whether it
         * is reached at all, and with what left staged. {@link DiagramMaterializeIntegrationTest} covers
         * the applying half against real RDF.
         */
        @Bean DiagramMaterializeService diagramMaterializeService() {
            return mock(DiagramMaterializeService.class);
        }

        @Bean DiagramServiceImpl diagramServiceImpl(
                DiagramRepository diagramRepo, OntologyMetadataRepository ontologyRepo,
                ConceptMetadataRepository conceptRepo, OntologyDetailExtractor extractor,
                JenaTDB2Repository tdb2, DiagramMaterializeService materializeService,
                DiagramLayoutReconciler reconciler, DiagramPendingEditRepository pendingEditRepo,
                DiagramMapper mapper, @Lazy DiagramServiceImpl self) {
            return new DiagramServiceImpl(diagramRepo, ontologyRepo, conceptRepo, extractor, tdb2,
                    materializeService, reconciler, pendingEditRepo, mapper, self);
        }
    }
}
