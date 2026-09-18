package com.dia.ismdtoolbackend.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * One distribution of an NKOD dataset — the link the user can follow, and enough metadata
 * to label it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NkodDistributionDto {

    @JsonProperty("iri")
    private String iri;

    @JsonProperty("název")
    private Map<String, String> name;

    /**
     * The single link to render: {@code dcat:downloadURL} when the publisher supplied one,
     * otherwise {@code dcat:accessURL}.
     *
     * <p>Only one link is exposed because the two are byte-identical for 98% of
     * distributions; surfacing both would show the same URL twice on nearly every row.
     *
     * <p>Null when the publisher supplied neither — the FE must not render a link then.
     */
    @JsonProperty("odkaz")
    private String link;

    /** {@code dcterms:format} IRI. The only dependable type signal — see {@code odkaz}. */
    @JsonProperty("formát")
    private String format;

    @JsonProperty("media-typ")
    private String mediaType;

    /**
     * True when this is an API or endpoint rather than a downloadable file, so the FE labels
     * it "Otevřít" instead of "Stáhnout". These links are WMS/WFS capabilities documents,
     * SPARQL endpoints, ArcGIS REST roots and map viewers.
     *
     * <p>Always serialized, so the FE can branch on it without a null check.
     *
     * <p>Named {@code sluzba}, not {@code isService}: Lombok would generate
     * {@code isService()} for the latter, which Jackson reads as a second bean property
     * {@code service} and emits alongside {@code je-služba}. A field-level
     * {@code @JsonProperty} does not suppress that duplicate.
     */
    @JsonProperty("je-služba")
    private boolean sluzba;
}