package com.dia.ismdtoolbackend.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
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