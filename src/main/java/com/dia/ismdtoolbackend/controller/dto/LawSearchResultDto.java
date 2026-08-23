package com.dia.ismdtoolbackend.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Grouped law-search result: matches bucketed by předpis number instead of returned flat.
 *
 * <p>A bare number is ambiguous — Czech acts renumber yearly, so "49" matches ~80 unrelated
 * laws and a flat list truncated to the page limit silently drops the one the user wanted.
 * This shape reports the ambiguity ({@link #ambiguous}, {@link #totalMatches}) so the FE can
 * ask for a year rather than guessing.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LawSearchResultDto {

    /** The needle this result answers, echoed back for FE correlation. Null for a blank query. */
    private String query;

    /**
     * True when the query does not identify a single act — a bare number matching several
     * years, so the user must still choose. False once the query pins one act (e.g.
     * "49/1997"), or when nothing matched.
     *
     * <p>The FE's cue to prompt for a year instead of auto-selecting the first row.
     */
    private boolean ambiguous;

    /**
     * Total acts across the returned groups, summed from their dataset-wide counts. Usually
     * exceeds the number of {@code LawDto}s actually returned, since each group's list is
     * capped for display.
     */
    private int totalMatches;

    /**
     * True when the <em>group</em> cap was filled, so further předpis numbers match than are
     * listed here. The FE should show "refine your search" rather than imply the list is
     * complete. Note this can be a benign false positive when exactly {@code limit} groups
     * exist and no more.
     */
    private boolean truncated;

    /**
     * Matches grouped by číslo, best group first: the exact-number group leads, then shortest
     * číslo, then číslo ascending — mirroring how people type ("49" → "490" → "4900").
     * Group size is deliberately NOT a criterion: counts run 100+ for every low číslo, so
     * ordering by size would float whichever number is most legislated rather than the one
     * the user typed. Within a group, acts are ordered newest rok first.
     */
    private List<LawSearchGroupDto> groups;
}