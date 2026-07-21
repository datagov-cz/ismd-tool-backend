package com.dia.ismdtoolbackend.models.concept;

import com.dia.ismdtoolbackend.controller.dto.DataTypeDto;
import com.dia.ismdtoolbackend.controller.dto.NkdConceptRefDto;
import com.dia.ismdtoolbackend.controller.dto.NonLegalSourceDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.enums.SnapshotOrigin;
import com.dia.ismdtoolbackend.models.rpp.RppAgenda;
import com.dia.ismdtoolbackend.models.rpp.RppIsvs;
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

    /**
     * The object/subject role marker of a TRIDA concept ({@code "objekt"} / {@code "subjekt"}), derived
     * from the {@code typ} type list. Unlike the concept KIND (třída/vztah/vlastnost), this pair IS
     * convertible, so it is emitted separately and is syncable.
     */
    @JsonProperty("typ-objektu-subjektu")
    private PropertyDeviation<String> objectSubjectType;

    /**
     * The public/private data classification ({@code true} = veřejný, {@code false} = neveřejný), derived
     * from the {@code typ} type list. Syncable — but going private requires a valid privacy provision, so
     * the sync co-syncs {@code ustanovení-dokládající-neveřejnost-údaje} and rejects a private twin that
     * carries none.
     */
    @JsonProperty("veřejnost-údaje")
    private PropertyDeviation<Boolean> isPublic;

    @JsonProperty("ais")
    private PropertyDeviation<String> ais;

    @JsonProperty("agenda")
    private PropertyDeviation<String> agenda;

    @JsonProperty("ustanovení-dokládající-neveřejnost-údaje")
    private PropertyDeviation<List<String>> privacyProvisions;

    /**
     * Resolved navigation metadata for every concept IRI referenced by this deviation — the {@link #source}
     * NKD twin and the {@code definiční-obor} / concept-typed {@code obor-hodnot} on BOTH sides of the diff.
     * Keyed by IRI; unresolved IRIs are absent (FE falls back to the bare IRI). Same map contract as
     * {@code OntologyDetailModel.ConceptDetailModel.referencedConceptsResolved}, so the FE reuses its reader.
     */
    @JsonProperty("referencované-pojmy-resolved")
    private Map<String, ResolvedConceptDto> referencedConceptsResolved;

    /**
     * Resolved datatype metadata for {@code obor-hodnot} values that are XSD datatypes (VLASTNOST ranges),
     * on both diff sides. Keyed by the raw range IRI/value; concept ranges live in
     * {@link #referencedConceptsResolved} instead.
     */
    @JsonProperty("obor-hodnot-resolved")
    private Map<String, DataTypeDto> rangeResolved;

    /** Resolved RPP agenda metadata for {@code agenda} IRIs on both diff sides, keyed by IRI. */
    @JsonProperty("agenda-resolved")
    private Map<String, RppAgenda> agendaResolved;

    /** Resolved RPP ISVS (AIS) metadata for {@code ais} IRIs on both diff sides, keyed by IRI. */
    @JsonProperty("ais-resolved")
    private Map<String, RppIsvs> aisResolved;

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
