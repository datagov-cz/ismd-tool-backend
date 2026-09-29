package com.dia.ismdtoolbackend.models.nkod;

import lombok.Getter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An immutable view of the NKOD codelist datasets, in title order and indexed by dataset IRI.
 * Built once per refresh and swapped in wholesale.
 */
public final class NkodCodelistSnapshot {

    @Getter
    private final Instant loadedAt;
    @Getter
    private final List<NkodCodelistEntry> entries;
    private final Map<String, NkodCodelistEntry> byDatasetIri;

    private NkodCodelistSnapshot(Instant loadedAt, List<NkodCodelistEntry> entries) {
        this.loadedAt = loadedAt;
        this.entries = List.copyOf(entries);
        Map<String, NkodCodelistEntry> index = new LinkedHashMap<>();
        for (NkodCodelistEntry entry : this.entries) {
            index.putIfAbsent(entry.datasetIri(), entry);
        }
        this.byDatasetIri = Map.copyOf(index);
    }

    public static NkodCodelistSnapshot empty() {
        return new NkodCodelistSnapshot(Instant.EPOCH, List.of());
    }

    /** Entries are kept in the given order; the first entry wins for a repeated dataset IRI. */
    public static NkodCodelistSnapshot of(Instant loadedAt, List<NkodCodelistEntry> entries) {
        return new NkodCodelistSnapshot(loadedAt, entries);
    }

    public Optional<NkodCodelistEntry> find(String datasetIri) {
        if (datasetIri == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byDatasetIri.get(datasetIri));
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public boolean isStale(Duration ttl, Clock clock) {
        return loadedAt.plus(ttl).isBefore(clock.instant());
    }
}
