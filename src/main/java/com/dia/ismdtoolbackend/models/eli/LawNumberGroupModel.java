package com.dia.ismdtoolbackend.models.eli;

/**
 * A předpis number and how many acts carry it — the aggregate row behind the grouped search.
 * The count comes from SPARQL over the whole dataset, so it is the true total regardless of
 * how many acts are fetched for display.
 */
public record LawNumberGroupModel(String cislo, int pocet) {
}