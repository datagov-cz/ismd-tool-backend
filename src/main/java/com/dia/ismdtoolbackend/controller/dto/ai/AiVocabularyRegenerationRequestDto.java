package com.dia.ismdtoolbackend.controller.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record AiVocabularyRegenerationRequestDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String conceptRef,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 100) List<@NotBlank String> structuralElementIds,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 10000) String contextText,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Valid AiKnownConceptualModelDto knownConceptualModel
) {}
