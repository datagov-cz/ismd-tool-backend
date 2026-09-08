package com.dia.ismdtoolbackend.controller.dto.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record AiVocabularyExpansionRequestDto(
        @NotNull Kind kind,
        @Min(1) @Max(10) Integer count,
        String selectedClassId,
        @Size(max = 100) List<@NotBlank String> structuralElementIds,
        @Size(max = 10000) String contextText,
        @NotNull @Valid AiKnownConceptualModelDto knownConceptualModel
) {
    public enum Kind {
        @JsonProperty("classes") CLASSES,
        @JsonProperty("properties") PROPERTIES,
        @JsonProperty("relationships") RELATIONSHIPS
    }
}
