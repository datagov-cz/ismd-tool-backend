package com.dia.ismdtoolbackend.config;

import com.dia.exceptions.ValidationException;
import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.controller.dto.MissingInSchemeDecisionDto;
import com.dia.ismdtoolbackend.exception.*;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

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

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("Constraint violation: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error("Zápis porušuje omezení databáze — pravděpodobně již existuje záznam se stejnou hodnotou."), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleExternalServiceUnavailable(ResourceAccessException e) {
        log.error("External service unavailable: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponseDto.error("Externí služba není momentálně dostupná."));
    }

    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleExternalServiceResponse(RestClientResponseException e) {
        log.warn("External service returned status {}: {}", e.getStatusCode(), e.getMessage());
        HttpStatusCode status = e.getStatusCode().is5xxServerError()
                ? HttpStatus.BAD_GATEWAY
                : e.getStatusCode();
        return ResponseEntity.status(status)
                .body(ApiResponseDto.error("Externí služba požadavek odmítla."));
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleExternalServiceError(RestClientException e) {
        log.error("External service call failed: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponseDto.error("Externí služba vrátila neplatnou odpověď."));
    }

    @ExceptionHandler(JenaTDB2Exception.class)
    public ResponseEntity<ApiResponseDto<Void>> handleJenaTDB2Exception(JenaTDB2Exception e) {
        log.error("Database operation failed: {}", e.getMessage(), e);
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponseDto<Void>> handleGenericException(Exception e) {
        log.error("Unexpected error: {}", e.getMessage(), e);
        return ResponseEntity.status(500).body(ApiResponseDto.error("Nastala neočekávaná chyba."));
    }

    @ExceptionHandler(OntologyNotFoundException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleOntologyNotFoundException(OntologyNotFoundException e) {
        log.warn("Ontology not found: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(OntologyValidationException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleOntologyValidationException(OntologyValidationException e) {
        log.warn("Ontology validation failed: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(InSchemeDecisionRequiredException.class)
    public ResponseEntity<ApiResponseDto<MissingInSchemeDecisionDto>> handleInSchemeDecisionRequired(InSchemeDecisionRequiredException e) {
        log.info("Upload paused for inScheme decision: {} concept(s) missing skos:inScheme",
                e.getConceptsMissingInScheme().size());
        MissingInSchemeDecisionDto data = new MissingInSchemeDecisionDto(
                e.getGraphName(), e.getConceptsMissingInScheme());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseDto.error(data, e.getMessage(), InSchemeDecisionRequiredException.ERROR_CODE));
    }

    @ExceptionHandler(OntologyStorageException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleOntologyStorageException(OntologyStorageException e) {
        log.error("Ontology storage failed: {}", e.getMessage(), e);
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(EmptyFileException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleEmptyFileException(EmptyFileException e) {
        log.warn("File is empty: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(UnsupportedRdfFormatException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleUnsupportedRdfFormatException(UnsupportedRdfFormatException e) {
        log.warn("Unsupported RDF language format: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(EmptyDataException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleEmptyDataException(EmptyDataException e) {
        log.warn("Data for creating ontology is empty: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String details = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + (fe.getDefaultMessage() == null ? "neplatná hodnota" : fe.getDefaultMessage()))
                .reduce((a, b) -> a + "; " + b)
                .orElse("Neplatná data v požadavku.");
        log.warn("Validation failed: {}", details);
        return new ResponseEntity<>(ApiResponseDto.error("Neplatná data v požadavku: " + details), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleHandlerMethodValidation(HandlerMethodValidationException e) {
        log.warn("Method argument validation failed: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error("Neplatná data v požadavku."), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleTypeMismatch(TypeMismatchException e) {
        log.warn("Bad request: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleSecurityException(SecurityException e) {
        log.warn("Unauthorized: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(ConceptNotFoundException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleConceptNotFoundException(ConceptNotFoundException e) {
        log.warn("Concept not found: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(ConceptValidationException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleConceptValidationException(ConceptValidationException e) {
        log.warn("Concept validation failed: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ConceptStorageException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleConceptStorageException(ConceptStorageException e) {
        log.error("Concept storage failed: {}", e.getMessage(), e);
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(CommentNotFoundException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleCommentNotFoundException(CommentNotFoundException e) {
        log.warn("Comment not found: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(CommentException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleCommentException(CommentException e) {
        log.warn("Comment operation failed: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(OntologyException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleOntologyException(OntologyException e) {
        log.error("Operation failed: {}", e.getMessage(), e);
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleValidationException(ValidationException e) {
        log.error("Validation failed: {}", e.getMessage(), e);
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.BAD_REQUEST);
    }

    /**
     * Distinct from {@link com.dia.exceptions.ValidationException} above: that one is the validator
     * library's and signals bad input, this one is the tool's own and wraps a failure to store or
     * read a validation report. Both names are written out in full — the wildcard import of the
     * tool's exception package makes it otherwise unclear which class either handler binds to.
     */
    @ExceptionHandler(com.dia.ismdtoolbackend.exception.ValidationException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleValidationReportException(
            com.dia.ismdtoolbackend.exception.ValidationException e) {
        log.error("Validation report operation failed: {}", e.getMessage(), e);
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(OntologyUploadException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleOntologyUploadException(OntologyUploadException e) {
        log.error("Ontology upload failed: {}", e.getMessage(), e);
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(OntologyAlreadyExistsException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleOntologyAlreadyExistsException(OntologyAlreadyExistsException e) {
        log.warn("Ontology already exists: {}", e.getMessage());
        String location = "/api/ontology/" + e.getExistingMetadata().getSlug() + "/detail";
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", location)
                .body(ApiResponseDto.error(e.getMessage()));
    }

    @ExceptionHandler(JsonExportException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleJsonExportException(JsonExportException e) {
        log.error("JSON export failed: {}", e.getMessage(), e);
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(OntologyAnalysisException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleOntologyAnalysisException(OntologyAnalysisException e) {
        log.error("Ontology analysis failed: {}", e.getMessage(), e);
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.warn("File upload size exceeded: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error("Nahraný soubor překračuje maximální povolenou velikost."), HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleIOException(IOException e) {
        log.error("IO exception: {}", e.getMessage(), e);
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(NkdResourceNotFoundException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleNkdResourceNotFoundException(NkdResourceNotFoundException e) {
        log.info("NKD resource not found: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(NkdEndpointException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleNkdEndpointException(NkdEndpointException e) {
        log.warn("NKD endpoint error: {}", e.getMessage());
        return new ResponseEntity<>(ApiResponseDto.error(e.getMessage()), HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ExceptionHandler(SparqlEndpointUnavailableException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleSparqlEndpointUnavailable(SparqlEndpointUnavailableException e) {
        log.error("{} unavailable: {}", e.getEndpointLabel(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponseDto.error(e.getEndpointLabel() + " data nejsou momentálně dostupná."));
    }

    @ExceptionHandler(ValidationServiceUnavailableException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleValidationServiceUnavailable(ValidationServiceUnavailableException e) {
        log.error("{} unavailable: {}", e.getEndpointLabel(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponseDto.error(e.getEndpointLabel() + " není momentálně dostupná."));
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
