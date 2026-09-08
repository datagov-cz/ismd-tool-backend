package com.dia.ismdtoolbackend.controller.dto.ai;

import com.dia.ismdtoolbackend.enums.AiJobStatus;
import com.dia.ismdtoolbackend.enums.AiTermType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AiVocabularySuggestionsJobResponseDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID jobId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AiJobStatus status,
        @Schema(description = "Validated proposal so far. Failed jobs retain completed batches; only completed status denotes a complete result.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        AiVocabularyDraftDto draft
) {
    public enum Phase { CLASSES, PROPERTIES, RELATIONSHIPS, DONE }

    public record AiVocabularyDraftDto(
            Phase phase,
            List<AiDraftClassDto> classes,
            List<AiDraftAttributeDto> attributes,
            List<AiDraftRelationshipDto> relationships
    ) {
    }

    public record AiDraftClassDto(
            String ref, Map<String, String> name, Map<String, String> definition,
            Map<String, String> explanation, AiTermType type,
            List<AiConceptReferenceDto> specializes, String legalAct
    ) {
    }

    public record AiDraftAttributeDto(
            String ref, AiConceptReferenceDto associatedClass, Map<String, String> name,
            Map<String, String> definition, Map<String, String> explanation, String legalAct
    ) {
    }

    public record AiDraftRelationshipDto(
            String ref, AiConceptReferenceDto sourceClass, AiConceptReferenceDto targetClass,
            Map<String, String> name, Map<String, String> definition,
            Map<String, String> explanation, String legalAct
    ) {
    }
}
