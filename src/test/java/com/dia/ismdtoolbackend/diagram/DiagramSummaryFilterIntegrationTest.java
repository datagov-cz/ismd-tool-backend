package com.dia.ismdtoolbackend.diagram;

import com.dia.ismdtoolbackend.config.JpaAuditingConfig;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.outbox.PostgresIntegrationTestBase;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code userId} filter on {@code findSummaries}, exercised against real Postgres so the JPQL
 * itself is under test — a mocked repository would assert nothing about the query.
 *
 * <p>A diagram carries no owner of its own; ownership is the owning ontology's {@code userId}, which
 * is what this filters on.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@EntityScan(basePackageClasses = DiagramEntity.class)
@EnableJpaRepositories(basePackageClasses = DiagramRepository.class)
@Import(JpaAuditingConfig.class)
class DiagramSummaryFilterIntegrationTest extends PostgresIntegrationTestBase {

    @Autowired private DiagramRepository diagramRepository;
    @Autowired private OntologyMetadataRepository ontologyRepository;

    @AfterEach
    void cleanUp() {
        diagramRepository.deleteAll();
        ontologyRepository.deleteAll();
    }

    private OntologyMetadataEntity ontology(String slug, String userId) {
        OntologyMetadataEntity o = new OntologyMetadataEntity();
        o.setSlug(slug);
        o.setGraphName("https://x/" + slug);
        o.setUserId(userId);
        o.setIsPublished(false);
        return ontologyRepository.save(o);
    }

    private void diagramFor(OntologyMetadataEntity o, String name) {
        DiagramEntity d = new DiagramEntity();
        d.setName(name);
        d.setOntologyMetadata(o);
        diagramRepository.save(d);
    }

    @Test
    @DisplayName("A userId keeps only that user's ontologies' diagrams")
    void filtersByOwningOntologysUser() {
        diagramFor(ontology("alice-slovnik", "alice"), "Alice diagram");
        diagramFor(ontology("bob-slovnik", "bob"), "Bob diagram");

        List<DiagramRepository.DiagramSummaryRow> rows = diagramRepository.findSummaries(null, "alice");

        assertThat(rows).extracting(DiagramRepository.DiagramSummaryRow::getName)
                .containsExactly("Alice diagram");
    }

    @Test
    @DisplayName("A null userId returns every diagram")
    void nullUserIdReturnsEverything() {
        diagramFor(ontology("alice-slovnik", "alice"), "Alice diagram");
        diagramFor(ontology("bob-slovnik", "bob"), "Bob diagram");

        List<DiagramRepository.DiagramSummaryRow> rows = diagramRepository.findSummaries(null, null);

        // `contains`, not `containsExactly`: the Testcontainers database is shared across test
        // classes, so an unfiltered read may legitimately see rows this class did not create.
        assertThat(rows).extracting(DiagramRepository.DiagramSummaryRow::getName)
                .contains("Alice diagram", "Bob diagram");
    }

    @Test
    @DisplayName("An unknown userId returns nothing rather than everything")
    void unknownUserIdReturnsEmpty() {
        diagramFor(ontology("alice-slovnik", "alice"), "Alice diagram");

        assertThat(diagramRepository.findSummaries(null, "nobody")).isEmpty();
    }

    @Test
    @DisplayName("Multiple diagrams of one user's slovník all survive the filter, with node counts intact")
    void keepsEveryDiagramOfAMatchingOntology() {
        OntologyMetadataEntity alice = ontology("alice-slovnik", "alice");
        diagramFor(alice, "První");
        diagramFor(alice, "Druhý");
        diagramFor(ontology("bob-slovnik", "bob"), "Bob diagram");

        List<DiagramRepository.DiagramSummaryRow> rows = diagramRepository.findSummaries(null, "alice");

        assertThat(rows).extracting(DiagramRepository.DiagramSummaryRow::getName)
                .containsExactly("První", "Druhý");
        // The left join to nodes must not drop diagrams that have none.
        assertThat(rows).allSatisfy(r -> assertThat(r.getNodeCount()).isZero());
    }

    @Test
    @DisplayName("The ontology and user filters compose")
    void ontologyAndUserFiltersCompose() {
        OntologyMetadataEntity alice = ontology("alice-slovnik", "alice");
        diagramFor(alice, "Alice diagram");
        diagramFor(ontology("bob-slovnik", "bob"), "Bob diagram");

        assertThat(diagramRepository.findSummaries(alice.getId(), "alice"))
                .extracting(DiagramRepository.DiagramSummaryRow::getName)
                .containsExactly("Alice diagram");
        // Same ontology, wrong owner — the two predicates are ANDed, not ORed.
        assertThat(diagramRepository.findSummaries(alice.getId(), "bob")).isEmpty();
    }
}
