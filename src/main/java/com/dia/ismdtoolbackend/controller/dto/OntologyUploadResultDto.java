package com.dia.ismdtoolbackend.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The uploaded vocabulary and what the import changed in the file.
 *
 * @param ontology         the stored vocabulary
 * @param rejectedConcepts concepts of the file that were not imported, with the reason
 * @param correctedIris    IRIs whose trailing slash was stripped, as they appeared in the file
 */
public record OntologyUploadResultDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OntologySummaryDto ontology,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<RejectedConceptDto> rejectedConcepts,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> correctedIris
) {
    public OntologyUploadResultDto {
        rejectedConcepts = rejectedConcepts == null ? List.of() : List.copyOf(rejectedConcepts);
        correctedIris = correctedIris == null ? List.of() : List.copyOf(correctedIris);
    }
}
