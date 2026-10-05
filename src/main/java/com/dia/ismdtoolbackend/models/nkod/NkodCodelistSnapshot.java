package com.dia.ismdtoolbackend.models.nkod;

import com.dia.ismdtoolbackend.utility.sparql.SparqlSolutions;
import lombok.Getter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An immutable view of the NKOD codelist datasets, in title order and indexed by dataset IRI.
 * Built once per refresh and swapped in wholesale. A loaded snapshot may hold no entries; that
 * is a catalogue without codelists, distinct from {@link #notLoaded()}. Each entry carries a
 * pre-computed, diacritic-folded search key over its title and description.
 */
public final class NkodCodelistSnapshot {

    @Getter
    private final Instant loadedAt;
    @Getter
    private final boolean loaded;
    @Getter
    private final List<NkodCodelistEntry> entries;
    private final List<String> searchKeys;
    private final Map<String, NkodCodelistEntry> byDatasetIri;

    private NkodCodelistSnapshot(Instant loadedAt, boolean loaded, List<NkodCodelistEntry> entries) {
        this.loadedAt = loadedAt;
        this.loaded = loaded;
        this.entries = List.copyOf(entries);
        this.searchKeys = this.entries.stream()
                .map(entry -> NkodDatasetSnapshot.fold(searchableText(entry.codelist())))
                .toList();
        Map<String, NkodCodelistEntry> index = new LinkedHashMap<>();
        for (NkodCodelistEntry entry : this.entries) {
            index.putIfAbsent(SparqlSolutions.toRawUtf8(entry.datasetIri()), entry);
        }
        this.byDatasetIri = Map.copyOf(index);
    }

    /** The placeholder held until the first refresh succeeds. */
    public static NkodCodelistSnapshot notLoaded() {
        return new NkodCodelistSnapshot(Instant.EPOCH, false, List.of());
    }

    /** Entries are kept in the given order; the first entry wins for a repeated dataset IRI. */
    public static NkodCodelistSnapshot of(Instant loadedAt, List<NkodCodelistEntry> entries) {
        return new NkodCodelistSnapshot(loadedAt, true, entries);
    }

    /** Looks up by dataset IRI; a percent-encoded IRI finds the same entry as its raw UTF-8 form. */
    public Optional<NkodCodelistEntry> find(String datasetIri) {
        if (datasetIri == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byDatasetIri.get(SparqlSolutions.toRawUtf8(datasetIri.trim())));
    }

    /**
     * Entries matching {@code query} (folded substring over title and description), or all
     * entries when the query is blank, in title order.
     */
    public List<NkodCodelistEntry> search(String query) {
        if (query == null || query.isBlank()) {
            return entries;
        }
        String needle = NkodDatasetSnapshot.fold(query.strip());
        List<NkodCodelistEntry> matches = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (searchKeys.get(i).contains(needle)) {
                matches.add(entries.get(i));
            }
        }
        return List.copyOf(matches);
    }

    private static String searchableText(NkodCodelist codelist) {
        String description = codelist.getDescription();
        return codelist.getTitle() + ' ' + (description == null ? "" : description);
    }

    public int size() {
        return entries.size();
    }

    public boolean isStale(Duration ttl, Clock clock) {
        return loadedAt.plus(ttl).isBefore(clock.instant());
    }
}
