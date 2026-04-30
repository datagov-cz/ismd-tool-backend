package com.dia.ismdtoolbackend.config;

import com.dia.exceptions.ValidationException;
import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.exception.*;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleEntityNotFound(EntityNotFoundException e) {
        log.error("Entity not found: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseDto.error(e.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleAccessDenied(AccessDeniedException e) {
        log.error("Access denied: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponseDto.error("Přístup odepřen: nemáte oprávnění k této operaci."));
    }

    @ExceptionHandler(JenaTDB2Exception.class)
    public ResponseEntity<ApiResponseDto> handleJenaTDB2Exception(JenaTDB2Exception e) {
        log.error("Database operation failed: {}", e.getMessage(), e);
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponseDto<Void>> handleGenericException(Exception e) {
        log.error("Unexpected error: {}", e.getMessage(), e);
        return ResponseEntity.status(500).body(ApiResponseDto.error("Nastala neočekávaná chyba."));
    }

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

    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<ApiResponseDto> handleTypeMismatch(TypeMismatchException e) {
        log.warn("Bad request: {}", e.getMessage());
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

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiResponseDto> handleValidationException(ValidationException e) {
        log.error("Validation failed: {}", e.getMessage(), e);
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(OntologyUploadException.class)
    public ResponseEntity<ApiResponseDto> handleOntologyUploadException(OntologyUploadException e) {
        log.error("Ontology upload failed: {}", e.getMessage(), e);
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(OntologyAlreadyExistsException.class)
    public ResponseEntity<ApiResponseDto> handleOntologyAlreadyExistsException(OntologyAlreadyExistsException e) {
        log.warn("Ontology already exists: {}", e.getMessage());
        String location = "/api/ontology/" + e.getExistingMetadata().getSlug() + "/detail";
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", location)
                .body(ApiResponseDto.error(e.getMessage()));
    }

    @ExceptionHandler(JsonExportException.class)
    public ResponseEntity<ApiResponseDto> handleJsonExportException(JsonExportException e) {
        log.error("JSON export failed: {}", e.getMessage(), e);
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(OntologyAnalysisException.class)
    public ResponseEntity<ApiResponseDto> handleOntologyAnalysisException(OntologyAnalysisException e) {
        log.error("Ontology analysis failed: {}", e.getMessage(), e);
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponseDto> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.warn("File upload size exceeded: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error("Nahraný soubor překračuje maximální povolenou velikost."), HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiResponseDto> handleIOException(IOException e) {
        log.error("IO exception: {}", e.getMessage(), e);
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(NkdResourceNotFoundException.class)
    public ResponseEntity<ApiResponseDto> handleNkdResourceNotFoundException(NkdResourceNotFoundException e) {
        log.info("NKD resource not found: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(NkdEndpointException.class)
    public ResponseEntity<ApiResponseDto> handleNkdEndpointException(NkdEndpointException e) {
        log.warn("NKD endpoint error: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ExceptionHandler(RppUnavailableException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleRppUnavailable(RppUnavailableException e) {
        log.error("RPP unavailable: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponseDto.error("RPP data nejsou momentálně dostupná."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("Type mismatch for parameter '{}': value='{}'", e.getName(), e.getValue());
        String msg = "Parametr '" + e.getName() + "' má neplatnou hodnotu.";
        return ResponseEntity.badRequest().body(ApiResponseDto.error(msg));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleMissingParameter(MissingServletRequestParameterException e) {
        log.warn("Missing required parameter '{}'", e.getParameterName());
        String msg = "Chybí povinný parametr '" + e.getParameterName() + "'.";
        return ResponseEntity.badRequest().body(ApiResponseDto.error(msg));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("Request body unreadable: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponseDto.error("Požadavek má neplatný formát."));
    }
}
