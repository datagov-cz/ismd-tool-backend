package com.dia.ismdtoolbackend.controller.dto;

import java.util.List;

/**
 * Body of the {@code ONTOLOGY_DOWNLOAD_BLOCKED_BY_VALIDATION} (HTTP 400) response. Lists the
 * validation errors that block the download so the FE can render them without a second call;
 * the full report (including warnings) stays available at
 * {@code GET /api/ontology/{slug}/validation-report}.
 *
 * @param graphName  the vocabulary IRI the download was requested for
 * @param errorCount number of ERROR-severity results in the stored report
 * @param errors     the blocking errors, capped at {@code MAX_LISTED_ERRORS}
 * @param truncated  true when {@code errorCount} exceeds the listed {@code errors}
 */
public record DownloadBlockedByValidationDto(
        String graphName,
        int errorCount,
        List<ValidationErrorSummaryDto> errors,
        boolean truncated
) {
}