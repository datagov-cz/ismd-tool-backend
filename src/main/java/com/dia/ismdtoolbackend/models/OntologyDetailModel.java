package com.dia.ismdtoolbackend.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OntologyDetailModel {

    @JsonProperty("@context")
    private String context;

    @JsonProperty("iri")
    private String iri;

    @JsonProperty("typ")
    private List<String> types;

    @JsonProperty("název")
    private Map<String, String> name;

    @JsonProperty("popis")
    private Map<String, String> description;

    @JsonProperty("časový-okamžik-vytvoření")
    private String creationDate;

    @JsonProperty("časový-okamžik-poslední-změny")
    private String modificationDate;

    @JsonProperty("pojmy")
    private List<ConceptDetailModel> concepts;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConceptDetailModel {

        @JsonProperty("iri")
        private String iri;

        @JsonProperty("typ")
        private List<String> types;

        @JsonProperty("název")
        private Map<String, Object> name;

        @JsonProperty("alternativní-název")
        private Map<String, Object> alternativeName;

        @JsonProperty("definice")
        private Map<String, Object> definition;

        @JsonProperty("popis")
        private Map<String, Object> description;

        @JsonProperty("identifikátor")
        private List<String> identifiers;

        @JsonProperty("ekvivalentní-pojem")
        private List<Map<String, Object>> exactMatches;

        @JsonProperty("definiční-obor")
        private String domain;

        @JsonProperty("obor-hodnot")
        private String range;

        @JsonProperty("nadřazená-třída")
        private List<String> broaderClasses;

        @JsonProperty("nadřazený-vztah")
        private List<String> broaderRelations;

        @JsonProperty("nadřazená-vlastnost")
        private List<String> broaderProperties;

        @JsonProperty("definující-ustanovení-právního-předpisu")
        private List<String> definingLegalSources;

        @JsonProperty("související-ustanovení-právního-předpisu")
        private List<String> relatedLegalSources;

        @JsonProperty("definující-nelegislativní-zdroj")
        private List<String> definingNonLegalSources;

        @JsonProperty("související-nelegislativní-zdroj")
        private List<String> relatedNonLegalSources;

        @JsonProperty("způsob-sdílení-údajů")
        private List<String> sharingMethods;

        @JsonProperty("způsob-získání-údajů")
        private String acquisitionMethod;

        @JsonProperty("typ-obsahu-údajů")
        private String contentType;

        @JsonProperty("je-ppdf")
        private Boolean isPpdf;

        @JsonProperty("ais")
        private String ais;

        @JsonProperty("agenda")
        private String agenda;

        @JsonProperty("ustanovení-neverejnost")
        private List<String> privacyProvisions;
    }
}
