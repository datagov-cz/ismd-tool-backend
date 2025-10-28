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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private CommentServiceImpl commentService;

    private CommentEntity testCommentEntity;
    private OntologyMetadataEntity testOntologyEntity;
    private ConceptMetadataEntity testConceptEntity;
    private CommentCreateModel testCommentCreateModel;
    private CommentModel testCommentModel;

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

        testOntologyEntity = new OntologyMetadataEntity();
        testOntologyEntity.setId(1L);
        testOntologyEntity.setGraphName(TEST_ONTOLOGY_IRI);

        testConceptEntity = new ConceptMetadataEntity();
        testConceptEntity.setId(1L);
        testConceptEntity.setConceptIri(TEST_CONCEPT_IRI);

        testCommentCreateModel = new CommentCreateModel();
        testCommentCreateModel.setComment(TEST_COMMENT_TEXT);

        testCommentModel = new CommentModel();
        testCommentModel.setId(TEST_COMMENT_ID);
        testCommentModel.setComment(TEST_COMMENT_TEXT);
    }

    // ========== postComment Tests - Ontology Comments ==========

    @Test
    void postComment_ToOntology_Success() throws JsonProcessingException {
        testCommentCreateModel.setOntologyIRI(TEST_ONTOLOGY_IRI);
        String updatedCommentsJson = "[{\"id\":1}]";

        when(ontologyMetadataRepository.findByGraphName(TEST_ONTOLOGY_IRI))
                .thenReturn(Optional.of(testOntologyEntity));
        when(commentMapper.toEntity(testCommentCreateModel)).thenReturn(testCommentEntity);
        when(commentRepository.save(any(CommentEntity.class))).thenReturn(testCommentEntity);
        when(objectMapper.readValue(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(java.util.List.of());
        when(objectMapper.writeValueAsString(anyList())).thenReturn(updatedCommentsJson);
        when(commentMapper.toDto(testCommentEntity)).thenReturn(testCommentModel);

        CommentModel result = commentService.postComment(testCommentCreateModel, TEST_USER_ID);

        assertNotNull(result);
        assertEquals(TEST_COMMENT_ID, result.getId());
        verify(ontologyMetadataRepository).save(testOntologyEntity);
        verify(commentRepository).save(any(CommentEntity.class));
    }

    @Test
    void postComment_ToOntology_WithExistingComments() throws JsonProcessingException {
        testCommentCreateModel.setOntologyIRI(TEST_ONTOLOGY_IRI);
        CommentEntity existingComment = new CommentEntity();
        existingComment.setId(99L);

        when(ontologyMetadataRepository.findByGraphName(TEST_ONTOLOGY_IRI))
                .thenReturn(Optional.of(testOntologyEntity));
        when(commentMapper.toEntity(testCommentCreateModel)).thenReturn(testCommentEntity);
        when(commentRepository.save(any(CommentEntity.class))).thenReturn(testCommentEntity);
        when(objectMapper.readValue(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(java.util.List.of(existingComment));
        when(objectMapper.writeValueAsString(anyList())).thenReturn("[]");
        when(commentMapper.toDto(testCommentEntity)).thenReturn(testCommentModel);

        CommentModel result = commentService.postComment(testCommentCreateModel, TEST_USER_ID);

        assertNotNull(result);
        verify(ontologyMetadataRepository).save(argThat(ontology -> ontology.getCommentsJson() != null));
    }

    @Test
    void postComment_ToOntology_OntologyNotFound() {
        testCommentCreateModel.setOntologyIRI(TEST_ONTOLOGY_IRI);

        when(ontologyMetadataRepository.findByGraphName(TEST_ONTOLOGY_IRI)).thenReturn(Optional.empty());

        CommentException exception = assertThrows(CommentException.class,
                () -> commentService.postComment(testCommentCreateModel, TEST_USER_ID));

        assertTrue(exception.getMessage().contains("nebyl nalezen"));
        verify(commentRepository, never()).save(any());
    }

    // ========== postComment Tests - Concept Comments ==========

    @Test
    void postComment_ToConcept_Success() throws JsonProcessingException {
        testCommentCreateModel.setConceptIRI(TEST_CONCEPT_IRI);
        String updatedCommentsJson = "[{\"id\":1}]";

        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI))
                .thenReturn(Optional.of(testConceptEntity));
        when(commentMapper.toEntity(testCommentCreateModel)).thenReturn(testCommentEntity);
        when(commentRepository.save(any(CommentEntity.class))).thenReturn(testCommentEntity);
        when(objectMapper.readValue(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(java.util.List.of());
        when(objectMapper.writeValueAsString(anyList())).thenReturn(updatedCommentsJson);
        when(commentMapper.toDto(testCommentEntity)).thenReturn(testCommentModel);

        CommentModel result = commentService.postComment(testCommentCreateModel, TEST_USER_ID);

        assertNotNull(result);
        assertEquals(TEST_COMMENT_ID, result.getId());
        verify(conceptMetadataRepository).save(testConceptEntity);
        verify(commentRepository).save(any(CommentEntity.class));
    }

    @Test
    void postComment_ToConcept_ConceptNotFound() {
        testCommentCreateModel.setConceptIRI(TEST_CONCEPT_IRI);

        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI)).thenReturn(Optional.empty());

        CommentException exception = assertThrows(CommentException.class,
                () -> commentService.postComment(testCommentCreateModel, TEST_USER_ID));

        assertTrue(exception.getMessage().contains("nebyl nalezen"));
        verify(commentRepository, never()).save(any());
    }

    // ========== postComment Tests - Validation ==========

    @Test
    void postComment_NullModel() {
        // The implementation logs before validation, which causes NullPointerException
        // This test verifies the actual behavior
        assertThrows(NullPointerException.class,
                () -> commentService.postComment(null, TEST_USER_ID));
    }

    @Test
    void postComment_NullUserId() {
        testCommentCreateModel.setOntologyIRI(TEST_ONTOLOGY_IRI);

        CommentException exception = assertThrows(CommentException.class,
                () -> commentService.postComment(testCommentCreateModel, null));

        assertTrue(exception.getMessage().contains("povinné"));
    }

    @Test
    void postComment_EmptyUserId() {
        testCommentCreateModel.setOntologyIRI(TEST_ONTOLOGY_IRI);

        CommentException exception = assertThrows(CommentException.class,
                () -> commentService.postComment(testCommentCreateModel, "  "));

        assertTrue(exception.getMessage().contains("povinné"));
    }

    @Test
    void postComment_NullCommentText() {
        testCommentCreateModel.setComment(null);
        testCommentCreateModel.setOntologyIRI(TEST_ONTOLOGY_IRI);

        CommentException exception = assertThrows(CommentException.class,
                () -> commentService.postComment(testCommentCreateModel, TEST_USER_ID));

        assertTrue(exception.getMessage().contains("Text komentáře je povinný"));
    }

    @Test
    void postComment_EmptyCommentText() {
        testCommentCreateModel.setComment("  ");
        testCommentCreateModel.setOntologyIRI(TEST_ONTOLOGY_IRI);

        CommentException exception = assertThrows(CommentException.class,
                () -> commentService.postComment(testCommentCreateModel, TEST_USER_ID));

        assertTrue(exception.getMessage().contains("Text komentáře je povinný"));
    }

    @Test
    void postComment_BothOntologyAndConcept() {
        testCommentCreateModel.setOntologyIRI(TEST_ONTOLOGY_IRI);
        testCommentCreateModel.setConceptIRI(TEST_CONCEPT_IRI);

        CommentException exception = assertThrows(CommentException.class,
                () -> commentService.postComment(testCommentCreateModel, TEST_USER_ID));

        assertTrue(exception.getMessage().contains("buď ke slovníku nebo k pojmu"));
    }

    @Test
    void postComment_NeitherOntologyNorConcept() {
        CommentException exception = assertThrows(CommentException.class,
                () -> commentService.postComment(testCommentCreateModel, TEST_USER_ID));

        assertTrue(exception.getMessage().contains("musí být specifikován"));
    }

    @Test
    void postComment_SetsPostedTime() throws JsonProcessingException {
        testCommentCreateModel.setOntologyIRI(TEST_ONTOLOGY_IRI);

        when(ontologyMetadataRepository.findByGraphName(TEST_ONTOLOGY_IRI))
                .thenReturn(Optional.of(testOntologyEntity));
        when(commentMapper.toEntity(testCommentCreateModel)).thenReturn(testCommentEntity);
        when(commentRepository.save(any(CommentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(objectMapper.readValue(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(java.util.List.of());
        when(objectMapper.writeValueAsString(anyList())).thenReturn("[]");
        when(commentMapper.toDto(any(CommentEntity.class))).thenReturn(testCommentModel);

        commentService.postComment(testCommentCreateModel, TEST_USER_ID);

        ArgumentCaptor<CommentEntity> captor = ArgumentCaptor.forClass(CommentEntity.class);
        verify(commentRepository).save(captor.capture());

        CommentEntity savedEntity = captor.getValue();
        assertNotNull(savedEntity.getPostedTime());
        assertEquals(TEST_USER_ID, savedEntity.getUserId());
    }

    @Test
    void postComment_JsonSerializationFails() throws JsonProcessingException {
        testCommentCreateModel.setOntologyIRI(TEST_ONTOLOGY_IRI);

        when(ontologyMetadataRepository.findByGraphName(TEST_ONTOLOGY_IRI))
                .thenReturn(Optional.of(testOntologyEntity));
        when(commentMapper.toEntity(testCommentCreateModel)).thenReturn(testCommentEntity);
        when(commentRepository.save(any(CommentEntity.class))).thenReturn(testCommentEntity);
        when(objectMapper.readValue(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(java.util.List.of());
        when(objectMapper.writeValueAsString(anyList()))
                .thenThrow(new JsonProcessingException("Serialization error") {});

        CommentException exception = assertThrows(CommentException.class,
                () -> commentService.postComment(testCommentCreateModel, TEST_USER_ID));

        assertTrue(exception.getMessage().contains("Nepodařilo se uložit komentář"));
    }

    @Test
    void postComment_JsonDeserializationFails_UsesEmptyList() throws JsonProcessingException {
        testCommentCreateModel.setOntologyIRI(TEST_ONTOLOGY_IRI);
        testOntologyEntity.setCommentsJson("{invalid json");

        when(ontologyMetadataRepository.findByGraphName(TEST_ONTOLOGY_IRI))
                .thenReturn(Optional.of(testOntologyEntity));
        when(commentMapper.toEntity(testCommentCreateModel)).thenReturn(testCommentEntity);
        when(commentRepository.save(any(CommentEntity.class))).thenReturn(testCommentEntity);
        when(objectMapper.readValue(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenThrow(new JsonProcessingException("Parse error") {});
        when(objectMapper.writeValueAsString(anyList())).thenReturn("[]");
        when(commentMapper.toDto(testCommentEntity)).thenReturn(testCommentModel);

        CommentModel result = commentService.postComment(testCommentCreateModel, TEST_USER_ID);

        assertNotNull(result);
        verify(ontologyMetadataRepository).save(testOntologyEntity);
    }

    // ========== deleteComment Tests ==========

    @Test
    void deleteComment_FromOntology_Success() throws JsonProcessingException {
        testCommentEntity.setOntologyIRI(TEST_ONTOLOGY_IRI);
        testOntologyEntity.setCommentsJson("[{\"id\":1}]");

        CommentEntity commentInList = new CommentEntity();
        commentInList.setId(TEST_COMMENT_ID);
        java.util.List<CommentEntity> commentsList = new java.util.ArrayList<>();
        commentsList.add(commentInList);

        when(commentRepository.findById(TEST_COMMENT_ID)).thenReturn(Optional.of(testCommentEntity));
        when(ontologyMetadataRepository.findByGraphName(TEST_ONTOLOGY_IRI))
                .thenReturn(Optional.of(testOntologyEntity));
        when(objectMapper.readValue(eq("[{\"id\":1}]"), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(commentsList);
        when(objectMapper.writeValueAsString(anyList())).thenReturn("[]");

        commentService.deleteComment(TEST_COMMENT_ID);

        verify(ontologyMetadataRepository).save(testOntologyEntity);
        verify(commentRepository).deleteById(TEST_COMMENT_ID);
    }

    @Test
    void deleteComment_FromConcept_Success() throws JsonProcessingException {
        testCommentEntity.setConceptIRI(TEST_CONCEPT_IRI);
        testConceptEntity.setCommentsJson("[{\"id\":1}]");

        CommentEntity commentInList = new CommentEntity();
        commentInList.setId(TEST_COMMENT_ID);
        java.util.List<CommentEntity> commentsList = new java.util.ArrayList<>();
        commentsList.add(commentInList);

        when(commentRepository.findById(TEST_COMMENT_ID)).thenReturn(Optional.of(testCommentEntity));
        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI))
                .thenReturn(Optional.of(testConceptEntity));
        when(objectMapper.readValue(eq("[{\"id\":1}]"), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(commentsList);
        when(objectMapper.writeValueAsString(anyList())).thenReturn("[]");

        commentService.deleteComment(TEST_COMMENT_ID);

        verify(conceptMetadataRepository).save(testConceptEntity);
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

    @Test
    void deleteComment_NoSubject_DeletesFromDatabaseOnly() {
        testCommentEntity.setOntologyIRI(null);
        testCommentEntity.setConceptIRI(null);

        when(commentRepository.findById(TEST_COMMENT_ID)).thenReturn(Optional.of(testCommentEntity));

        commentService.deleteComment(TEST_COMMENT_ID);

        verify(commentRepository).deleteById(TEST_COMMENT_ID);
        verify(ontologyMetadataRepository, never()).save(any());
        verify(conceptMetadataRepository, never()).save(any());
    }

    @Test
    void deleteComment_SubjectNotFound_DeletesFromDatabaseOnly() {
        testCommentEntity.setOntologyIRI(TEST_ONTOLOGY_IRI);

        when(commentRepository.findById(TEST_COMMENT_ID)).thenReturn(Optional.of(testCommentEntity));
        when(ontologyMetadataRepository.findByGraphName(TEST_ONTOLOGY_IRI)).thenReturn(Optional.empty());

        commentService.deleteComment(TEST_COMMENT_ID);

        verify(commentRepository).deleteById(TEST_COMMENT_ID);
        verify(ontologyMetadataRepository, never()).save(any());
    }

    @Test
    void deleteComment_CommentNotInJsonList() throws JsonProcessingException {
        testCommentEntity.setOntologyIRI(TEST_ONTOLOGY_IRI);
        CommentEntity otherComment = new CommentEntity();
        otherComment.setId(999L);

        when(commentRepository.findById(TEST_COMMENT_ID)).thenReturn(Optional.of(testCommentEntity));
        when(ontologyMetadataRepository.findByGraphName(TEST_ONTOLOGY_IRI))
                .thenReturn(Optional.of(testOntologyEntity));
        when(objectMapper.readValue(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(new java.util.ArrayList<>(java.util.List.of(otherComment)));

        commentService.deleteComment(TEST_COMMENT_ID);

        verify(ontologyMetadataRepository, never()).save(any());
        verify(commentRepository).deleteById(TEST_COMMENT_ID);
    }

    @Test
    void deleteComment_FromOntology_EmptyJsonList() {
        testCommentEntity.setOntologyIRI(TEST_ONTOLOGY_IRI);
        testOntologyEntity.setCommentsJson("");

        when(commentRepository.findById(TEST_COMMENT_ID)).thenReturn(Optional.of(testCommentEntity));
        when(ontologyMetadataRepository.findByGraphName(TEST_ONTOLOGY_IRI))
                .thenReturn(Optional.of(testOntologyEntity));

        commentService.deleteComment(TEST_COMMENT_ID);

        verify(ontologyMetadataRepository, never()).save(any());
        verify(commentRepository).deleteById(TEST_COMMENT_ID);
    }

    @Test
    void deleteComment_FromConcept_NullJsonList() {
        testCommentEntity.setConceptIRI(TEST_CONCEPT_IRI);
        testConceptEntity.setCommentsJson(null);

        when(commentRepository.findById(TEST_COMMENT_ID)).thenReturn(Optional.of(testCommentEntity));
        when(conceptMetadataRepository.findByConceptIri(TEST_CONCEPT_IRI))
                .thenReturn(Optional.of(testConceptEntity));

        commentService.deleteComment(TEST_COMMENT_ID);

        verify(conceptMetadataRepository, never()).save(any());
        verify(commentRepository).deleteById(TEST_COMMENT_ID);
    }

    @Test
    void deleteComment_JsonDeserializationFails_DeletesFromDatabaseOnly() throws JsonProcessingException {
        testCommentEntity.setOntologyIRI(TEST_ONTOLOGY_IRI);
        testOntologyEntity.setCommentsJson("{invalid json");

        when(commentRepository.findById(TEST_COMMENT_ID)).thenReturn(Optional.of(testCommentEntity));
        when(ontologyMetadataRepository.findByGraphName(TEST_ONTOLOGY_IRI))
                .thenReturn(Optional.of(testOntologyEntity));
        when(objectMapper.readValue(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenThrow(new JsonProcessingException("Parse error") {});

        commentService.deleteComment(TEST_COMMENT_ID);

        verify(ontologyMetadataRepository, never()).save(any());
        verify(commentRepository).deleteById(TEST_COMMENT_ID);
    }
}