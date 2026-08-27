package com.dia.ismdtoolbackend.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Grouped law-search result: matches bucketed by předpis number instead of returned flat,
 * with an explicit ambiguity signal so the FE can ask for a year rather than guessing.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LawSearchResultDto {

    /** The needle this result answers, echoed back for FE correlation. Null for a blank query. */
    private String query;

    /**
     * True when the query does not identify a single act, so the user must still choose.
     * False once the query pins one act (e.g. "49/1997"), or when nothing matched.
     */
    private boolean ambiguous;

    /**
     * Total acts across the returned groups, summed from their dataset-wide counts. Usually
     * exceeds the number of {@code LawDto}s returned, since each group's list is capped.
     */
    private int totalMatches;

    /**
     * True when the group cap was filled, so further předpis numbers match than are listed
     * here. Can be a benign false positive when exactly {@code limit} groups exist.
     */
    private boolean truncated;

    /**
     * Matches grouped by číslo, best group first: exact-number group, then shortest číslo,
     * then číslo ascending. Within a group, acts are ordered newest rok first.
     */
    private List<LawSearchGroupDto> groups;
}