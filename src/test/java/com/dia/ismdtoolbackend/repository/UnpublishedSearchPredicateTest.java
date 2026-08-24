package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.outbox.PostgresIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code UNPUBLISHED} ("rozpracovaný") search filter, run as native SQL against REAL Postgres —
 * the queries use {@code ismd_schema.unaccent}, so a mocked repository proves nothing about them.
 *
 * <p>"Rozpracovaný" means <em>local</em>, not draft-flagged: NKD is the published world and ISMD is
 * the workbench. {@code is_published = true} marks a row whose IRI resolves in NKD — the working-copy
 * marker — so it is NOT a "finished" flag, and filtering on it hid entire uploaded vocabularies. On
 * real data every uploaded ontology is {@code true} top to bottom until someone edits a concept
 * (which severs that one concept to {@code false}), so a draft-state filter returned almost nothing.
 * Draft state is surfaced through ordering instead.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
class UnpublishedSearchPredicateTest extends PostgresIntegrationTestBase {

    private static final String Q = "rozpracovany";

    @Autowired
    private ConceptMetadataRepository conceptRepository;

    @Autowired
    private OntologyMetadataRepository ontologyRepository;

    @BeforeEach
    void clean() {
        conceptRepository.deleteAll();
        ontologyRepository.deleteAll();
    }

    private OntologyMetadataEntity ontology(String slug, Boolean isPublished) {
        OntologyMetadataEntity o = new OntologyMetadataEntity();
        o.setSlug(slug);
        o.setGraphName("https://example.test/" + slug);
        o.setUserId("user-1");
        o.setIsPublished(isPublished);
        o.setCreatedAt(LocalDateTime.now());
        o.setUpdatedAt(LocalDateTime.now());
        return ontologyRepository.save(o);
    }

    private ConceptMetadataEntity concept(OntologyMetadataEntity o, String name, Boolean isPublished) {
        ConceptMetadataEntity c = new ConceptMetadataEntity();
        c.setConceptName(name);
        c.setSlug(name);
        c.setConceptType(ConceptType.TRIDA);
        c.setGraphName(o.getGraphName());
        c.setConceptIri(o.getGraphName() + "/pojem/" + name);
        c.setUserId("user-1");
        c.setIsPublished(isPublished);
        c.setOntologyMetadata(o);
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        return conceptRepository.save(c);
    }

    private List<String> unpublishedConceptNames() {
        return conceptRepository
                .searchByTextUnpublished(Q, false, List.of(), false, null)
                .stream()
                .map(ConceptMetadataEntity::getConceptName)
                .toList();
    }

    /**
     * The reported bug, in the exact shape of A124: a freshly uploaded working copy — ontology
     * {@code is_published = true} and EVERY concept {@code true}, because nothing has been edited yet.
     * This is what real uploaded data looks like (5 of 6 local ontologies), and a draft-state filter
     * returned nothing for it.
     */
    @Test
    void includesUntouchedWorkingCopyWithNoDraftsAnywhere() {
        OntologyMetadataEntity a124 = ontology("rozpracovany-a124", true);
        concept(a124, "rozpracovany-a124-concept", true);

        assertThat(ontologyRepository.searchByTextUnpublished(Q))
                .extracting(OntologyMetadataEntity::getSlug)
                .containsExactly("rozpracovany-a124");
        assertThat(unpublishedConceptNames()).containsExactly("rozpracovany-a124-concept");
        assertThat(ontologyRepository.findAllGraphNames()).contains(a124.getGraphName());
    }

    /** Plain drafts stay included — the widening must not have traded one exclusion for another. */
    @Test
    void includesPlainDrafts() {
        OntologyMetadataEntity draftOntology = ontology("rozpracovany-draft-ont", false);
        concept(draftOntology, "rozpracovany-draft-concept", false);

        assertThat(ontologyRepository.searchByTextUnpublished(Q)).hasSize(1);
        assertThat(unpublishedConceptNames()).containsExactly("rozpracovany-draft-concept");
    }

    /** A NULL flag (legacy rows) is included too, and must not trip the ordering's NULLS FIRST. */
    @Test
    void includesNullPublishedFlag() {
        OntologyMetadataEntity nullFlagged = ontology("rozpracovany-null", null);
        concept(nullFlagged, "rozpracovany-null-concept", null);

        assertThat(unpublishedConceptNames()).containsExactly("rozpracovany-null-concept");
        assertThat(ontologyRepository.searchByTextUnpublished(Q)).hasSize(1);
    }

