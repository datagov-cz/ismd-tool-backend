package com.dia.ismdtoolbackend.config;

import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramConflictDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramReadbackFailureDto;
import com.dia.ismdtoolbackend.enums.ErrorCode;
import com.dia.ismdtoolbackend.exception.DiagramConflictResolutionException;
import com.dia.ismdtoolbackend.exception.DiagramContentUnavailableException;
import com.dia.ismdtoolbackend.exception.DiagramEditConflictException;
import com.dia.ismdtoolbackend.exception.DiagramNameConflictException;
import com.dia.ismdtoolbackend.exception.DiagramReadbackFailedException;
import com.dia.ismdtoolbackend.exception.DiagramVersionConflictException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static com.dia.ismdtoolbackend.config.ErrorResponses.error;

/**
 * Maps the diagram exceptions to {@link ApiResponseDto} errors. Ordered before {@link GlobalExceptionHandler},
 * whose catch-all {@code Exception} handler would match first.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DiagramExceptionHandler {

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
}
