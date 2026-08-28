package com.dia.ismdtoolbackend.client.ai.dto;

import com.dia.ismdtoolbackend.controller.dto.ai.AiKnownConceptualModelDto;

import java.util.List;

public record IsmdAiClassJobRequest(
        int k,
        List<String> structuralElementIds,
        String contextText,
        AiKnownConceptualModelDto knownConceptualModel
) {
}
