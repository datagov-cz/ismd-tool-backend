package com.dia.ismdtoolbackend.models;

import com.dia.ismdtoolbackend.controller.dto.CodeListDto;
import com.dia.ismdtoolbackend.controller.dto.DataTypeDto;
import com.dia.ismdtoolbackend.controller.dto.NonLegalSourceDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto;
import com.dia.ismdtoolbackend.models.concept.ConceptPropertiesModel;
import com.dia.ismdtoolbackend.models.concept.ConceptRelationshipsModel;
import com.dia.ismdtoolbackend.models.rpp.RppAgenda;
import com.dia.ismdtoolbackend.models.rpp.RppIsvs;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

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

    @JsonProperty("počet-pojmů")
    private Integer conceptCount;

    @Data
    @Builder
    @Jacksonized
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
        private List<String> exactMatches;

        @JsonProperty("definiční-obor")
        private String domain;

        @JsonProperty("obor-hodnot")
        private String range;

        @JsonProperty("obor-hodnot-resolved")
        private DataTypeDto rangeResolved;

        @JsonProperty("nadřazená-třída")
        private List<String> broaderClasses;

        @JsonProperty("nadřazený-vztah")
        private List<String> broaderRelations;

        @JsonProperty("nadřazená-vlastnost")
        private List<String> broaderProperties;

        // Source lists are always serialized — empty array, never null/absent —
        // so the FE has a stable contract (see OntologyDetailExtractor.nullToEmpty
        // / buildResolvedSources / buildNonLegalSources). Overrides the class-level
        // @JsonInclude(NON_NULL).
        @JsonInclude()
        @JsonProperty("definující-ustanovení-právního-předpisu")
        private List<String> definingLegalSources;

        @JsonInclude()
        @JsonProperty("související-ustanovení-právního-předpisu")
        private List<String> relatedLegalSources;

        @JsonInclude()
        @JsonProperty("definující-ustanovení-právního-předpisu-resolved")
        private List<ResolvedLegalSourceDto> definingLegalSourcesResolved;

        @JsonInclude()
        @JsonProperty("související-ustanovení-právního-předpisu-resolved")
        private List<ResolvedLegalSourceDto> relatedLegalSourcesResolved;

        @JsonInclude()
        @JsonProperty("definující-nelegislativní-zdroj")
        private List<NonLegalSourceDto> definingNonLegalSources;

        @JsonInclude()
        @JsonProperty("související-nelegislativní-zdroj")
        private List<NonLegalSourceDto> relatedNonLegalSources;

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

        @JsonProperty("agendový-informační-systém-resolved")
        private RppIsvs aisResolved;

        @JsonProperty("agenda-resolved")
        private RppAgenda agendaResolved;

        @JsonInclude()
        @JsonProperty("ustanovení-dokládající-neveřejnost-údaje")
        private List<String> privacyProvisions;

        @JsonInclude()
        @JsonProperty("ustanovení-dokládající-neveřejnost-údaje-resolved")
        private List<ResolvedLegalSourceDto> privacyProvisionsResolved;

        @JsonProperty("instance-definovány-číselníkem")
        private CodeListDto codeList;

        private List<ConceptPropertiesModel> conceptProperties;

        private List<ConceptRelationshipsModel> conceptRelationships;

        /**
         * How many properties/relationships from OTHER vocabularies point at this concept.
         * Set on the local ontology detail only, where the member lists are scoped to the
         * ontology's own graph; the concept detail lists those members in full instead, so
         * {@code conceptProperties.size() + foreignPropertyCount} there agrees with the
         * concept detail's list length. Null when there are none.
         */
        @JsonProperty("počet-cizích-vlastností")
        private Integer foreignPropertyCount;

        @JsonProperty("počet-cizích-vztahů")
        private Integer foreignRelationshipCount;

        /**
         * Pre-resolved metadata for every referenced concept IRI in this detail
         * (exact matches, broader classes/relations/properties, domain, range,
         * and each property/relationship IRI). Keyed by concept IRI; unresolved
         * IRIs are absent from the map. Populated inline in the detail flow by
         * {@code ReferencedConceptsEnricher}, so the FE needs no secondary
         * resolve round-trip for the detail page.
         */
        @JsonProperty("referencované-pojmy-resolved")
        private Map<String, ResolvedConceptDto> referencedConceptsResolved;
    }
}
