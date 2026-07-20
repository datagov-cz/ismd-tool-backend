package com.dia.ismdtoolbackend.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The číselník a class declares its instances are defined by. Mirrors the OFN JSON-LD
 * shape: the číselník IRI, its type, and the NKOD dataset that covers it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeListDto {

    private String iri;

    @JsonProperty("typ")
    private String typ;

    @JsonProperty("datová-sada-v-nkod")
    private String datovaSadaVNkod;
}
