package com.dia.ismdtoolbackend.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Detail of one NKOD dataset and the concepts it is annotated with via
 * {@code týká-se-pojmu} (issue #123).
 *
 * <p>{@code pojmy} reuses {@link MinimalConceptDto}, the row type the vocabulary detail
 * page already renders, so both concept lists look and behave the same. NKD concepts carry
 * no slug, so the FE deep-links them by IRI.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GetNkodDatasetDto {

    @JsonProperty("iri")
    private String iri;

    @JsonProperty("název")
    private Map<String, String> name;

    @JsonProperty("popis")
    private Map<String, String> description;

    /** {@code dcat:landingPage} — the "open in NKD" target. */
    @JsonProperty("vstupní-stránka")
    private String landingPage;

    /**
     * Always serialized, even when empty: an empty list is the expected state until
     * publishers populate {@code týká-se-pojmu}, and the FE distinguishes "no concepts"
     * from "field missing".
     */
    @JsonProperty("pojmy")
    private List<MinimalConceptDto> concepts;

    @JsonProperty("počet-pojmů")
    private Integer conceptCount;
}