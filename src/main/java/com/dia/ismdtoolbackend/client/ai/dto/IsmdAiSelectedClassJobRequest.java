package com.dia.ismdtoolbackend.client.ai.dto;

import com.dia.ismdtoolbackend.controller.dto.ai.AiKnownConceptualModelDto;

import java.util.List;

public record IsmdAiSelectedClassJobRequest(
        int k,
        String selectedClassId,
        List<String> structuralElementIds,
        String contextText,
        AiKnownConceptualModelDto knownConceptualModel
) {
}
