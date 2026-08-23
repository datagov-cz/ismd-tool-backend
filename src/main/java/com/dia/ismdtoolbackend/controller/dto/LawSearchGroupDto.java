package com.dia.ismdtoolbackend.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One předpis number with every act carrying that number, newest year first.
 *
 * <p>Czech acts renumber from 1 each year, so a bare number like "49" identifies ~80
 * unrelated laws (49/1997, 49/2020, 49/2026 …) that are all equally good matches. A flat
 * result list fills its whole window with one number's years and hides everything else;
 * grouping makes the ambiguity visible so the user picks a year instead of scrolling.
 *
 * <p>These are separate acts, NOT versions of one another — a law's versions (znění) come
 * from {@code /api/eli/law/versions}.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LawSearchGroupDto {

    /** The předpis number shared by every entry in {@link #laws}, e.g. "49". */
    private String cislo;

    /**
     * How many acts carry this number <strong>dataset-wide</strong>, from the aggregate query.
     * This is NOT {@code laws.size()}: {@link #laws} holds only what was fetched for display
     * and may be capped, so rendering the list length as the total would show a false number.
     */
    private int count;

    /**
     * True when the needle matched this number exactly — the group the user most likely
     * meant. False for groups matched only by prefix or by year.
     */
    private boolean exactNumberMatch;

    /**
     * Acts with this číslo, newest rok first — capped for display, so possibly fewer than
     * {@link #count}. Narrow the query (or pick a year) to reach the rest.
     */
    private List<LawDto> laws;
}