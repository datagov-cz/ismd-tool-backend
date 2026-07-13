package com.dia.ismdtoolbackend.controller.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

@Schema(description = "Input for generating class suggestions. The backend adds only the configured suggestion count.")
public record AiClassSuggestionRequestDto(
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<@NotBlank String> structuralElementIds,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String contextText,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Valid
        AiKnownConceptualModelDto knownConceptualModel
) {
}
