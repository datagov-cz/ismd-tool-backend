package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.enums.ConceptType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Slim concept projection for /api/ontology/concepts. The FE distinguishes the
 * source by which navigation key is populated: local ISMD concepts ship their
 * slug (PG primary navigation key); NKD concepts ship the IRI only (no slug
 * exists), so the FE deep-links via IRI.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MinimalConceptDto {
    private String iri;
    private String slug;
    private Map<String, String> name;
    private ConceptType conceptType;
}
