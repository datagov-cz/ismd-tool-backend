package com.dia.ismdtoolbackend.audit;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.repository.CommentRepository;
import com.dia.ismdtoolbackend.service.impl.CommentServiceImpl;
import com.dia.ismdtoolbackend.testsupport.pg.PgTestData;
import com.dia.ismdtoolbackend.testsupport.pg.PlainJpaIT;
import com.dia.ismdtoolbackend.testsupport.pg.PostgresIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The delete audit trail on Postgres: the writer's transaction contract, a real delete, and the admin query. */
@PlainJpaIT
class DeleteAuditIntegrationTest extends PostgresIntegrationTestBase {

    private static final String GRAPH = "https://slovník.gov.cz/audit";

    @Autowired private DeleteAuditWriter writer;
    @Autowired private DeleteAuditRepository repository;
    @Autowired private CommentServiceImpl commentService;
    @Autowired private CommentRepository commentRepository;
    @Autowired private PgTestData data;
    @Autowired private TransactionTemplate tx;

    @AfterEach
    void clearUser() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aCommentDeleteRecordsWhoAndWhatButNotTheText() {
        signIn("kc-sub-1", "ROLE_USER");
        OntologyMetadataEntity ontology = data.createOntology("audit", GRAPH);
        ConceptMetadataEntity concept = data.createConcept(ontology, GRAPH + "/pojem/a", "A", ConceptType.TRIDA);
        CommentEntity comment = new CommentEntity();
        comment.setComment("osobní poznámka");
        comment.setUserId("kc-sub-1");
        comment.setOntologyMetadata(ontology);
        comment.setConceptMetadata(concept);
        Long commentId = commentRepository.save(comment).getId();

        commentService.deleteComment(commentId);

        List<DeleteAuditEntry> rows = all();
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getEntityType()).isEqualTo(DeletedEntityType.COMMENT);
            assertThat(row.getEntityId()).isEqualTo(commentId);
            assertThat(row.getActorId()).isEqualTo("kc-sub-1");
            assertThat(row.isActorAdmin()).isFalse();
            assertThat(row.getGraphName()).isEqualTo(GRAPH);
            assertThat(row.getParentIri()).isEqualTo(GRAPH + "/pojem/a");
            assertThat(row.getDeletedAt()).isNotNull();
            assertThat(row.getDetails()).isNull();
        });
        assertThat(commentRepository.findById(commentId)).isEmpty();
    }

    @Test
    void theRowRollsBackWithTheDelete() {
        signIn("kc-sub-1", "ROLE_USER");

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            writer.diagram(7L, "Přehled", "audit", GRAPH);
            throw new IllegalStateException("delete failed after the audit write");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(all()).isEmpty();
    }

    @Test
    void theWriterRefusesToRunOutsideATransaction() {
        signIn("kc-sub-1", "ROLE_USER");

        assertThatThrownBy(() -> writer.diagram(7L, "Přehled", "audit", GRAPH))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void cascadeDetailsAreStoredAsJson() {
        signIn("kc-admin", "ROLE_ADMIN");

        tx.executeWithoutResult(status -> {
            writer.ontology(1L, "audit", GRAPH, 3, 2, 1);
            writer.concept(2L, GRAPH + "/pojem/a", "a", "A", GRAPH, List.of(GRAPH + "/pojem/b"));
        });

        assertThat(repository.findPage("ONTOLOGY", null, null, null, 10, 0)).singleElement().satisfies(row -> {
            assertThat(row.isActorAdmin()).isTrue();
            assertThat(row.getEntityIri()).isEqualTo(GRAPH);
            assertThat(row.getDetails()).contains("\"concepts\":3", "\"diagrams\":2", "\"comments\":1");
        });
        assertThat(repository.findPage("CONCEPT", null, null, null, 10, 0)).singleElement()
                .satisfies(row -> assertThat(row.getDetails())
                        .isEqualTo("{\"cascadedConcepts\":[\"" + GRAPH + "/pojem/b\"]}"));
    }

    @Test
    void theAdminQueryFiltersAndPagesNewestFirst() {
        signIn("alice", "ROLE_USER");
        tx.executeWithoutResult(status -> writer.comment(1L, GRAPH, null));
        Instant afterFirst = Instant.now();
        tx.executeWithoutResult(status -> writer.diagram(2L, "D", "audit", GRAPH));
        signIn("bob", "ROLE_USER");
        tx.executeWithoutResult(status -> writer.diagram(3L, "E", "audit", GRAPH));

        assertThat(ids(repository.findPage(null, null, null, null, 10, 0))).containsExactly(3L, 2L, 1L);
        assertThat(ids(repository.findPage("DIAGRAM", null, null, null, 10, 0))).containsExactly(3L, 2L);
        assertThat(ids(repository.findPage(null, "alice", null, null, 10, 0))).containsExactly(2L, 1L);
        assertThat(ids(repository.findPage(null, null, afterFirst, null, 10, 0))).containsExactly(3L, 2L);
        assertThat(ids(repository.findPage(null, null, null, afterFirst, 10, 0))).containsExactly(1L);
        assertThat(ids(repository.findPage(null, null, null, null, 1, 1))).containsExactly(2L);
        assertThat(repository.countMatching("DIAGRAM", null, null, null)).isEqualTo(2);
        assertThat(repository.countMatching(null, "bob", null, null)).isEqualTo(1);
    }

    private List<DeleteAuditEntry> all() {
        return repository.findPage(null, null, null, null, 100, 0);
    }

    private static List<Long> ids(List<DeleteAuditEntry> rows) {
        return rows.stream().map(DeleteAuditEntry::getEntityId).toList();
    }

    private static void signIn(String sub, String role) {
        SecurityUser user = new SecurityUser(sub, sub, List.of(role));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }
}
