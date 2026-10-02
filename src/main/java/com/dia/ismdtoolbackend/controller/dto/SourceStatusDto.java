package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.enums.SearchSourceStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceStatusDto {
    private SearchSourceStatus status;
    private int returnedCount;
    /**
     * Total ontology matches in this source for the current query (across all
     * pages). Nullable when the source could not produce a count (timeout / error).
     */
    private Integer totalOntologies;
    /**
     * Total concept matches in this source for the current query (across all
     * pages). Nullable when the source could not produce a count (timeout / error).
     */
    private Integer totalConcepts;
    /**
     * Total diagram matches in this source for the current query (across all
     * pages). Only ISMD contributes; nullable when uncounted. NKD is always null.
     */
    private Integer totalDiagrams;
    private String message;
}
