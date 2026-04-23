package com.dia.ismdtoolbackend.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Flat summary of an NKD ontology suitable for list views (e.g. the FE's
 * "last accessed" tab). A trimmed projection of {@link com.dia.ismdtoolbackend.models.OntologyDetailModel}
 * — we drop the concept list because returning N×full-detail for a tile row
 * would balloon the payload with no UX benefit.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NkdOntologyListItemDto {

    @JsonProperty("iri")
    private String iri;

    @JsonProperty("název")
    private Map<String, String> name;

    @JsonProperty("popis")
    private Map<String, String> description;

    @JsonProperty("časový-okamžik-vytvoření")
    private String creationDate;

    @JsonProperty("časový-okamžik-poslední-změny")
    private String modificationDate;
}
