package com.dia.ismdtoolbackend.controller.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;

public record AiIdReferenceDto(
        @Schema(
                description = "Identifier of the referenced conceptual-model term.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String id
) {
}
