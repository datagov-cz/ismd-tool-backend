package com.dia.ismdtoolbackend.controller.dto.ai;

import com.dia.ismdtoolbackend.enums.AiJobStatus;
import com.dia.ismdtoolbackend.enums.AiTermType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AiClassSuggestionsJobResponseDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID jobId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        AiJobStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<AiClassSuggestionDto> newSuggestions
) {

    public record AiClassSuggestionDto(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String suggestionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Map<String, String> name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Map<String, String> definition,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Map<String, String> explanation,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            AiTermType type,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<AiIdReferenceDto> specializes,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String legalAct
    ) {
    }
}
