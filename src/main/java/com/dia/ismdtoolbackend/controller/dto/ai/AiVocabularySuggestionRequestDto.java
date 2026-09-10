package com.dia.ismdtoolbackend.controller.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "Parameters forwarded to the aggregated ISMD AI job. Omitted counts use AI defaults; no vocabulary is created.")
public record AiVocabularySuggestionRequestDto(
        @Schema(description = "Maximum new classes; AI default is 5.")
        @Min(1) @Max(10) Integer classCount,
        @Schema(description = "Maximum properties per new class; AI default is 3, 0 skips properties.")
        @Min(0) @Max(10) Integer propertiesPerClass,
        @Schema(description = "Maximum relationships per new class; AI default is 3, 0 skips relationships.")
        @Min(0) @Max(10) Integer relationshipsPerClass,
        @Schema(description = "Optional ELI elements of the act version in the URL; omitted or empty uses the whole act.")
        @Size(max = 100) List<@NotBlank String> structuralElementIds,
        @Size(max = 10000) String contextText,
        @Schema(description = "Optional known terms supplied directly, including unsaved terms with temporary termID values.")
        @Valid AiKnownConceptualModelDto knownConceptualModel
) {
}
