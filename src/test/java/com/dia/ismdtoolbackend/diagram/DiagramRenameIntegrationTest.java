package com.dia.ismdtoolbackend.diagram;

import com.dia.ismdtoolbackend.config.JpaAuditingConfig;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramSummaryDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.DiagramNameConflictException;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.outbox.PostgresIntegrationTestBase;
import com.dia.ismdtoolbackend.outbox.TransactionTemplateConfig;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.impl.DiagramLayoutReconciler;
import com.dia.ismdtoolbackend.service.OntologyLabelLookup;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Rename is its own endpoint, not a field on the layout save, and these are the cases that make it
 * behave differently from create.
 *
 * <p>Two of them are the traps that a create-path copy-paste would walk into: the uniqueness check has to
 * EXCLUDE the diagram being renamed (or a diagram collides with its own name and no rename ever saves),
 * and a blank name must be refused rather than silently taking create's "Nový diagram" default.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@EntityScan(basePackageClasses = {DiagramEntity.class, ConceptMetadataEntity.class})
@EnableJpaRepositories(basePackageClasses = {DiagramRepository.class, ConceptMetadataRepository.class})
@Import({JpaAuditingConfig.class, DiagramRenameIntegrationTest.Beans.class, TransactionTemplateConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DiagramRenameIntegrationTest extends PostgresIntegrationTestBase {

    private static final String SLUG = "rename-ontology";
    private static final String GRAPH = "https://x/rename-ontology";
    private static final String OTHER_SLUG = "rename-other-ontology";
    private static final String OTHER_GRAPH = "https://x/rename-other-ontology";

    @Autowired private DiagramRepository diagramRepository;
    @Autowired private OntologyMetadataRepository ontologyRepository;
    @Autowired private ConceptMetadataRepository conceptRepository;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private DiagramServiceImpl service;

    @BeforeEach
    void setUp() {
        txTemplate.executeWithoutResult(tx -> {
            diagramRepository.deleteAllInBatch();
            conceptRepository.deleteAllInBatch();
            ontologyRepository.deleteAllInBatch();
        });
        txTemplate.executeWithoutResult(tx -> {
            ontology(SLUG, GRAPH);
            ontology(OTHER_SLUG, OTHER_GRAPH);
        });
    }

    @Test
    void renamesTheDiagram() {
        Long id = diagram("Hlavní diagram");

        DiagramSummaryDto result = service.renameDiagram(SLUG, id, "Pohled HR");

        assertThat(result.name()).isEqualTo("Pohled HR");
        assertThat(diagramRepository.findById(id).orElseThrow().getName()).isEqualTo("Pohled HR");
    }

    /** Surrounding whitespace is trimmed, as on create — the stored name is what the picker shows. */
    @Test
    void trimsTheNewName() {
        Long id = diagram("Hlavní diagram");

        assertThat(service.renameDiagram(SLUG, id, "  Pohled HR  ").name()).isEqualTo("Pohled HR");
        assertThat(diagramRepository.findById(id).orElseThrow().getName()).isEqualTo("Pohled HR");
    }

    /**
     * THE trap. The uniqueness check must exclude the diagram being renamed. A FE that sends the whole
     * name field on every save — or a user who re-confirms the current name — would otherwise get a 409
     * telling them their own name is taken, and no rename could ever be saved.
     */
    @Test
    void renamingToItsOwnCurrentName_isANoOpNotASelfConflict() {
        Long id = diagram("Hlavní diagram");

        DiagramSummaryDto result = service.renameDiagram(SLUG, id, "Hlavní diagram");

        assertThat(result.name()).isEqualTo("Hlavní diagram");
        assertThat(diagramRepository.findById(id).orElseThrow().getName()).isEqualTo("Hlavní diagram");
    }

    /** Trimming happens before the comparison, so a whitespace-only difference is still a no-op. */
    @Test
    void renamingToItsOwnNameWithPadding_isStillANoOp() {
        Long id = diagram("Hlavní diagram");

        assertThat(service.renameDiagram(SLUG, id, "  Hlavní diagram  ").name())
                .isEqualTo("Hlavní diagram");
    }

    /** A name another canvas in the same ontology already holds is a real conflict. */
    @Test
    void renamingOntoASiblingsName_conflicts() {
        Long mine = diagram("Hlavní diagram");
        diagram("Pohled HR");

        assertThatThrownBy(() -> service.renameDiagram(SLUG, mine, "Pohled HR"))
                .isInstanceOf(DiagramNameConflictException.class);

        assertThat(diagramRepository.findById(mine).orElseThrow().getName())
                .as("the failed rename left the original name in place")
                .isEqualTo("Hlavní diagram");
    }

    /**
     * Names are unique per ONTOLOGY, not globally — two slovníky may each have a "Hlavní diagram", so a
     * name held only by another ontology's canvas must not block this rename.
     */
    @Test
    void aNameHeldInAnotherOntology_doesNotConflict() {
        Long mine = diagram("Hlavní diagram");
        diagramIn(OTHER_SLUG, "Pohled HR");

        assertThat(service.renameDiagram(SLUG, mine, "Pohled HR").name()).isEqualTo("Pohled HR");
    }

    /**
     * The other trap: blank is create-only semantics. On create an absent name means "pick a default";
     * on rename it means the user cleared the field, and quietly renaming their canvas to "Nový diagram"
     * would be worse than refusing.
     */
    @Test
    void aBlankNameIsRefused_notDefaulted() {
        Long id = diagram("Hlavní diagram");

        for (String blank : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> service.renameDiagram(SLUG, id, blank))
                    .as("blank input: %s", blank == null ? "null" : "\"" + blank + "\"")
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(diagramRepository.findById(id).orElseThrow().getName())
                .as("no blank attempt renamed anything, and none defaulted to \"Nový diagram\"")
                .isEqualTo("Hlavní diagram");
    }

    /**
     * The slug is authorized by the endpoint but the diagram id is not, so rename must re-assert the
     * pairing exactly as every other id-bearing path does — otherwise it is an IDOR that renames another
     * ontology's canvas through your own slug.
     */
    @Test
    void renamingADiagramOfAnotherOntology_isRefused() {
        Long elsewhere = diagramIn(OTHER_SLUG, "Cizí diagram");

        assertThatThrownBy(() -> service.renameDiagram(SLUG, elsewhere, "Pohled HR"))
                .isInstanceOf(EntityNotFoundException.class);

        assertThat(diagramRepository.findById(elsewhere).orElseThrow().getName())
                .isEqualTo("Cizí diagram");
    }

    // ---- fixtures -----------------------------------------------------------------------------------

    private void ontology(String slug, String graphName) {
        OntologyMetadataEntity o = new OntologyMetadataEntity();
        o.setSlug(slug);
        o.setGraphName(graphName);
        o.setUserId("u1");
        o.setIsPublished(false);
        ontologyRepository.saveAndFlush(o);
    }

    private Long diagram(String name) {
        return diagramIn(SLUG, name);
    }

    private Long diagramIn(String ontologySlug, String name) {
        return txTemplate.execute(tx -> {
            DiagramEntity d = new DiagramEntity();
            d.setOntologyMetadata(ontologyRepository.findBySlug(ontologySlug).orElseThrow());
            d.setName(name);
            return diagramRepository.saveAndFlush(d).getId();
        });
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