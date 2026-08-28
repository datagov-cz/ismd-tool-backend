package com.dia.ismdtoolbackend.controller.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

@Schema(description = "Input for generating suggestions for a selected class. The backend adds only the configured suggestion count.")
public record AiSelectedClassSuggestionRequestDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String selectedClassId,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<@NotBlank String> structuralElementIds,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String contextText,
        @Schema(
                description = "Slugs of ontologies merged into the known conceptual model.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        List<@NotBlank String> knownConceptualModelSlugs
) {
}
