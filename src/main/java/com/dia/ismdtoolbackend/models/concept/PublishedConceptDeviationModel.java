package com.dia.ismdtoolbackend.models.concept;

import com.dia.ismdtoolbackend.controller.dto.NkdConceptRefDto;
import com.dia.ismdtoolbackend.controller.dto.NonLegalSourceDto;
import com.dia.ismdtoolbackend.enums.SnapshotOrigin;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * A per-characteristic diff against a published NKD concept. One model serves both deviation cases —
 * the comparison is identical, only the <em>meaning</em> of each {@link PropertyDeviation}'s value pair
 * differs — so {@link #origin} tells the reader which it is holding:
 *
 * <ul>
 *   <li>{@link SnapshotOrigin#LINK_TARGET} — {@code localValue} is the <strong>stored local copy</strong>
 *       of a foreign NKD concept, {@code publishedValue} is live NKD.</li>
 *   <li>{@link SnapshotOrigin#WORKING_COPY} — {@code localValue} is <strong>the user's own value</strong>,
 *       {@code publishedValue} is the concept's NKD twin at the same IRI.</li>
 * </ul>
 *
 * {@link #source} is the NKD resource being compared against, in both cases, so the block is
 * self-describing wherever it is embedded and the FE can navigate from it.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PublishedConceptDeviationModel {

    private DeviationStatus status;
    private String errorMessage;

    /** Which deviation case this is */
    private SnapshotOrigin origin;

    /** The NKD published resource this was compared against */
    private NkdConceptRefDto source;

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
    private PropertyDeviation<List<String>> exactMatches;

    @JsonProperty("definující-ustanovení-právního-předpisu")
    private PropertyDeviation<List<String>> definingLegalSources;

    @JsonProperty("související-ustanovení-právního-předpisu")
    private PropertyDeviation<List<String>> relatedLegalSources;

    @JsonProperty("definující-nelegislativní-zdroj")
    private PropertyDeviation<List<NonLegalSourceDto>> definingNonLegalSources;

    @JsonProperty("související-nelegislativní-zdroj")
    private PropertyDeviation<List<NonLegalSourceDto>> relatedNonLegalSources;

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

    @JsonProperty("ustanovení-dokládající-neveřejnost-údaje")
    private PropertyDeviation<List<String>> privacyProvisions;

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
        QUERY_ERROR,
        /**
         * A LINK_TARGET snapshot whose deviation has not been evaluated yet (cold load) or is being
         * (re-)evaluated by the async warmer (cold/stale ontology-detail load). FE shows a spinner and
         * re-fetches; {@code availableActions} is empty while pending.
         */
        PENDING
    }
}
