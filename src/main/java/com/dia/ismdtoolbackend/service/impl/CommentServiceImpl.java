package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.CommentException;
import com.dia.ismdtoolbackend.mapper.CommentMapper;
import com.dia.ismdtoolbackend.models.CommentCreateModel;
import com.dia.ismdtoolbackend.models.CommentModel;
import com.dia.ismdtoolbackend.repository.CommentRepository;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.CommentService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CommentServiceImpl implements CommentService {

    private final CommentMapper commentMapper;
    private final CommentRepository commentRepository;
    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final ObjectMapper objectMapper;


    @Override
    @Transactional
    public CommentModel postComment(CommentCreateModel commentCreateModel, String userId) {
        log.info("Creating commentEntity for: ontology: {}, concept: {}, comment: {}",
                commentCreateModel.getOntologyIRI(), commentCreateModel.getConceptIRI(), commentCreateModel.getComment());

        validateInput(commentCreateModel, userId);

        Object subject = determineCommentSubject(commentCreateModel);

        if (subject instanceof OntologyMetadataEntity ontologyMetadata) {
            return addCommentToOntology(ontologyMetadata, commentCreateModel, userId);
        }
        if (subject instanceof ConceptMetadataEntity conceptMetadata) {
            return addCommentToConcept(conceptMetadata, commentCreateModel, userId);
        }

        throw new CommentException("Předmět komentáře nebyl nalezen, nebo není specifikován");
    }

    private void validateInput(CommentCreateModel commentCreateModel, String userId) {
        if (commentCreateModel == null) {
            throw new CommentException("Data pro vytvoření komentáře jsou prázdná");
        }

        if (userId == null || userId.trim().isEmpty()) {
            throw new CommentException("ID uživatele je povinné");
        }

        if (commentCreateModel.getComment() == null || commentCreateModel.getComment().trim().isEmpty()) {
            throw new CommentException("Text komentáře je povinný");
        }

        boolean hasOntologyIRI = commentCreateModel.getOntologyIRI() != null && !commentCreateModel.getOntologyIRI().isEmpty();
        boolean hasConceptIRI = commentCreateModel.getConceptIRI() != null && !commentCreateModel.getConceptIRI().isEmpty();

        if (hasOntologyIRI && hasConceptIRI) {
            throw new CommentException("Komentář může být přiřazen buď ke slovníku nebo k pojmu, ne k oběma");
        }

        if (!hasOntologyIRI && !hasConceptIRI) {
            throw new CommentException("Předmět komentáře (slovník nebo pojem) musí být specifikován");
        }
    }

    private Object determineCommentSubject(CommentCreateModel commentCreateModel) {
        if (commentCreateModel.getOntologyIRI() != null && !commentCreateModel.getOntologyIRI().isEmpty()) {
            return ontologyMetadataRepository.findByGraphName(commentCreateModel.getOntologyIRI()).orElse(null);
        }
        if (commentCreateModel.getConceptIRI() != null && !commentCreateModel.getConceptIRI().isEmpty()) {
            return conceptMetadataRepository.findByConceptIri(commentCreateModel.getConceptIRI()).orElse(null);
        }
        return null;
    }

    private CommentModel addCommentToOntology(OntologyMetadataEntity subject, CommentCreateModel commentCreateModel, String userId) {
        CommentEntity savedComment = createAndSaveCommentEntity(commentCreateModel, userId);

        List<CommentEntity> commentsList = getCommentsListFromJson(subject.getCommentsJson());
        commentsList.add(savedComment);
        String updatedCommentsJson = serializeCommentsToJson(commentsList);

        subject.setCommentsJson(updatedCommentsJson);
        ontologyMetadataRepository.save(subject);

        log.info("Added comment {} to ontology {}", savedComment.getId(), subject.getGraphName());
        return commentMapper.toDto(savedComment);
    }

    private CommentModel addCommentToConcept(ConceptMetadataEntity subject, CommentCreateModel commentCreateModel, String userId) {
        CommentEntity savedComment = createAndSaveCommentEntity(commentCreateModel, userId);

        List<CommentEntity> commentsList = getCommentsListFromJson(subject.getCommentsJson());
        commentsList.add(savedComment);
        String updatedCommentsJson = serializeCommentsToJson(commentsList);

        subject.setCommentsJson(updatedCommentsJson);
        conceptMetadataRepository.save(subject);

        log.info("Added comment {} to concept {}", savedComment.getId(), subject.getConceptIri());
        return commentMapper.toDto(savedComment);
    }

    private CommentEntity createAndSaveCommentEntity(CommentCreateModel commentCreateModel, String userId) {
        CommentEntity commentEntity = commentMapper.toEntity(commentCreateModel);
        commentEntity.setUserId(userId);
        commentEntity.setPostedTime(LocalDateTime.now());
        return commentRepository.save(commentEntity);
    }

    private List<CommentEntity> getCommentsListFromJson(String commentsJson) {
        if (commentsJson == null || commentsJson.trim().isEmpty()) {
            return new ArrayList<>();
        }

        try {
            return objectMapper.readValue(commentsJson, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse comments JSON, starting with empty list", e);
            return new ArrayList<>();
        }
    }

    private String serializeCommentsToJson(List<CommentEntity> commentsList) {
        try {
            return objectMapper.writeValueAsString(commentsList);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize comments to JSON", e);
            throw new CommentException("Nepodařilo se uložit komentář: " + e.getMessage());
        }
    }
}
