package com.dia.ismdtoolbackend.models;

import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PublishedOntologyDeviationModel {

    private PublishedConceptDeviationModel.DeviationStatus status;
    private String errorMessage;

    @JsonProperty("typ")
    private PublishedOntologyDeviationModel.PropertyDeviation<List<String>> types;

    @JsonProperty("název")
    private PublishedOntologyDeviationModel.PropertyDeviation<Map<String, String>> name;

    @JsonProperty("popis")
    private PublishedOntologyDeviationModel.PropertyDeviation<Map<String, String>> description;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PropertyDeviation<T> {
        private T localValue;
        private T publishedValue;
        private boolean isDifferent;
    }

    public enum DeviationStatus {
        NO_DEVIATION,
        HAS_DEVIATIONS,
        ENDPOINT_UNAVAILABLE,
        CONCEPT_NOT_FOUND_IN_NKD,
        QUERY_ERROR
    }
}
