package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.exception.CommentException;
import com.dia.ismdtoolbackend.models.CommentCreateModel;
import com.dia.ismdtoolbackend.models.CommentModel;
import com.dia.ismdtoolbackend.repository.CommentRepository;
import com.dia.ismdtoolbackend.service.impl.CommentServiceImpl;
import com.dia.ismdtoolbackend.testsupport.pg.PgTestData;
import com.dia.ismdtoolbackend.testsupport.pg.PlainJpaIT;
import com.dia.ismdtoolbackend.testsupport.pg.PostgresIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Guards the comment length limit against the real {@code comments.comment} column on Postgres. */
@PlainJpaIT
class CommentLengthIntegrationTest extends PostgresIntegrationTestBase {

    private static final String GRAPH = "https://slovnik.gov.cz/comment-length";

    @Autowired private CommentServiceImpl service;
    @Autowired private CommentRepository commentRepository;
    @Autowired private PgTestData data;

    @BeforeEach
    void setUp() {
        data.createOntology("comment-length", GRAPH);
    }

    @Test
    void commentAtTheLimitIsStored() {
        String text = "ž".repeat(CommentServiceImpl.MAX_COMMENT_LENGTH);

        CommentModel posted = service.postComment(request(text), "user123");

        assertThat(commentRepository.findById(posted.getId()).orElseThrow().getComment()).isEqualTo(text);
    }

    @Test
    void commentOverTheLimitIsRejectedBeforeTheDatabase() {
        String text = "ž".repeat(CommentServiceImpl.MAX_COMMENT_LENGTH + 1);

        assertThatThrownBy(() -> service.postComment(request(text), "user123"))
                .isInstanceOf(CommentException.class)
                .hasMessageContaining(String.valueOf(CommentServiceImpl.MAX_COMMENT_LENGTH));
        assertThat(commentRepository.count()).isZero();
    }

    private static CommentCreateModel request(String text) {
        CommentCreateModel model = new CommentCreateModel();
        model.setOntologyIRI(GRAPH);
        model.setComment(text);
        return model;
    }
}
