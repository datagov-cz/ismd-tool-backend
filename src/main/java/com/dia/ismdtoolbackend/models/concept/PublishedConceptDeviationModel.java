package com.dia.ismdtoolbackend.models.concept;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PublishedConceptDeviationModel {

    private DeviationStatus status;
    private String errorMessage;

    @JsonProperty("typ")
    private PropertyDeviation<List<String>> types;

    @JsonProperty("název")
    private PropertyDeviation<Map<String, String>> name;

    @JsonProperty("alternativní-název")
    private PropertyDeviation<Map<String, Object>> alternativeName;

    @JsonProperty("definice")
    private PropertyDeviation<Map<String, String>> definition;

    @JsonProperty("popis")
    private PropertyDeviation<Map<String, String>> description;

    @JsonProperty("identifikátor")
    private PropertyDeviation<String> identifier;

    @JsonProperty("nadřazená-třída")
    private PropertyDeviation<List<String>> broaderClasses;

    @JsonProperty("nadřazený-vztah")
    private PropertyDeviation<List<String>> broaderRelations;

    @JsonProperty("nadřazená-vlastnost")
    private PropertyDeviation<List<String>> broaderProperties;

    @JsonProperty("definiční-obor")
    private PropertyDeviation<String> domain;

    @JsonProperty("obor-hodnot")
    private PropertyDeviation<String> range;

    @JsonProperty("ekvivalentní-pojem")
    private PropertyDeviation<List<Map<String, String>>> exactMatches;

    @JsonProperty("definující-ustanovení-právního-předpisu")
    private PropertyDeviation<List<String>> definingLegalSources;

    @JsonProperty("související-ustanovení-právního-předpisu")
    private PropertyDeviation<List<String>> relatedLegalSources;

    @JsonProperty("definující-nelegislativní-zdroj")
    private PropertyDeviation<List<Map<String, Object>>> definingNonLegalSources;

    @JsonProperty("související-nelegislativní-zdroj")
    private PropertyDeviation<List<Map<String, Object>>> relatedNonLegalSources;

    @JsonProperty("způsob-sdílení-údajů")
    private PropertyDeviation<List<String>> sharingMethods;

    @JsonProperty("způsob-získání-údajů")
    private PropertyDeviation<String> acquisitionMethod;

    @JsonProperty("typ-obsahu-údajů")
    private PropertyDeviation<String> contentType;

    @JsonProperty("je-ppdf")
    private PropertyDeviation<Boolean> isPpdf;

    @JsonProperty("ais")
    private PropertyDeviation<String> ais;

    @JsonProperty("agenda")
    private PropertyDeviation<String> agenda;

    @JsonProperty("ustanovení-neverejnost")
    private PropertyDeviation<String> privacyProvisions;

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
