package com.dia.ismdtoolbackend.controller.dto.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record AiVocabularyExpansionRequestDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Kind kind,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Min(1) @Max(10) Integer count,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String selectedClassId,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 100) List<@NotBlank String> structuralElementIds,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 10000) String contextText,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Valid AiKnownConceptualModelDto knownConceptualModel
) {
    public enum Kind {
        @JsonProperty("classes") CLASSES,
        @JsonProperty("properties") PROPERTIES,
        @JsonProperty("relationships") RELATIONSHIPS
    }
}
