package com.dia.ismdtoolbackend.controller.dto.diagram;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/diagram/{ontologySlug}/create}.
 *
 * <p>{@code name} is optional — a blank or absent one takes a default — so a client that only wants
 * "another canvas" need send nothing meaningful. Deliberately NOT {@code @NotBlank}: a required field
 * here would type as non-optional in the generated client.
 */
@Schema(description = "Údaje pro vytvoření nového diagramu.")
public record DiagramCreateDto(

        @Schema(description = "Název diagramu. Nepovinný — prázdný název se nahradí výchozím.",
                example = "Pohled HR")
        @Size(max = 255, message = "Název diagramu může mít nejvýše 255 znaků.")
        String name
) {
}
