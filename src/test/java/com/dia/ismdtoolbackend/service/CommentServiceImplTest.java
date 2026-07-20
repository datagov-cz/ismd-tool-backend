package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.CommentException;
import com.dia.ismdtoolbackend.exception.CommentNotFoundException;
import com.dia.ismdtoolbackend.mapper.CommentMapper;
import com.dia.ismdtoolbackend.models.CommentCreateModel;
import com.dia.ismdtoolbackend.models.CommentModel;
import com.dia.ismdtoolbackend.repository.CommentRepository;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.impl.CommentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommentServiceImplTest {

    @Mock
    private CommentMapper commentMapper;

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private OntologyMetadataRepository ontologyMetadataRepository;

    @Mock
    private ConceptMetadataRepository conceptMetadataRepository;

    @InjectMocks
    private CommentServiceImpl commentService;

    private CommentEntity testCommentEntity;
    private CommentCreateModel testCommentCreateModel;
    private CommentModel testCommentModel;
    private OntologyMetadataEntity testOntologyMetadata;
    private ConceptMetadataEntity testConceptMetadata;

    private static final Long TEST_COMMENT_ID = 1L;
    private static final String TEST_ONTOLOGY_IRI = "http://example.org/test-ontology";
    private static final String TEST_CONCEPT_IRI = "http://example.org/pojem/test-concept";
    private static final String TEST_USER_ID = "user123";
    private static final String TEST_COMMENT_TEXT = "This is a test comment";

    @BeforeEach
    void setUp() {
        testCommentEntity = new CommentEntity();
        testCommentEntity.setId(TEST_COMMENT_ID);
        testCommentEntity.setComment(TEST_COMMENT_TEXT);
        testCommentEntity.setUserId(TEST_USER_ID);
        testCommentEntity.setPostedTime(LocalDateTime.now());

        testCommentCreateModel = new CommentCreateModel();
        testCommentCreateModel.setComment(TEST_COMMENT_TEXT);

        testCommentModel = new CommentModel();
        testCommentModel.setId(TEST_COMMENT_ID);
        testCommentModel.setComment(TEST_COMMENT_TEXT);
        testCommentModel.setUserId(TEST_USER_ID);

        testOntologyMetadata = new OntologyMetadataEntity();
        testOntologyMetadata.setId(10L);
        testOntologyMetadata.setGraphName(TEST_ONTOLOGY_IRI);

        testConceptMetadata = new ConceptMetadataEntity();
        testConceptMetadata.setId(20L);
        testConceptMetadata.setConceptIri(TEST_CONCEPT_IRI);
    }

    // ========== postComment Tests - Ontology Comments ==========

    @Test
    void postComment_ToOntology_Success() {
        testCommentCreateModel.setOntologyIRI(TEST_ONTOLOGY_IRI);

        when(commentMapper.toEntity(eq(testCommentCreateModel), eq(TEST_USER_ID), any(LocalDateTime.class))).thenReturn(testCommentEntity);
        when(ontologyMetadataRepository.findByGraphName(TEST_ONTOLOGY_IRI)).thenReturn(Optional.of(testOntologyMetadata));
        when(commentRepository.save(any(CommentEntity.class))).thenReturn(testCommentEntity);
        when(commentMapper.toDto(testCommentEntity)).thenReturn(testCommentModel);

        CommentModel result = commentService.postComment(testCommentCreateModel, TEST_USER_ID);

        assertNotNull(result);
        assertEquals(TEST_COMMENT_ID, result.getId());
        assertEquals(TEST_USER_ID, result.getUserId());

        verify(commentRepository).save(any(CommentEntity.class));
        verify(commentMapper).toDto(testCommentEntity);
    }

    @Test
    void postComment_ToOntology_LinksOwningOntologyMetadata() {
        testCommentCreateModel.setOntologyIRI(TEST_ONTOLOGY_IRI);

        when(commentMapper.toEntity(eq(testCommentCreateModel), eq(TEST_USER_ID), any(LocalDateTime.class))).thenReturn(testCommentEntity);
        when(ontologyMetadataRepository.findByGraphName(TEST_ONTOLOGY_IRI)).thenReturn(Optional.of(testOntologyMetadata));
        when(commentRepository.save(any(CommentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(commentMapper.toDto(any(CommentEntity.class))).thenReturn(testCommentModel);

        commentService.postComment(testCommentCreateModel, TEST_USER_ID);

        ArgumentCaptor<CommentEntity> captor = ArgumentCaptor.forClass(CommentEntity.class);
        verify(commentRepository).save(captor.capture());

        CommentEntity savedEntity = captor.getValue();
        assertSame(testOntologyMetadata, savedEntity.getOntologyMetadata(), "Comment should link the owning ontology");
        assertNull(savedEntity.getConceptMetadata(), "Concept link should stay unset for an ontology comment");
        assertEquals(TEST_USER_ID, savedEntity.getUserId(), "User ID should be set");
    }

    @Test
    void postComment_ToOntology_UnresolvableIRI_Rejected() {
        testCommentCreateModel.setOntologyIRI(TEST_ONTOLOGY_IRI);

        when(commentMapper.toEntity(eq(testCommentCreateModel), eq(TEST_USER_ID), any(LocalDateTime.class))).thenReturn(testCommentEntity);
        when(ontologyMetadataRepository.findByGraphName(TEST_ONTOLOGY_IRI)).thenReturn(Optional.empty());

        CommentException exception = assertThrows(CommentException.class,
                () -> commentService.postComment(testCommentCreateModel, TEST_USER_ID));

        assertTrue(exception.getMessage().contains("nebyl nalezen"));
        verify(commentRepository, never()).save(any());
    }

    // ========== postComment Tests - Concept Comments ==========

    @Test
    void postComment_ToConcept_Success() {
        testCommentCreateModel.setConceptIRI(TEST_CONCEPT_IRI);

        when(commentMapper.toEntity(eq(testCommentCreateModel), eq(TEST_USER_ID), any(LocalDateTime.class))).thenReturn(testCommentEntity);
        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.of(testConceptMetadata));
        when(commentRepository.save(any(CommentEntity.class))).thenReturn(testCommentEntity);
        when(commentMapper.toDto(testCommentEntity)).thenReturn(testCommentModel);

        CommentModel result = commentService.postComment(testCommentCreateModel, TEST_USER_ID);

        assertNotNull(result);
        assertEquals(TEST_COMMENT_ID, result.getId());
        verify(commentRepository).save(any(CommentEntity.class));
    }

    @Test
    void postComment_ToConcept_LinksOwningConceptMetadata() {
        testCommentCreateModel.setConceptIRI(TEST_CONCEPT_IRI);

        when(commentMapper.toEntity(eq(testCommentCreateModel), eq(TEST_USER_ID), any(LocalDateTime.class))).thenReturn(testCommentEntity);
        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.of(testConceptMetadata));
        when(commentRepository.save(any(CommentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(commentMapper.toDto(any(CommentEntity.class))).thenReturn(testCommentModel);

        commentService.postComment(testCommentCreateModel, TEST_USER_ID);

        ArgumentCaptor<CommentEntity> captor = ArgumentCaptor.forClass(CommentEntity.class);
        verify(commentRepository).save(captor.capture());

        CommentEntity savedEntity = captor.getValue();
        assertSame(testConceptMetadata, savedEntity.getConceptMetadata(), "Comment should link the owning concept");
        assertNull(savedEntity.getOntologyMetadata(), "Ontology link should stay unset for a concept comment");
    }

    @Test
    void postComment_ToConcept_UnresolvableIRI_Rejected() {
        testCommentCreateModel.setConceptIRI(TEST_CONCEPT_IRI);

        when(commentMapper.toEntity(eq(testCommentCreateModel), eq(TEST_USER_ID), any(LocalDateTime.class))).thenReturn(testCommentEntity);
        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.empty());

        CommentException exception = assertThrows(CommentException.class,
                () -> commentService.postComment(testCommentCreateModel, TEST_USER_ID));

        assertTrue(exception.getMessage().contains("nebyl nalezen"));
        verify(commentRepository, never()).save(any());
    }

    // ========== postComment Tests - Validation ==========

    @Test
    void postComment_NullModel() {
        assertThrows(NullPointerException.class,
                () -> commentService.postComment(null, TEST_USER_ID));
    }

    @Test
    void postComment_NullUserId() {
        testCommentCreateModel.setOntologyIRI(TEST_ONTOLOGY_IRI);

        CommentException exception = assertThrows(CommentException.class,
                () -> commentService.postComment(testCommentCreateModel, null));

        assertTrue(exception.getMessage().contains("povinné"));
        verify(commentRepository, never()).save(any());
    }

    @Test
    void postComment_EmptyUserId() {
        testCommentCreateModel.setOntologyIRI(TEST_ONTOLOGY_IRI);

        CommentException exception = assertThrows(CommentException.class,
                () -> commentService.postComment(testCommentCreateModel, "  "));

        assertTrue(exception.getMessage().contains("povinné"));
        verify(commentRepository, never()).save(any());
    }

    @Test
    void postComment_NullCommentText() {
        testCommentCreateModel.setComment(null);
        testCommentCreateModel.setOntologyIRI(TEST_ONTOLOGY_IRI);

        CommentException exception = assertThrows(CommentException.class,
                () -> commentService.postComment(testCommentCreateModel, TEST_USER_ID));

        assertTrue(exception.getMessage().contains("Text komentáře je povinný"));
        verify(commentRepository, never()).save(any());
    }

    @Test
    void postComment_EmptyCommentText() {
        testCommentCreateModel.setComment("  ");
        testCommentCreateModel.setOntologyIRI(TEST_ONTOLOGY_IRI);

        CommentException exception = assertThrows(CommentException.class,
                () -> commentService.postComment(testCommentCreateModel, TEST_USER_ID));

        assertTrue(exception.getMessage().contains("Text komentáře je povinný"));
        verify(commentRepository, never()).save(any());
    }

    @Test
    void postComment_BothOntologyAndConcept() {
        testCommentCreateModel.setOntologyIRI(TEST_ONTOLOGY_IRI);
        testCommentCreateModel.setConceptIRI(TEST_CONCEPT_IRI);

        CommentException exception = assertThrows(CommentException.class,
                () -> commentService.postComment(testCommentCreateModel, TEST_USER_ID));

        assertTrue(exception.getMessage().contains("buď ke slovníku nebo k pojmu"));
        verify(commentRepository, never()).save(any());
    }

    @Test
    void postComment_NeitherOntologyNorConcept() {
        CommentException exception = assertThrows(CommentException.class,
                () -> commentService.postComment(testCommentCreateModel, TEST_USER_ID));

        assertTrue(exception.getMessage().contains("musí být specifikován"));
        verify(commentRepository, never()).save(any());
    }

    // ========== deleteComment Tests ==========

    @Test
    void deleteComment_Success() {
        when(commentRepository.findById(TEST_COMMENT_ID)).thenReturn(Optional.of(testCommentEntity));
        doNothing().when(commentRepository).deleteById(TEST_COMMENT_ID);

        commentService.deleteComment(TEST_COMMENT_ID);

        verify(commentRepository).findById(TEST_COMMENT_ID);
        verify(commentRepository).deleteById(TEST_COMMENT_ID);
    }

    @Test
    void deleteComment_ConceptComment_Success() {
        testCommentEntity.setConceptMetadata(testConceptMetadata);

        when(commentRepository.findById(TEST_COMMENT_ID)).thenReturn(Optional.of(testCommentEntity));
        doNothing().when(commentRepository).deleteById(TEST_COMMENT_ID);

        commentService.deleteComment(TEST_COMMENT_ID);

        verify(commentRepository).findById(TEST_COMMENT_ID);
        verify(commentRepository).deleteById(TEST_COMMENT_ID);
    }

    @Test
    void deleteComment_CommentNotFound() {
        when(commentRepository.findById(TEST_COMMENT_ID)).thenReturn(Optional.empty());

        CommentNotFoundException exception = assertThrows(CommentNotFoundException.class,
                () -> commentService.deleteComment(TEST_COMMENT_ID));

        assertTrue(exception.getMessage().contains("nebyl nalezen"));
        verify(commentRepository, never()).deleteById(any());
    }

}
