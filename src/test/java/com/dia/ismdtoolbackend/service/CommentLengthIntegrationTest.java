package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.CommentException;
import com.dia.ismdtoolbackend.mapper.CommentMapperImpl;
import com.dia.ismdtoolbackend.models.CommentCreateModel;
import com.dia.ismdtoolbackend.models.CommentModel;
import com.dia.ismdtoolbackend.outbox.PostgresIntegrationTestBase;
import com.dia.ismdtoolbackend.outbox.TransactionTemplateConfig;
import com.dia.ismdtoolbackend.repository.CommentRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.impl.CommentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Guards the comment length limit against the real {@code comments.comment} column on Postgres. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@Import({CommentServiceImpl.class, CommentMapperImpl.class, TransactionTemplateConfig.class})
@EntityScan(basePackageClasses = CommentEntity.class)
@EnableJpaRepositories(basePackageClasses = CommentRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CommentLengthIntegrationTest extends PostgresIntegrationTestBase {

    private static final String GRAPH = "https://slovnik.gov.cz/comment-length";

    @Autowired private CommentServiceImpl service;
    @Autowired private CommentRepository commentRepository;
    @Autowired private OntologyMetadataRepository ontologyMetadataRepository;
    @Autowired private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        txTemplate.executeWithoutResult(tx -> {
            commentRepository.deleteAll();
            ontologyMetadataRepository.findByGraphName(GRAPH).ifPresent(ontologyMetadataRepository::delete);
            ontologyMetadataRepository.flush();
            OntologyMetadataEntity ontology = new OntologyMetadataEntity();
            ontology.setSlug("comment-length");
            ontology.setGraphName(GRAPH);
            ontology.setUserId("user123");
            ontology.setIsPublished(false);
            ontology.setCreatedAt(LocalDateTime.now());
            ontologyMetadataRepository.save(ontology);
        });
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
