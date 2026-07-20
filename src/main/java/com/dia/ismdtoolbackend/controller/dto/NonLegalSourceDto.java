package com.dia.ismdtoolbackend.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Stable response contract: every key is always serialized (explicit
 * {@code null} when absent), so consumers null-check rather than presence-check.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NonLegalSourceDto {

    private String iri;

    @JsonProperty("typ")
    private String typ;

    @JsonProperty("název")
    private Map<String, String> nazev;

    @JsonProperty("popis")
    private Map<String, String> popis;

    private String url;
}