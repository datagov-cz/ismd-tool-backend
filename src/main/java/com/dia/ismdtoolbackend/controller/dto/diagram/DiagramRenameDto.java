package com.dia.ismdtoolbackend.controller.dto.diagram;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/diagram/{ontologySlug}/{diagramId}/rename}.
 *
 * <p>{@code name} is {@code @NotBlank} here, unlike on {@link DiagramCreateDto} where a blank one takes
 * a default. The default is create-only semantics: on a rename a blank name means the user cleared the
 * field, and silently renaming their canvas to "Nový diagram" would be worse than refusing.
 */
@Schema(description = "Nový název diagramu.")
public record DiagramRenameDto(

        @Schema(description = "Nový název diagramu. Povinný a neprázdný.", example = "Pohled HR")
        @NotBlank(message = "Název diagramu nesmí být prázdný.")
        @Size(max = 255, message = "Název diagramu může mít nejvýše 255 znaků.")
        String name
) {
}