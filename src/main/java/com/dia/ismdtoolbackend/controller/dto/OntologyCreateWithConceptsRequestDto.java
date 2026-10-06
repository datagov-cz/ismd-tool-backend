package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.controller.dto.ai.AiConceptReferenceDto;
import com.dia.ismdtoolbackend.enums.AiTermType;
import com.dia.ismdtoolbackend.models.OntologyCreateModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

@Schema(description = "New vocabulary and selected terms only. References to deselected classes are rejected. At most 1000 terms in total.")
public record OntologyCreateWithConceptsRequestDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull @Valid OntologyCreateModel ontology,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull @Size(max = 1000) List<@NotNull @Valid ClassDto> classes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull @Size(max = 1000) List<@NotNull @Valid AttributeDto> attributes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull @Size(max = 1000) List<@NotNull @Valid RelationshipDto> relationships
) {
    public record ClassDto(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String ref,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull Map<String, String> name,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) Map<String, String> definition,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) Map<String, String> explanation,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull AiTermType type,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) List<@NotNull @Valid AiConceptReferenceDto> specializes,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String legalAct
    ) {}

    public record AttributeDto(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String ref,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull @Valid AiConceptReferenceDto associatedClass,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull Map<String, String> name,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) Map<String, String> definition,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) Map<String, String> explanation,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String legalAct,
            @Schema(description = "Optional datatype IRI supplied during review.", requiredMode = Schema.RequiredMode.NOT_REQUIRED) String dataType
    ) {}

    public record RelationshipDto(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank String ref,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull @Valid AiConceptReferenceDto sourceClass,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull @Valid AiConceptReferenceDto targetClass,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull Map<String, String> name,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) Map<String, String> definition,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) Map<String, String> explanation,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String legalAct
    ) {}
}
