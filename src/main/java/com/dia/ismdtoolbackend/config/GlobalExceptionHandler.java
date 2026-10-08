package com.dia.ismdtoolbackend.config;

import com.dia.exceptions.ValidationException;
import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.controller.dto.DownloadBlockedByValidationDto;
import com.dia.ismdtoolbackend.controller.dto.MissingInSchemeDecisionDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramConflictDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramReadbackFailureDto;
import com.dia.ismdtoolbackend.controller.dto.ValidationErrorSummaryDto;
import com.dia.ismdtoolbackend.enums.ErrorCode;
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
        return error(ErrorCode.RESOURCE_NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleAccessDenied(AccessDeniedException e) {
        log.error("Access denied: {}", e.getMessage());
        return error(ErrorCode.ACCESS_DENIED);
    }

    @ExceptionHandler(OntologyCreationConflictException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleOntologyCreationConflict(OntologyCreationConflictException e) {
        return error(ErrorCode.ONTOLOGY_CREATION_CONFLICT, e.getMessage());
    }

    /** Another editor saved the diagram first; membership is a full replace, so a stale save would delete. */
    @ExceptionHandler(DiagramVersionConflictException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleDiagramVersionConflict(
            DiagramVersionConflictException e) {
        log.warn("Diagram version conflict: {}", e.getMessage());
        return error(ErrorCode.DIAGRAM_VERSION_CONFLICT, e.getMessage());
    }

    /** A diagram name is already taken within its ontology (names identify a canvas to the user). */
    @ExceptionHandler(DiagramNameConflictException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleDiagramNameConflict(DiagramNameConflictException e) {
        log.warn("Diagram name conflict: {}", e.getMessage());
        return error(ErrorCode.DIAGRAM_NAME_CONFLICT, e.getMessage());
    }

    /**
     * The resolution itself is unusable — {@code ACCEPT_THEIRS} with no {@code winnerDiagramId}, or one
     * naming a diagram that is not in the conflict set. 400, not 409: re-sending the same request cannot
     * succeed, so the FE must correct it rather than let the user choose again.
     */
    @ExceptionHandler(DiagramConflictResolutionException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleDiagramConflictResolution(
            DiagramConflictResolutionException e) {
        log.warn("Invalid diagram conflict resolution: {}", e.getMessage());
        return error(ErrorCode.DIAGRAM_CONFLICT_RESOLUTION_INVALID, e.getMessage());
    }

    /**
     * Sibling diagrams stage competing edits on the same concept and the caller named no resolution.
     * 409 with the report: nothing was written, and the FE re-calls with {@code onConflict} once the
     * user has chosen a side.
     */
    @ExceptionHandler(DiagramEditConflictException.class)
    public ResponseEntity<ApiResponseDto<DiagramConflictDto>> handleDiagramEditConflict(
            DiagramEditConflictException e) {
        log.warn("Diagram materialize blocked by cross-diagram conflict: {} concept(s)",
                e.getReport().conflicts().size());
        return error(ErrorCode.DIAGRAM_EDIT_CONFLICT, e.getMessage(), e.getReport());
    }

    /**
     * The write COMMITTED; only the Fuseki read that renders it failed. 502 (not 500): the failure is in an
     * upstream dependency, and the code + version tell the FE to reload rather than retry the save — a retry
     * would carry the stale version and 409.
     */
    @ExceptionHandler(DiagramReadbackFailedException.class)
    public ResponseEntity<ApiResponseDto<DiagramReadbackFailureDto>> handleDiagramReadbackFailed(
            DiagramReadbackFailedException e) {
        log.error("Diagram write committed but content read-back failed (version {}): {}",
                e.getVersion(), e.getMessage(), e);
        return error(ErrorCode.DIAGRAM_SAVED_READBACK_FAILED, e.getMessage(), new DiagramReadbackFailureDto(e.getVersion()));
    }

    /**
     * A diagram read could not reach the ontology's own graph. 502, like the read-back failure above, so one
     * unreachable Fuseki does not report 500 on a read and 502 on a save.
     */
    @ExceptionHandler(DiagramContentUnavailableException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleDiagramContentUnavailable(
            DiagramContentUnavailableException e) {
        log.error("Diagram content could not be read: {}", e.getMessage(), e);
        return error(ErrorCode.DIAGRAM_CONTENT_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("Constraint violation: {}", e.getMessage());
        return error(ErrorCode.DATA_INTEGRITY_VIOLATION);
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleExternalServiceUnavailable(ResourceAccessException e) {
        log.error("External service unavailable: {}", e.getMessage(), e);
        return error(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE);
    }

    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleExternalServiceResponse(RestClientResponseException e) {
        log.warn("External service returned status {}: {}", e.getStatusCode(), e.getMessage());
        HttpStatusCode status = e.getStatusCode().is5xxServerError()
                ? HttpStatus.BAD_GATEWAY
                : e.getStatusCode();
        return ResponseEntity.status(status).body(ApiResponseDto.error(ErrorCode.EXTERNAL_SERVICE_REJECTED));
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleExternalServiceError(RestClientException e) {
        log.error("External service call failed: {}", e.getMessage(), e);
        return error(ErrorCode.EXTERNAL_SERVICE_INVALID_RESPONSE);
    }

    @ExceptionHandler(JenaTDB2Exception.class)
    public ResponseEntity<ApiResponseDto<Void>> handleJenaTDB2Exception(JenaTDB2Exception e) {
        log.error("Database operation failed: {}", e.getMessage(), e);
        return error(ErrorCode.RDF_STORE_ERROR, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponseDto<Void>> handleGenericException(Exception e) {
        log.error("Unexpected error: {}", e.getMessage(), e);
        return error(ErrorCode.INTERNAL_ERROR);
    }

    @ExceptionHandler(OntologyNotFoundException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleOntologyNotFoundException(OntologyNotFoundException e) {
        log.warn("Ontology not found: {}", e.getMessage());
        return error(ErrorCode.ONTOLOGY_NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(OntologyValidationException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleOntologyValidationException(OntologyValidationException e) {
        log.warn("Ontology validation failed: {}", e.getMessage());
        return error(ErrorCode.ONTOLOGY_VALIDATION_FAILED, e.getMessage());
    }

    @ExceptionHandler(InSchemeDecisionRequiredException.class)
    public ResponseEntity<ApiResponseDto<MissingInSchemeDecisionDto>> handleInSchemeDecisionRequired(InSchemeDecisionRequiredException e) {
        log.info("Upload paused for inScheme decision: {} concept(s) missing skos:inScheme",
                e.getConceptsMissingInScheme().size());
        MissingInSchemeDecisionDto data = new MissingInSchemeDecisionDto(
                e.getGraphName(), e.getConceptsMissingInScheme());
        return error(ErrorCode.MISSING_INSCHEME_DECISION_REQUIRED, e.getMessage(), data);
    }

    @ExceptionHandler(OntologyDownloadBlockedException.class)
    public ResponseEntity<ApiResponseDto<DownloadBlockedByValidationDto>> handleOntologyDownloadBlocked(OntologyDownloadBlockedException e) {
        log.info("Download blocked for {}: {} validation error(s), rules: {}",
                e.getGraphName(),
                e.getErrorCount(),
                e.getErrors().stream().map(ValidationErrorSummaryDto::ruleName).distinct().toList());
        DownloadBlockedByValidationDto data = new DownloadBlockedByValidationDto(
                e.getGraphName(), e.getErrorCount(), e.getErrors(), e.isTruncated());
        return error(ErrorCode.ONTOLOGY_DOWNLOAD_BLOCKED_BY_VALIDATION, e.getMessage(), data);
    }

    @ExceptionHandler(OntologyStorageException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleOntologyStorageException(OntologyStorageException e) {
        log.error("Ontology storage failed: {}", e.getMessage(), e);
        return error(ErrorCode.ONTOLOGY_STORAGE_FAILED, e.getMessage());
    }

    @ExceptionHandler(EmptyFileException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleEmptyFileException(EmptyFileException e) {
        log.warn("File is empty: {}", e.getMessage());
        return error(ErrorCode.UPLOAD_FILE_EMPTY, e.getMessage());
    }

    @ExceptionHandler(UnsupportedRdfFormatException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleUnsupportedRdfFormatException(UnsupportedRdfFormatException e) {
        log.warn("Unsupported RDF language format: {}", e.getMessage());
        return error(ErrorCode.UPLOAD_RDF_FORMAT_UNSUPPORTED, e.getMessage());
    }

    @ExceptionHandler(EmptyDataException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleEmptyDataException(EmptyDataException e) {
        log.warn("Data for creating ontology is empty: {}", e.getMessage());
        return error(ErrorCode.UPLOAD_DATA_EMPTY, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        return error(ErrorCode.INVALID_ARGUMENT, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String details = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + (fe.getDefaultMessage() == null ? "neplatná hodnota" : fe.getDefaultMessage()))
                .reduce((a, b) -> a + "; " + b)
                .orElse("Neplatná data v požadavku.");
        log.warn("Validation failed: {}", details);
        return error(ErrorCode.REQUEST_VALIDATION_FAILED, "Neplatná data v požadavku: " + details);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleHandlerMethodValidation(HandlerMethodValidationException e) {
        log.warn("Method argument validation failed: {}", e.getMessage());
        return error(ErrorCode.REQUEST_VALIDATION_FAILED);
    }

    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleTypeMismatch(TypeMismatchException e) {
        log.warn("Bad request: {}", e.getMessage());
        return error(ErrorCode.INVALID_PARAMETER, e.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleSecurityException(SecurityException e) {
        log.warn("Unauthorized: {}", e.getMessage());
        return error(ErrorCode.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(ConceptNotFoundException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleConceptNotFoundException(ConceptNotFoundException e) {
        log.warn("Concept not found: {}", e.getMessage());
        return error(ErrorCode.CONCEPT_NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConceptValidationException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleConceptValidationException(ConceptValidationException e) {
        log.warn("Concept validation failed: {}", e.getMessage());
        return error(ErrorCode.CONCEPT_VALIDATION_FAILED, e.getMessage());
    }

    @ExceptionHandler(ConceptStorageException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleConceptStorageException(ConceptStorageException e) {
        log.error("Concept storage failed: {}", e.getMessage(), e);
        return error(ErrorCode.CONCEPT_STORAGE_FAILED, e.getMessage());
    }

    @ExceptionHandler(CommentNotFoundException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleCommentNotFoundException(CommentNotFoundException e) {
        log.warn("Comment not found: {}", e.getMessage());
        return error(ErrorCode.COMMENT_NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(CommentException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleCommentException(CommentException e) {
        log.warn("Comment operation failed: {}", e.getMessage());
        return error(ErrorCode.COMMENT_INVALID, e.getMessage());
    }

    @ExceptionHandler(OntologyException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleOntologyException(OntologyException e) {
        log.error("Operation failed: {}", e.getMessage(), e);
        return error(ErrorCode.ONTOLOGY_PROCESSING_FAILED, e.getMessage());
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleValidationException(ValidationException e) {
        log.error("Validation failed: {}", e.getMessage(), e);
        return error(ErrorCode.VALIDATION_FAILED, e.getMessage());
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
        return error(ErrorCode.VALIDATION_REPORT_FAILED, e.getMessage());
    }

    @ExceptionHandler(OntologyUploadException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleOntologyUploadException(OntologyUploadException e) {
        log.error("Ontology upload failed: {}", e.getMessage(), e);
        return error(ErrorCode.UPLOAD_FAILED, e.getMessage());
    }

    @ExceptionHandler(OntologyUploadFileTooLargeException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleOntologyUploadFileTooLarge(OntologyUploadFileTooLargeException e) {
        log.warn("Ontology upload rejected, file too large: {}", e.getMessage());
        return error(ErrorCode.UPLOAD_FILE_TOO_LARGE, e.getMessage());
    }

    @ExceptionHandler(OntologyUploadParseException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleOntologyUploadParse(OntologyUploadParseException e) {
        log.warn("Ontology upload rejected, RDF not parseable: {}", e.getMessage());
        return error(ErrorCode.UPLOAD_RDF_PARSE_FAILED, e.getMessage());
    }

    @ExceptionHandler(OntologyUploadParseTimeoutException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleOntologyUploadParseTimeout(OntologyUploadParseTimeoutException e) {
        log.warn("Ontology upload rejected, RDF parsing timed out: {}", e.getMessage());
        return error(ErrorCode.UPLOAD_RDF_PARSE_TIMEOUT, e.getMessage());
    }

    @ExceptionHandler(OntologyUploadMissingIriException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleOntologyUploadMissingIri(OntologyUploadMissingIriException e) {
        log.warn("Ontology upload rejected, no ontology IRI in data: {}", e.getMessage());
        return error(ErrorCode.UPLOAD_ONTOLOGY_IRI_MISSING, e.getMessage());
    }

    @ExceptionHandler(OntologyUploadIriCollisionException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleOntologyUploadIriCollision(OntologyUploadIriCollisionException e) {
        log.warn("Ontology upload rejected, IRIs differ only by a trailing slash: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponseDto.error(
                null, e.getMessage(), OntologyUploadIriCollisionException.ERROR_CODE));
    }

    /** 502, matching the diagram handlers: the failing party is the RDF store. */
    @ExceptionHandler(OntologyUploadRdfStoreException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleOntologyUploadRdfStore(OntologyUploadRdfStoreException e) {
        log.error("Ontology upload failed writing to TDB2: {}", e.getMessage(), e);
        return error(ErrorCode.UPLOAD_RDF_STORE_FAILED, e.getMessage());
    }

    @ExceptionHandler(OntologyUploadMetadataException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleOntologyUploadMetadata(OntologyUploadMetadataException e) {
        log.error("Ontology upload failed saving metadata: {}", e.getMessage(), e);
        return error(ErrorCode.UPLOAD_METADATA_SAVE_FAILED, e.getMessage());
    }

    @ExceptionHandler(OntologyAlreadyExistsException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleOntologyAlreadyExistsException(OntologyAlreadyExistsException e) {
        log.warn("Ontology already exists: {}", e.getMessage());
        return error(ErrorCode.ONTOLOGY_ALREADY_EXISTS, e.getMessage());
    }

    @ExceptionHandler(JsonExportException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleJsonExportException(JsonExportException e) {
        log.error("JSON export failed: {}", e.getMessage(), e);
        return error(ErrorCode.EXPORT_FAILED, e.getMessage());
    }

    @ExceptionHandler(OntologyAnalysisException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleOntologyAnalysisException(OntologyAnalysisException e) {
        log.error("Ontology analysis failed: {}", e.getMessage(), e);
        return error(ErrorCode.ONTOLOGY_ANALYSIS_FAILED, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.warn("File upload size exceeded: {}", e.getMessage());
        return error(ErrorCode.UPLOAD_FILE_TOO_LARGE);
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleIOException(IOException e) {
        log.error("IO exception: {}", e.getMessage(), e);
        return error(ErrorCode.IO_ERROR, e.getMessage());
    }

    @ExceptionHandler(NkdResourceNotFoundException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleNkdResourceNotFoundException(NkdResourceNotFoundException e) {
        log.info("NKD resource not found: {}", e.getMessage());
        return error(ErrorCode.NKD_RESOURCE_NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(NkdEndpointException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleNkdEndpointException(NkdEndpointException e) {
        log.warn("NKD endpoint error: {}", e.getMessage());
        return error(ErrorCode.NKD_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(SparqlEndpointUnavailableException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleSparqlEndpointUnavailable(SparqlEndpointUnavailableException e) {
        log.error("{} unavailable: {}", e.getEndpointLabel(), e.getMessage(), e);
        return error(ErrorCode.SPARQL_ENDPOINT_UNAVAILABLE, e.getEndpointLabel() + " data nejsou momentálně dostupná.");
    }

    @ExceptionHandler(ValidationServiceUnavailableException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleValidationServiceUnavailable(ValidationServiceUnavailableException e) {
        log.error("{} unavailable: {}", e.getEndpointLabel(), e.getMessage(), e);
        return error(ErrorCode.VALIDATION_SERVICE_UNAVAILABLE, e.getEndpointLabel() + " není momentálně dostupná.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("Type mismatch for parameter '{}': value='{}'", e.getName(), e.getValue());
        String msg = "Parametr '" + e.getName() + "' má neplatnou hodnotu.";
        return error(ErrorCode.INVALID_PARAMETER, msg);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleMissingParameter(MissingServletRequestParameterException e) {
        log.warn("Missing required parameter '{}'", e.getParameterName());
        String msg = "Chybí povinný parametr '" + e.getParameterName() + "'.";
        return error(ErrorCode.MISSING_PARAMETER, msg);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponseDto<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("Request body unreadable: {}", e.getMessage());
        return error(ErrorCode.MALFORMED_REQUEST);
    }

    private static ResponseEntity<ApiResponseDto<Void>> error(ErrorCode code) {
        return error(code, null, null);
    }

    private static ResponseEntity<ApiResponseDto<Void>> error(ErrorCode code, String message) {
        return error(code, message, null);
    }

    /** The status comes from the code, so the two cannot disagree. */
    private static <T> ResponseEntity<ApiResponseDto<T>> error(ErrorCode code, String message, T data) {
        return ResponseEntity.status(code.getStatus()).body(ApiResponseDto.error(code, message, data));
    }
}
