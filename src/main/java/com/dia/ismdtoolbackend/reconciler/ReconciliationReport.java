package com.dia.ismdtoolbackend.reconciler;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The result of one reconciler run: when it ran, how much it scanned, what triggered it, and
 * every {@link Mismatch} found grouped by category. Detection-only — no repair state yet.
 */
public record ReconciliationReport(
        Instant startedAt,
        Instant finishedAt,
        String triggeredBy,
        int graphsScanned,
        int ownedRdfConceptsScanned,
        int pgConceptsScanned,
        List<Mismatch> mismatches
) {

    /** Per-category counts, including zero-count categories, for a stable report shape. */
    public Map<MismatchCategory, Integer> countsByCategory() {
        Map<MismatchCategory, Integer> counts = new EnumMap<>(MismatchCategory.class);
        for (MismatchCategory c : MismatchCategory.values()) {
            counts.put(c, 0);
        }
        for (Mismatch m : mismatches) {
            counts.merge(m.category(), 1, Integer::sum);
        }
        return counts;
    }

    public int totalMismatches() {
        return mismatches.size();
    }
}