    /**
     * The text query is still the filter: rows that don't match the query are excluded. This is what
     * keeps the "no publish-state restriction" change from degenerating into "return everything".
     */
    @Test
    void stillFiltersOnText() {
        OntologyMetadataEntity match = ontology("rozpracovany-match", true);
        concept(match, "rozpracovany-match-concept", true);
        OntologyMetadataEntity other = ontology("nesouvisejici-slovnik", true);
        concept(other, "nesouvisejici-pojem", true);

        assertThat(ontologyRepository.searchByTextUnpublished(Q))
                .extracting(OntologyMetadataEntity::getSlug)
                .containsExactly("rozpracovany-match");
        assertThat(unpublishedConceptNames()).containsExactly("rozpracovany-match-concept");
    }

    /**
     * Row filter and reported total must agree — a count computed from a different predicate than the
     * rows is how drafts fell off the page in #168/#170.
     */
    @Test
    void countMatchesReturnedRows() {
        OntologyMetadataEntity workingCopy = ontology("rozpracovany-count", true);
        concept(workingCopy, "rozpracovany-count-a", true);
        concept(workingCopy, "rozpracovany-count-b", false);
        OntologyMetadataEntity second = ontology("rozpracovany-count-two", true);
        concept(second, "rozpracovany-count-c", true);
        ontology("nesouvisejici-count", true);

        long conceptCount = conceptRepository.countSearchByTextUnpublished(Q, false, List.of(), false, null);
        assertThat(conceptCount).isEqualTo(unpublishedConceptNames().size()).isEqualTo(3);

        long ontologyCount = ontologyRepository.countSearchByTextUnpublished(Q);
        assertThat(ontologyCount).isEqualTo(ontologyRepository.searchByTextUnpublished(Q).size()).isEqualTo(2);
    }

    /**
     * Draft-first ordering, matching the default {@code searchByText} and the merged-list
     * {@code RESULT_ORDER}. With publish state no longer filtering, ordering is the ONLY thing that
     * still distinguishes an edited draft from an untouched working copy — so it carries the weight
     * the predicate used to.
     */
    @Test
    void ordersDraftsBeforePublishedWorkingCopies() {
        OntologyMetadataEntity workingCopy = ontology("rozpracovany-order", true);
        // Published row is the most recently touched, so recency alone would float it to the top.
        ConceptMetadataEntity draft = concept(workingCopy, "rozpracovany-order-draft", false);
        draft.setUpdatedAt(LocalDateTime.now().minusDays(3));
        conceptRepository.save(draft);
        ConceptMetadataEntity published = concept(workingCopy, "rozpracovany-order-published", true);
        published.setUpdatedAt(LocalDateTime.now());
        conceptRepository.save(published);

        assertThat(unpublishedConceptNames())
                .containsExactly("rozpracovany-order-draft", "rozpracovany-order-published");
    }

    /** Same draft-first bias on the ontology half of the filter. */
    @Test
    void ordersDraftOntologiesBeforePublishedOnes() {
        OntologyMetadataEntity draftOntology = ontology("rozpracovany-ord-draft", false);
        draftOntology.setUpdatedAt(LocalDateTime.now().minusDays(3));
        ontologyRepository.save(draftOntology);
        OntologyMetadataEntity workingCopy = ontology("rozpracovany-ord-wc", true);
        workingCopy.setUpdatedAt(LocalDateTime.now());
        ontologyRepository.save(workingCopy);

        assertThat(ontologyRepository.searchByTextUnpublished(Q))
                .extracting(OntologyMetadataEntity::getSlug)
                .containsExactly("rozpracovany-ord-draft", "rozpracovany-ord-wc");
    }

    /** The graph filter still narrows the concept search to the requested ontologies. */
    @Test
    void graphFilterStillApplies() {
        OntologyMetadataEntity wanted = ontology("rozpracovany-graph-a", true);
        concept(wanted, "rozpracovany-graph-a-concept", true);
        OntologyMetadataEntity unwanted = ontology("rozpracovany-graph-b", true);
        concept(unwanted, "rozpracovany-graph-b-concept", true);

        List<ConceptMetadataEntity> scoped = conceptRepository.searchByTextUnpublished(
                Q, true, List.of(wanted.getGraphName()), false, null);

        assertThat(scoped).extracting(ConceptMetadataEntity::getConceptName)
                .containsExactly("rozpracovany-graph-a-concept");
    }

    /** The type filter still narrows within the widened set. */
    @Test
    void typeFilterStillApplies() {
        OntologyMetadataEntity workingCopy = ontology("rozpracovany-type", true);
        concept(workingCopy, "rozpracovany-type-trida", true);
        ConceptMetadataEntity vztah = concept(workingCopy, "rozpracovany-type-vztah", true);
        vztah.setConceptType(ConceptType.VZTAH);
        conceptRepository.save(vztah);

        List<ConceptMetadataEntity> onlyVztah = conceptRepository.searchByTextUnpublished(
                Q, false, List.of(), true, ConceptType.VZTAH.name());

        assertThat(onlyVztah).extracting(ConceptMetadataEntity::getConceptName)
                .containsExactly("rozpracovany-type-vztah");
    }
}