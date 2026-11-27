package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.enums.ApiWarningCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** A warning on a successful response, with the concepts it concerns. */
public record ApiWarningDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ApiWarningCode code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> conceptIris
) {
}
