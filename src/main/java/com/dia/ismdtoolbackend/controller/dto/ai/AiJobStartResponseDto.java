package com.dia.ismdtoolbackend.controller.dto.ai;

import com.dia.ismdtoolbackend.enums.AiJobStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record AiJobStartResponseDto(
        @Schema(
                description = "Identifier of the newly started suggestion job.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        UUID jobId,
        @Schema(
                description = "Initial processing status of the job.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        AiJobStatus status
) {
}
