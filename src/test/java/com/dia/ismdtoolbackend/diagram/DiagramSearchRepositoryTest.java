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

    /** Search with paging wide open, the shape every assertion below cares about. */
    private List<DiagramRepository.DiagramSearchRow> search(String query) {
        return diagramRepository.searchByOntologyText(query, false, Integer.MAX_VALUE, 0);
    }

    private long count(String query) {
        return diagramRepository.countSearchByOntologyText(query, false);
    }

    @Test
    void matchesDiagramByOntologySlug() {
        diagramFor(ontology("pracovni-pomer"));
        diagramFor(ontology("obchodni-rejstrik"));

        List<DiagramRepository.DiagramSearchRow> hits = search("pomer");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getSlug()).isEqualTo("pracovni-pomer");
        assertThat(count("pomer")).isEqualTo(1);
    }

    @Test
    void searchIsAccentInsensitive() {
        diagramFor(ontology("skoly-a-skolstvi"));

        // 'školy' (accented) matches the unaccented stored slug 'skoly'.
        assertThat(search("školy")).hasSize(1);
    }

    @Test
    void ontologyWithoutDiagram_isNotMatched() {
        ontology("no-diagram-here");   // no diagram created

        assertThat(search("no-diagram-here")).isEmpty();
        assertThat(count("no-diagram-here")).isZero();
    }

    @Test
    void emptyQuery_matchesAllDiagrams() {
        diagramFor(ontology("a-slovnik"));
        diagramFor(ontology("b-slovnik"));

        // '%%' matches every diagram — assert against the total row count rather than a fixed 2,
        // so committed rows leaked by sibling Testcontainer tests (shared singleton container) don't
        // make this brittle.
        List<DiagramRepository.DiagramSearchRow> all = search("");
        assertThat(all).hasSize((int) diagramRepository.count());
        assertThat(all).extracting(DiagramRepository.DiagramSearchRow::getSlug)
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

        assertThat(search("pomer"))
                .as("both canvases of the matched ontology are returned")
                .hasSize(2);
        assertThat(count("pomer")).isEqualTo(2);
    }

    /** The name is searchable in its own right — a user may recall the canvas, not the slovník. */
    @Test
    void matchesDiagramByItsOwnName() {
        OntologyMetadataEntity o = ontology("pracovni-pomer");
        diagramFor(o, "Hlavní diagram");
        diagramFor(o, "Pohled HR");

        List<DiagramRepository.DiagramSearchRow> hits = search("Pohled");

        assertThat(hits).singleElement()
                .extracting(DiagramRepository.DiagramSearchRow::getName).isEqualTo("Pohled HR");
    }

    /** Accent-insensitive on the name too, matching the slug behaviour. */
    @Test
    void matchesDiagramNameWithoutAccents() {
        diagramFor(ontology("jine-slovnik"), "Přehled vazeb");

        assertThat(search("prehled")).hasSize(1);
    }

    /** A name identifies a canvas to the user, so it must be unique inside its ontology. */
    @Test
    void duplicateNameWithinOneOntology_isRejected() {
        OntologyMetadataEntity o = ontology("dup-name");
        diagramFor(o, "Pohled HR");

        assertThatThrownBy(() -> diagramFor(o, "Pohled HR"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * The page is cut in SQL. An unbounded fetch sliced in Java scales with the corpus rather than the
     * page, and the ORDER BY is total, so paging is stable rather than silently dropping rows.
     */
    @Test
    void paginatesInSql_withAStableOrder() {
        OntologyMetadataEntity o = ontology("stránkování");
        diagramFor(o, "Diagram A");
        diagramFor(o, "Diagram B");
        diagramFor(o, "Diagram C");

        List<DiagramRepository.DiagramSearchRow> firstTwo =
                diagramRepository.searchByOntologyText("stránkování", false, 2, 0);
        List<DiagramRepository.DiagramSearchRow> lastOne =
                diagramRepository.searchByOntologyText("stránkování", false, 2, 2);

        assertThat(firstTwo).hasSize(2);
        assertThat(lastOne).hasSize(1);
        // Every row appears exactly once across the pages — no overlap, nothing hidden.
        assertThat(java.util.stream.Stream.concat(firstTwo.stream(), lastOne.stream())
                .map(DiagramRepository.DiagramSearchRow::getName))
                .containsExactlyInAnyOrder("Diagram A", "Diagram B", "Diagram C");
        assertThat(count("stránkování")).isEqualTo(3);
    }

    /** One query serves both publish scopes; unpublishedOnly=true narrows on the join. */
    @Test
    void unpublishedOnlyFlag_narrowsToDrafts() {
        OntologyMetadataEntity draft = ontology("koncept-slovnik");
        OntologyMetadataEntity published = ontology("vydany-slovnik");
        published.setIsPublished(true);
        ontologyRepository.saveAndFlush(published);
        diagramFor(draft, "Draft diagram");
        diagramFor(published, "Published diagram");

        assertThat(diagramRepository.searchByOntologyText("slovnik", true, Integer.MAX_VALUE, 0))
                .extracting(DiagramRepository.DiagramSearchRow::getName)
                .containsExactly("Draft diagram");
        assertThat(diagramRepository.searchByOntologyText("slovnik", false, Integer.MAX_VALUE, 0))
                .extracting(DiagramRepository.DiagramSearchRow::getName)
                .containsExactlyInAnyOrder("Draft diagram", "Published diagram");
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
