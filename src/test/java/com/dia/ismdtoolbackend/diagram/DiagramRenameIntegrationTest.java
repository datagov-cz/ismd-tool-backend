package com.dia.ismdtoolbackend.diagram;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramSummaryDto;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.DiagramNameConflictException;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.service.impl.DiagramServiceImpl;
import com.dia.ismdtoolbackend.testsupport.pg.DiagramServiceIT;
import com.dia.ismdtoolbackend.testsupport.pg.PgTestData;
import com.dia.ismdtoolbackend.testsupport.pg.PostgresIntegrationTestBase;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rename is its own endpoint, not a field on the layout save, and these are the cases that make it
 * behave differently from create.
 *
 * <p>Two of them are the traps that a create-path copy-paste would walk into: the uniqueness check has to
 * EXCLUDE the diagram being renamed (or a diagram collides with its own name and no rename ever saves),
 * and a blank name must be refused rather than silently taking create's "Nový diagram" default.
 */
@DiagramServiceIT
class DiagramRenameIntegrationTest extends PostgresIntegrationTestBase {

    private static final String SLUG = "rename-ontology";
    private static final String GRAPH = "https://x/rename-ontology";
    private static final String OTHER_SLUG = "rename-other-ontology";
    private static final String OTHER_GRAPH = "https://x/rename-other-ontology";

    @Autowired private DiagramRepository diagramRepository;
    @Autowired private DiagramServiceImpl service;
    @Autowired private PgTestData data;

    private OntologyMetadataEntity ontology;
    private OntologyMetadataEntity otherOntology;

    @BeforeEach
    void setUp() {
        ontology = data.createOntology(SLUG, GRAPH, "u1", false);
        otherOntology = data.createOntology(OTHER_SLUG, OTHER_GRAPH, "u1", false);
    }

    @Test
    void renamesTheDiagram() {
        Long id = createDiagram("Hlavní diagram");

        DiagramSummaryDto result = service.renameDiagram(SLUG, id, "Pohled HR");

        assertThat(result.name()).isEqualTo("Pohled HR");
        assertThat(diagramRepository.findById(id).orElseThrow().getName()).isEqualTo("Pohled HR");
    }

    /** Surrounding whitespace is trimmed, as on create — the stored name is what the picker shows. */
    @Test
    void trimsTheNewName() {
        Long id = createDiagram("Hlavní diagram");

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
        Long id = createDiagram("Hlavní diagram");

        DiagramSummaryDto result = service.renameDiagram(SLUG, id, "Hlavní diagram");

        assertThat(result.name()).isEqualTo("Hlavní diagram");
        assertThat(diagramRepository.findById(id).orElseThrow().getName()).isEqualTo("Hlavní diagram");
    }

    /** Trimming happens before the comparison, so a whitespace-only difference is still a no-op. */
    @Test
    void renamingToItsOwnNameWithPadding_isStillANoOp() {
        Long id = createDiagram("Hlavní diagram");

        assertThat(service.renameDiagram(SLUG, id, "  Hlavní diagram  ").name())
                .isEqualTo("Hlavní diagram");
    }

    /** A name another canvas in the same ontology already holds is a real conflict. */
    @Test
    void renamingOntoASiblingsName_conflicts() {
        Long mine = createDiagram("Hlavní diagram");
        createDiagram("Pohled HR");

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
        Long mine = createDiagram("Hlavní diagram");
        data.createDiagram(otherOntology, "Pohled HR");

        assertThat(service.renameDiagram(SLUG, mine, "Pohled HR").name()).isEqualTo("Pohled HR");
    }

    /**
     * The other trap: blank is create-only semantics. On create an absent name means "pick a default";
     * on rename it means the user cleared the field, and quietly renaming their canvas to "Nový diagram"
     * would be worse than refusing.
     */
    @Test
    void aBlankNameIsRefused_notDefaulted() {
        Long id = createDiagram("Hlavní diagram");

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
        Long elsewhere = data.createDiagram(otherOntology, "Cizí diagram");

        assertThatThrownBy(() -> service.renameDiagram(SLUG, elsewhere, "Pohled HR"))
                .isInstanceOf(EntityNotFoundException.class);

        assertThat(diagramRepository.findById(elsewhere).orElseThrow().getName())
                .isEqualTo("Cizí diagram");
    }

    // ---- fixtures -----------------------------------------------------------------------------------

    private Long createDiagram(String name) {
        return data.createDiagram(ontology, name);
    }
}
