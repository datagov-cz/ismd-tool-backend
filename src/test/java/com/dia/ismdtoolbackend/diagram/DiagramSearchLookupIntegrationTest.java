package com.dia.ismdtoolbackend.diagram;

import com.dia.ismdtoolbackend.config.JpaAuditingConfig;
import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.SearchType;
import com.dia.ismdtoolbackend.outbox.PostgresIntegrationTestBase;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.search.DiagramSearchLookup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression cover for the diagram-search lazy-loading bug (finding F4).
 *
 * <p>{@code searchByOntologyText} is a NATIVE query, so it cannot fetch-join, and
 * {@link DiagramEntity#getOntologyMetadata()} is {@code LAZY}. Mapping the row to a DTO outside a
 * persistence session therefore threw {@code LazyInitializationException} — which aborted the whole
 * ISMD search provider and silently discarded its ontology and concept results too.
 *
 * <p>{@link DiagramSearchLookup} exists to do that mapping inside its own read-only transaction.
 *
 * <p><strong>Every test here is {@code NOT_SUPPORTED}</strong>, which suspends the transaction
 * {@code @DataJpaTest} would otherwise wrap around it. That matters: inside the test's own
 * transaction the session stays open regardless, so the assertions pass even with the lookup's
 * {@code @Transactional} deleted — the test would prove nothing. Suspended, the lookup's own
 * annotation is the only thing keeping a session open, which is exactly the production condition.
 * Verified by mutation: removing {@code @Transactional} from {@code search()} fails these tests.
 *
 * <p>Data is committed rather than rolled back, so each test cleans up after itself.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@EntityScan(basePackageClasses = DiagramEntity.class)
@EnableJpaRepositories(basePackageClasses = DiagramRepository.class)
@Import({JpaAuditingConfig.class, DiagramSearchLookup.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DiagramSearchLookupIntegrationTest extends PostgresIntegrationTestBase {

    @Autowired private DiagramSearchLookup lookup;
    @Autowired private DiagramRepository diagramRepository;
    @Autowired private OntologyMetadataRepository ontologyRepository;

    @AfterEach
    void cleanUp() {
        // Writes commit (no test transaction to roll back), so clear them between tests.
        diagramRepository.deleteAll();
        ontologyRepository.deleteAll();
    }

    private OntologyMetadataEntity ontology(String slug, String graphName) {
        OntologyMetadataEntity o = new OntologyMetadataEntity();
        o.setSlug(slug);
        o.setGraphName(graphName);
        o.setUserId("u1");
        o.setIsPublished(false);
        return ontologyRepository.save(o);
    }

    private void diagramFor(OntologyMetadataEntity o) {
        diagramFor(o, "Hlavní diagram");
    }

    private void diagramFor(OntologyMetadataEntity o, String name) {
        DiagramEntity d = new DiagramEntity();
        d.setName(name);
        d.setOntologyMetadata(o);
        diagramRepository.save(d);
    }

    private OntologyMetadataEntity publishedOntology(String slug, String graphName) {
        OntologyMetadataEntity o = new OntologyMetadataEntity();
        o.setSlug(slug);
        o.setGraphName(graphName);
        o.setUserId("u1");
        o.setIsPublished(true);
        return ontologyRepository.save(o);
    }

    /**
     * A diagram has no publish state of its own — it mirrors its ontology's. An UNPUBLISHED-scoped
     * search must therefore exclude diagrams of published ontologies, in both the rows and the count.
     */
    @Test
    void unpublishedFilterExcludesPublishedOntologiesDiagrams() {
        diagramFor(publishedOntology("published-pomer", "https://x/published-pomer"));
        diagramFor(ontology("draft-pomer", "https://x/draft-pomer"));   // ontology() is unpublished

        List<SearchResultDto> unpublishedOnly = lookup.search("pomer", Boolean.FALSE);

        assertThat(unpublishedOnly).extracting(SearchResultDto::getSlug)
                .containsExactly("draft-pomer");
        assertThat(lookup.count("pomer", Boolean.FALSE)).isEqualTo(1);
    }

    /** No publish filter (source=ISMD/ALL) returns diagrams regardless of their ontology's state. */
    @Test
    void noPublishedFilterReturnsBothPublishedAndUnpublished() {
        diagramFor(publishedOntology("published-pomer", "https://x/published-pomer"));
        diagramFor(ontology("draft-pomer", "https://x/draft-pomer"));

        assertThat(lookup.search("pomer", null)).extracting(SearchResultDto::getSlug)
                .containsExactlyInAnyOrder("published-pomer", "draft-pomer");
        assertThat(lookup.count("pomer", null)).isEqualTo(2);
    }

    /** {@code isPublished} carries the ontology's value, so the FE can tell the two apart. */
    @Test
    void isPublishedMirrorsTheOntology() {
        diagramFor(publishedOntology("published-pomer", "https://x/published-pomer"));
        diagramFor(ontology("draft-pomer", "https://x/draft-pomer"));

        assertThat(lookup.search("pomer", null))
                .extracting(SearchResultDto::getSlug, SearchResultDto::getIsPublished)
                .containsExactlyInAnyOrder(
                        org.assertj.core.api.Assertions.tuple("published-pomer", true),
                        org.assertj.core.api.Assertions.tuple("draft-pomer", false));
    }

    @Test
    void mapsOntologyFieldsWithoutLazyInitializationException() {
        diagramFor(ontology("pracovni-pomer", "https://x/pracovni-pomer"));

        List<SearchResultDto> hits = lookup.search("pomer", null);

        assertThat(hits).hasSize(1);
        SearchResultDto dto = hits.get(0);
        // Each of these dereferences the LAZY ontologyMetadata association.
        assertThat(dto.getIri()).isEqualTo("https://x/pracovni-pomer#diagram-" + dto.getId());
        assertThat(dto.getDiagramId())
                .as("the routing key: /api/diagram/{slug}/{diagramId}/detail")
                .isEqualTo(dto.getId());
        assertThat(dto.getSlug()).isEqualTo("pracovni-pomer");
        assertThat(dto.getOntologyIri()).isEqualTo("https://x/pracovni-pomer");
        assertThat(dto.getType()).isEqualTo(SearchType.DIAGRAM);
    }

    /** The synthetic IRI keeps a diagram from dedup-colliding with its own ontology's row. */
    @Test
    void syntheticIriDiffersFromTheOntologyIri() {
        diagramFor(ontology("obchodni-rejstrik", "https://x/obchodni-rejstrik"));

        SearchResultDto dto = lookup.search("rejstrik", null).get(0);

        assertThat(dto.getIri()).isNotEqualTo(dto.getOntologyIri());
        assertThat(dto.getIri()).contains("#diagram-");
    }

    /** A graphless ontology still yields a non-null dedup key. */
    @Test
    void graphlessOntologyFallsBackToDiagramIdKey() {
        diagramFor(ontology("bez-grafu", null));

        SearchResultDto dto = lookup.search("bez-grafu", null).get(0);

        assertThat(dto.getIri()).isNotNull().startsWith("diagram:");
        assertThat(dto.getOntologyIri()).isNull();
    }

    @Test
    void countAgreesWithRowCount() {
        diagramFor(ontology("pracovni-pomer", "https://x/pracovni-pomer"));
        diagramFor(ontology("pomerne-jina", "https://x/pomerne-jina"));

        assertThat(lookup.count("pomer", null)).isEqualTo(2);
        assertThat(lookup.search("pomer", null)).hasSize(2);
    }

    /**
     * Two canvases of one ontology are two destinations, so each is its own row — with an IRI that
     * separates them (a shared one would dedup-collapse them) and its own name as the label.
     */
    @Test
    void twoDiagramsOfOneOntology_areTwoDistinctRows() {
        OntologyMetadataEntity o = ontology("pracovni-pomer", "https://x/pracovni-pomer");
        diagramFor(o, "Hlavní diagram");
        diagramFor(o, "Pohled HR");

        List<SearchResultDto> hits = lookup.search("pomer", null);

        assertThat(hits).hasSize(2);
        assertThat(hits).extracting(SearchResultDto::getLabel)
                .containsExactlyInAnyOrder("Hlavní diagram", "Pohled HR");
        assertThat(hits).extracting(SearchResultDto::getIri)
                .as("a shared synthetic IRI would collapse the two rows on a type=null pass")
                .doesNotHaveDuplicates();
        assertThat(lookup.count("pomer", null)).isEqualTo(2);
    }
}