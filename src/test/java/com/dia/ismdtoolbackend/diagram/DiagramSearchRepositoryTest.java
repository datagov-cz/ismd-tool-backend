package com.dia.ismdtoolbackend.diagram;

import com.dia.ismdtoolbackend.config.JpaAuditingConfig;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.outbox.PostgresIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The PG-backed diagram text search that powers {@code type=DIAGRAM} results: a diagram is matched on its
 * ontology's slug, accent-insensitively (via {@code ismd_schema.unaccent}), and the total count agrees with
 * the row count. An ontology with no diagram never appears.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@EntityScan(basePackageClasses = DiagramEntity.class)
@EnableJpaRepositories(basePackageClasses = DiagramRepository.class)
@Import(JpaAuditingConfig.class)
class DiagramSearchRepositoryTest extends PostgresIntegrationTestBase {

    @Autowired private DiagramRepository diagramRepository;
    @Autowired private OntologyMetadataRepository ontologyRepository;

    private OntologyMetadataEntity ontology(String slug) {
        OntologyMetadataEntity o = new OntologyMetadataEntity();
        o.setSlug(slug);
        o.setGraphName("https://x/" + slug);
        o.setUserId("u1");
        o.setIsPublished(false);
        return ontologyRepository.save(o);
    }

    private void diagramFor(OntologyMetadataEntity o) {
        diagramFor(o, "Hlavní diagram");
    }

    private DiagramEntity diagramFor(OntologyMetadataEntity o, String name) {
        DiagramEntity d = new DiagramEntity();
        d.setName(name);
        d.setOntologyMetadata(o);
        return diagramRepository.save(d);
    }

    @Test
    void matchesDiagramByOntologySlug() {
        diagramFor(ontology("pracovni-pomer"));
        diagramFor(ontology("obchodni-rejstrik"));

        List<DiagramEntity> hits = diagramRepository.searchByOntologyText("pomer");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getOntologyMetadata().getSlug()).isEqualTo("pracovni-pomer");
        assertThat(diagramRepository.countSearchByOntologyText("pomer")).isEqualTo(1);
    }

    @Test
    void searchIsAccentInsensitive() {
        diagramFor(ontology("skoly-a-skolstvi"));

        // 'školy' (accented) matches the unaccented stored slug 'skoly'.
        assertThat(diagramRepository.searchByOntologyText("školy")).hasSize(1);
    }

    @Test
    void ontologyWithoutDiagram_isNotMatched() {
        ontology("no-diagram-here");   // no diagram created

        assertThat(diagramRepository.searchByOntologyText("no-diagram-here")).isEmpty();
        assertThat(diagramRepository.countSearchByOntologyText("no-diagram-here")).isZero();
    }

    @Test
    void emptyQuery_matchesAllDiagrams() {
        diagramFor(ontology("a-slovnik"));
        diagramFor(ontology("b-slovnik"));

        // '%%' matches every diagram — assert against the total row count rather than a fixed 2,
        // so committed rows leaked by sibling Testcontainer tests (shared singleton container) don't
        // make this brittle.
        List<DiagramEntity> all = diagramRepository.searchByOntologyText("");
        assertThat(all).hasSize((int) diagramRepository.count());
        assertThat(all).extracting(d -> d.getOntologyMetadata().getSlug())
                .contains("a-slovnik", "b-slovnik");
    }

    /**
     * Every diagram is its own search hit. They are distinct destinations with their own names, so
     * collapsing an ontology's diagrams to one row would hide all but the first.
     */
    @Test
    void everyDiagramOfAnOntologyIsItsOwnSearchRow() {
        OntologyMetadataEntity o = ontology("pracovni-pomer");
        diagramFor(o, "Hlavní diagram");
        diagramFor(o, "Pohled HR");

        assertThat(diagramRepository.searchByOntologyText("pomer"))
                .as("both canvases of the matched ontology are returned")
                .hasSize(2);
        assertThat(diagramRepository.countSearchByOntologyText("pomer")).isEqualTo(2);
    }

    /** The name is searchable in its own right — a user may recall the canvas, not the slovník. */
    @Test
    void matchesDiagramByItsOwnName() {
        OntologyMetadataEntity o = ontology("pracovni-pomer");
        diagramFor(o, "Hlavní diagram");
        diagramFor(o, "Pohled HR");

        List<DiagramEntity> hits = diagramRepository.searchByOntologyText("Pohled");

        assertThat(hits).singleElement()
                .extracting(DiagramEntity::getName).isEqualTo("Pohled HR");
    }

    /** Accent-insensitive on the name too, matching the slug behaviour. */
    @Test
    void matchesDiagramNameWithoutAccents() {
        diagramFor(ontology("jine-slovnik"), "Přehled vazeb");

        assertThat(diagramRepository.searchByOntologyText("prehled")).hasSize(1);
    }

    /** A name identifies a canvas to the user, so it must be unique inside its ontology. */
    @Test
    void duplicateNameWithinOneOntology_isRejected() {
        OntologyMetadataEntity o = ontology("dup-name");
        diagramFor(o, "Pohled HR");

        assertThatThrownBy(() -> diagramFor(o, "Pohled HR"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Uniqueness is per ontology, not global — two slovníky may each hold a "Hlavní diagram". */
    @Test
    void sameNameInDifferentOntologies_isAllowed() {
        OntologyMetadataEntity a = ontology("unikatni-slovnik-a");
        OntologyMetadataEntity b = ontology("unikatni-slovnik-b");

        diagramFor(a, "Hlavní diagram");
        diagramFor(b, "Hlavní diagram");

        // Scoped to the rows this test created: the class shares one schema across tests.
        assertThat(diagramRepository.findByOntologyMetadataIdOrderByIdAsc(a.getId())).hasSize(1);
        assertThat(diagramRepository.findByOntologyMetadataIdOrderByIdAsc(b.getId())).hasSize(1);
    }
}
