package com.dia.ismdtoolbackend.models.eli;

/**
 * A předpis number and how many acts carry it dataset-wide — the aggregate row behind the
 * grouped search.
 */
public record LawNumberGroupModel(String cislo, int pocet) {
}