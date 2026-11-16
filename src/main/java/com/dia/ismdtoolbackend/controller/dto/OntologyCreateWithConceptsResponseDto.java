package com.dia.ismdtoolbackend.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

public record OntologyCreateWithConceptsResponseDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OntologySummaryDto ontology,
        @Schema(description = "Mapping of every selected temporary ref to its final concept IRI.",
                requiredMode = Schema.RequiredMode.REQUIRED) Map<String, String> conceptIris
) {}
