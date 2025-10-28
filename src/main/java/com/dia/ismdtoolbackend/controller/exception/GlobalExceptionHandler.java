package com.dia.ismdtoolbackend.controller.exception;

import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.exception.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OntologyNotFoundException.class)
    public ResponseEntity<ApiResponseDto> handleOntologyNotFoundException(OntologyNotFoundException e) {
        log.warn("Ontology not found: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(OntologyValidationException.class)
    public ResponseEntity<ApiResponseDto> handleOntologyValidationException(OntologyValidationException e) {
        log.warn("Ontology validation failed: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(OntologyStorageException.class)
    public ResponseEntity<ApiResponseDto> handleOntologyStorageException(OntologyStorageException e) {
        log.error("Ontology storage failed: {}", e.getMessage(), e);
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(EmptyFileException.class)
    public ResponseEntity<ApiResponseDto> handleEmptyFileException(EmptyFileException e) {
        log.warn("File is empty: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(UnsupportedRdfFormatException.class)
    public ResponseEntity<ApiResponseDto> handleUnsupportedRdfFormatException(UnsupportedRdfFormatException e) {
        log.warn("Unsupported RDF language format: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(EmptyDataException.class)
    public ResponseEntity<ApiResponseDto> handleEmptyDataException(EmptyDataException e) {
        log.warn("Data for creating ontology is empty: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponseDto> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResponseDto> handleSecurityException(SecurityException e) {
        log.warn("Unauthorized: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(ConceptNotFoundException.class)
    public ResponseEntity<ApiResponseDto> handleConceptNotFoundException(ConceptNotFoundException e) {
        log.warn("Concept not found: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(ConceptValidationException.class)
    public ResponseEntity<ApiResponseDto> handleConceptValidationException(ConceptValidationException e) {
        log.warn("Concept validation failed: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ConceptStorageException.class)
    public ResponseEntity<ApiResponseDto> handleConceptStorageException(ConceptStorageException e) {
        log.error("Concept storage failed: {}", e.getMessage(), e);
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(CommentNotFoundException.class)
    public ResponseEntity<ApiResponseDto> handleCommentNotFoundException(CommentNotFoundException e) {
        log.warn("Comment not found: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(CommentException.class)
    public ResponseEntity<ApiResponseDto> handleCommentException(CommentException e) {
        log.warn("Comment operation failed: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(OntologyException.class)
    public ResponseEntity<ApiResponseDto> handleOntologyException(OntologyException e) {
        log.error("Operation failed: {}", e.getMessage(), e);
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponseDto> handleGenericException(Exception e) {
        log.error("Internal server error: {}", e.getMessage(), e);
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
