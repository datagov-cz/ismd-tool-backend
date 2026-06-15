package com.dia.ismdtoolbackend.reconciler.dto;

import com.dia.ismdtoolbackend.reconciler.Mismatch;
import com.dia.ismdtoolbackend.reconciler.MismatchCategory;
import com.dia.ismdtoolbackend.reconciler.ReconciliationReport;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * API shape for a reconciler report. Flattens {@link ReconciliationReport} and surfaces the
 * per-category counts (a derived map, not a record component) explicitly for the FE/admin.
 */
public record ReconciliationReportDto(
        Instant startedAt,
        Instant finishedAt,
        String triggeredBy,
        int graphsScanned,
        int ownedRdfConceptsScanned,
        int pgConceptsScanned,
        int totalMismatches,
        Map<MismatchCategory, Integer> countsByCategory,
        List<Mismatch> mismatches
) {
    public static ReconciliationReportDto from(ReconciliationReport r) {
        return new ReconciliationReportDto(
                r.startedAt(), r.finishedAt(), r.triggeredBy(),
                r.graphsScanned(), r.ownedRdfConceptsScanned(), r.pgConceptsScanned(),
                r.totalMismatches(), r.countsByCategory(), r.mismatches());
    }
}