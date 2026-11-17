package com.dia.ismdtoolbackend.config;

import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.controller.dto.OfnConformanceFailureDto;
import com.dia.ismdtoolbackend.controller.dto.OfnConformanceIssueDto;
import com.dia.ismdtoolbackend.enums.ErrorCode;
import com.dia.ismdtoolbackend.exception.OfnConformanceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Renders {@link OfnConformanceException} with every issue, so the user knows what to correct. Ordered
 * before {@link GlobalExceptionHandler}, whose catch-all {@code Exception} handler would match first.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OfnConformanceExceptionHandler {

    @ExceptionHandler(OfnConformanceException.class)
    public ResponseEntity<ApiResponseDto<OfnConformanceFailureDto>> handle(OfnConformanceException e) {
        List<OfnConformanceIssueDto> issues = e.getIssues().stream()
                .map(issue -> new OfnConformanceIssueDto(issue.kind().name(), issue.conceptIri(), issue.location(),
                        issue.message(), issue.value()))
                .toList();
        ErrorCode code = switch (e.getContext()) {
            case WRITE -> ErrorCode.OFN_WRITE_NONCONFORMANT;
            case UPLOAD -> ErrorCode.UPLOAD_OFN_NONCONFORMANT;
            case DOWNLOAD -> ErrorCode.ONTOLOGY_DOWNLOAD_BLOCKED_BY_OFN;
        };
        if (e.getContext() == OfnConformanceException.Context.WRITE) {
            log.error("OFN safety net refused a write to {}: {}", e.getVocabularyIri(), e.getIssues());
        } else {
            log.info("{} of {} refused: {} OFN issue(s)", e.getContext(), e.getVocabularyIri(), e.getIssues().size());
        }
        return ResponseEntity.status(code.getStatus())
                .body(ApiResponseDto.error(code, e.getMessage(), new OfnConformanceFailureDto(e.getVocabularyIri(), issues)));
    }
}
