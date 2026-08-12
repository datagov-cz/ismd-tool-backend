package com.dia.ismdtoolbackend.client.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

public record IsmdAiFeedbackRequest(
        @JsonProperty("jobID") UUID jobId,
        @JsonProperty("suggestionID") List<String> suggestionIds
) {
}
