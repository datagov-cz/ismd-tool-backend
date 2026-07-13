package com.dia.ismdtoolbackend.controller.dto.ai;

import com.dia.ismdtoolbackend.enums.AiJobStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AiRelationshipSuggestionsJobResponseDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID jobId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String selectedClassId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        AiJobStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<AiRelationshipSuggestionDto> newRelationshipSuggestions
) {

    public record AiRelationshipSuggestionDto(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String suggestionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            AiIdReferenceDto sourceClass,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            AiIdReferenceDto targetClass,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Map<String, String> name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Map<String, String> definition,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Map<String, String> explanation,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String legalAct
    ) {
    }
}
