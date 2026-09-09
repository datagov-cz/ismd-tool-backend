package com.dia.ismdtoolbackend.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record OntologyIriCheckResponseDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String iri,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean valid,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean available
) {
}
