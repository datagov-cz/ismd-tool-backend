package com.dia.ismdtoolbackend.controller.dto.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record AiVocabularyRegenerationRequestDto(
        @NotBlank String conceptRef,
        @Size(max = 100) List<@NotBlank String> structuralElementIds,
        @Size(max = 10000) String contextText,
        @NotNull @Valid AiKnownConceptualModelDto knownConceptualModel
) {}
