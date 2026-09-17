package com.dia.ismdtoolbackend.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One předpis number with every act carrying that number, newest year first. Czech acts
 * renumber from 1 each year, so a bare "49" matches dozens of unrelated laws.
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
     * How many acts carry this number dataset-wide, from the aggregate query. Not
     * {@code laws.size()}, which is only what was fetched for display.
     */
    private int count;

    /** True when the needle matched this number exactly, rather than by prefix or by year. */
    private boolean exactNumberMatch;

    /**
     * Acts with this číslo, newest rok first — capped for display, so possibly fewer than
     * {@link #count}. Narrow the query (or pick a year) to reach the rest.
     */
    private List<LawDto> laws;
}