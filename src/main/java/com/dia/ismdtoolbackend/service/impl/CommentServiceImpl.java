package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.exception.CommentException;
import com.dia.ismdtoolbackend.exception.CommentNotFoundException;
import com.dia.ismdtoolbackend.mapper.CommentMapper;
import com.dia.ismdtoolbackend.models.CommentCreateModel;
import com.dia.ismdtoolbackend.models.CommentModel;
import com.dia.ismdtoolbackend.repository.CommentRepository;
import com.dia.ismdtoolbackend.service.CommentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class CommentServiceImpl implements CommentService {

    private final CommentMapper commentMapper;
    private final CommentRepository commentRepository;

    @Override
    @Transactional
    public CommentModel postComment(CommentCreateModel commentCreateModel, String userId) {
        log.info("Creating commentEntity for: ontology: {}, concept: {}, comment: {}",
                commentCreateModel.getOntologyIRI(), commentCreateModel.getConceptIRI(), commentCreateModel.getComment());

        validateInput(commentCreateModel, userId);

        var commentEntity = commentMapper.toEntity(commentCreateModel, userId, LocalDateTime.now());

        return commentMapper.toDto(commentRepository.save(commentEntity));
    }

    @Override
    @Transactional
    public void deleteComment(Long commentId) {
        log.info("Deleting comment with ID: {}", commentId);

        commentRepository.findById(commentId)
                .orElseThrow(() -> new CommentNotFoundException("Komentář s ID " + commentId + " nebyl nalezen"));

        commentRepository.deleteById(commentId);
        log.info("Successfully deleted comment {}", commentId);
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
}
