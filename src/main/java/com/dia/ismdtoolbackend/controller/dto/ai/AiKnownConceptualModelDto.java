package com.dia.ismdtoolbackend.controller.dto.ai;

import com.dia.ismdtoolbackend.enums.AiTermType;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

@Schema(description = "Known terms that AI suggestions should take into account.")
public record AiKnownConceptualModelDto(
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<@Valid @NotNull KnownClassTermDto> classes,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<@Valid @NotNull KnownAttributeTermDto> attributes,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<@Valid @NotNull KnownRelationshipTermDto> relationships
) {

    public record KnownClassTermDto(
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @JsonProperty("termID")
            String termId,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Map<String, String> name,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Map<String, String> definition,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Map<String, String> explanation,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            AiTermType type,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            List<@Valid @NotNull AiIdReferenceDto> specializes,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String legalAct
    ) {
    }

    public record KnownAttributeTermDto(
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @JsonProperty("termID")
            String termId,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @Valid
            AiIdReferenceDto associatedClass,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Map<String, String> name,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Map<String, String> definition,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Map<String, String> explanation,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String legalAct
    ) {
    }

    public record KnownRelationshipTermDto(
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @JsonProperty("termID")
            String termId,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @Valid
            AiIdReferenceDto sourceClass,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @Valid
            AiIdReferenceDto targetClass,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Map<String, String> name,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Map<String, String> definition,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Map<String, String> explanation,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String legalAct
    ) {
    }
}
