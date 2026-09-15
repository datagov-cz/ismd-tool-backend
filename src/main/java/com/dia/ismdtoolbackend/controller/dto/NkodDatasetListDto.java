package com.dia.ismdtoolbackend.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Paged NKOD dataset list. Mirrors {@code GetNkdOntologyListDto} so the FE's list views
 * share one envelope shape.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NkodDatasetListDto {

    private List<NkodDatasetListItemDto> datasets;

    /** Total matching the current query, not the size of this page — the FE pages on it. */
    @JsonProperty("celkový-počet")
    private Integer totalCount;
}