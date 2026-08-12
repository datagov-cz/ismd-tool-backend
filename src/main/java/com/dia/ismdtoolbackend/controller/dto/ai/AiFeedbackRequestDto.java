package com.dia.ismdtoolbackend.controller.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record AiFeedbackRequestDto(
        @Schema(
                description = "Identifier of the suggestion job.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull
        UUID jobId,

        @Schema(
                description = "Suggestion identifiers to which the feedback applies.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotEmpty
        List<@NotBlank String> suggestionIds
) {
}
