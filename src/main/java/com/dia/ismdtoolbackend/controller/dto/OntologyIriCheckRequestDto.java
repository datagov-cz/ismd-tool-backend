package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.models.NameModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record OntologyIriCheckRequestDto(
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String namespace,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Valid NameModel nameModel
) {
}
