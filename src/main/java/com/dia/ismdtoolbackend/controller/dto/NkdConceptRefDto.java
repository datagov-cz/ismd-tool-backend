package com.dia.ismdtoolbackend.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A reference to the published NKD concept a local copy tracks: its IRI (so the FE can navigate to
 * the NKD concept's detail) and a human label (so the card isn't a bare IRI). The label is null-safe
 * — when the snapshot carries no name the FE falls back to the IRI.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NkdConceptRefDto {
    private String iri;
    private String label;
}
