package com.dia.ismdtoolbackend.controller.dto.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Exactly one of ref (an unsaved concept) or iri (an existing concept). Values are preserved from ISMD AI.")
public record AiConceptReferenceDto(String ref, String iri) {
}
