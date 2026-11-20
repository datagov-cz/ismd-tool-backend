package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.testsupport.pg.PlainJpaIT;
import com.dia.ismdtoolbackend.testsupport.pg.PostgresIntegrationTestBase;
import com.dia.ismdtoolbackend.utility.paging.OffsetPageable;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The list endpoints' JPQL against real Postgres: filters, order and offset paging. */
@PlainJpaIT
@Transactional
class ListQueriesIntegrationTest extends PostgresIntegrationTestBase {

    @Autowired private OntologyMetadataRepository ontologyRepository;
    @Autowired private DiagramRepository diagramRepository;
    @Autowired private EntityManager em;

    private OntologyMetadataEntity ontology(String slug, String userId, boolean published) {
        OntologyMetadataEntity o = new OntologyMetadataEntity();
        o.setSlug(slug);
        o.setGraphName("https://x/" + slug);
        o.setUserId(userId);
        o.setIsPublished(published);
        return ontologyRepository.save(o);
    }

    /** {@code @LastModifiedDate} overwrites {@code updatedAt} on save, so the timestamp is set by a bulk update after it. */
    private DiagramEntity diagram(OntologyMetadataEntity o, String name, LocalDateTime updatedAt) {
        DiagramEntity d = new DiagramEntity();
        d.setName(name);
        d.setOntologyMetadata(o);
        DiagramEntity saved = diagramRepository.saveAndFlush(d);
        em.createQuery("update DiagramEntity d set d.updatedAt = :t where d.id = :id")
                .setParameter("t", updatedAt)
                .setParameter("id", saved.getId())
                .executeUpdate();
        em.clear();
        return saved;
    }

    private List<String> slugs(List<OntologyMetadataEntity> rows) {
        return rows.stream().map(OntologyMetadataEntity::getSlug).toList();
    }

    @Test
    void ontologyFiltersCombine() {
        ontology("a-draft", "alice", false);
        ontology("a-pub", "alice", true);
        ontology("b-draft", "bob", false);

        assertThat(slugs(ontologyRepository.findForList(null, null, null, true, List.of(""))))
                .containsExactlyInAnyOrder("a-draft", "a-pub", "b-draft");
        assertThat(slugs(ontologyRepository.findForList("alice", false, null, true, List.of(""))))
                .containsExactly("a-draft");
        assertThat(slugs(ontologyRepository.findForList(null, null, "https://x/b-draft", true, List.of(""))))
                .containsExactly("b-draft");
        assertThat(slugs(ontologyRepository.findForList(null, true, null, false, List.of("a-pub", "b-draft"))))
                .containsExactly("a-pub");
        assertThat(ontologyRepository.findForList(null, null, null, false, List.of("missing"))).isEmpty();
    }

    @Test
    void diagramPageIsNewestFirstWithIdTiebreak() {
        OntologyMetadataEntity alice = ontology("alice", "alice", false);
        OntologyMetadataEntity bob = ontology("bob", "bob", false);
        LocalDateTime t = LocalDateTime.of(2026, 11, 1, 12, 0);
        diagram(alice, "oldest", t.minusDays(3));
        diagram(alice, "older", t.minusDays(1));
        diagram(bob, "new-1", t);
        diagram(alice, "new-2", t);

        Page<DiagramRepository.DiagramSummaryRow> all = diagramRepository.findSummaryPage(null, new OffsetPageable(10, 0));
        assertThat(all.getContent()).extracting(DiagramRepository.DiagramSummaryRow::getName)
                .containsExactly("new-2", "new-1", "older", "oldest");
        assertThat(all.getTotalElements()).isEqualTo(4);

        Page<DiagramRepository.DiagramSummaryRow> window = diagramRepository.findSummaryPage(null, new OffsetPageable(2, 1));
        assertThat(window.getContent()).extracting(DiagramRepository.DiagramSummaryRow::getName)
                .containsExactly("new-1", "older");
        assertThat(window.getTotalElements()).isEqualTo(4);

        Page<DiagramRepository.DiagramSummaryRow> mine = diagramRepository.findSummaryPage("alice", new OffsetPageable(1, 0));
        assertThat(mine.getContent()).extracting(DiagramRepository.DiagramSummaryRow::getName).containsExactly("new-2");
        assertThat(mine.getTotalElements()).isEqualTo(3);
    }

    @Test
    void diagramPageCountsDiagramsNotNodes() {
        OntologyMetadataEntity alice = ontology("alice", "alice", false);
        diagram(alice, "only", LocalDateTime.of(2026, 11, 1, 12, 0));

        Page<DiagramRepository.DiagramSummaryRow> page = diagramRepository.findSummaryPage(null, new OffsetPageable(1, 0));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).singleElement().extracting(DiagramRepository.DiagramSummaryRow::getNodeCount)
                .isEqualTo(0L);
    }
}
