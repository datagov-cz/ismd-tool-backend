package com.dia.ismdtoolbackend.models;

import com.dia.ismdtoolbackend.models.concept.ConceptPropertiesModel;
import com.dia.ismdtoolbackend.models.concept.ConceptRelationshipsModel;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
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
        private Map<String, String> name;

        @JsonProperty("alternativní-název")
        private Map<String, Object> alternativeName;

        @JsonProperty("definice")
        private Map<String, String> definition;

        @JsonProperty("popis")
        private Map<String, String> description;

        @JsonProperty("identifikátor")
        private String identifier;

        @JsonProperty("ekvivalentní-pojem")
        private List<Map<String, String>> exactMatches;

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
        private List<Map<String, Object>> definingNonLegalSources;

        @JsonProperty("související-nelegislativní-zdroj")
        private List<Map<String, Object>> relatedNonLegalSources;

        @JsonProperty("způsob-sdílení-údaje")
        private List<String> sharingMethods;

        @JsonProperty("způsob-získání-údaje")
        private String acquisitionMethod;

        @JsonProperty("typ-obsahu-údaje")
        private String contentType;

        @JsonProperty("je-ppdf")
        private Boolean isPpdf;

        @JsonProperty("agendový-informační-systém")
        private String ais;

        @JsonProperty("agenda")
        private String agenda;

        @JsonProperty("ustanovení-neveřejnost")
        private String privacyProvisions;

        private List<ConceptPropertiesModel> conceptProperties;

        private List<ConceptRelationshipsModel> conceptRelationships;
    }
}
