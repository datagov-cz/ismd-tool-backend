package com.dia.ismdtoolbackend.models.nkod;

import lombok.Getter;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An immutable, query-ready view of the NKOD catalogue.
 *
 * <p>Built once per refresh and swapped in wholesale, so readers never see a half-built
 * index and need no locking.
 *
 * <p>Rows are pre-sorted by title and carry a pre-computed, diacritic-folded search key.
 */
public final class NkodDatasetSnapshot {

    @Getter
    private final Instant loadedAt;
    private final List<IndexedRow> rows;

    /** Language the rows are pre-sorted by; other languages are re-sorted per request. */
    private final String sortLang;

    private NkodDatasetSnapshot(Instant loadedAt, List<IndexedRow> rows, String sortLang) {
        this.loadedAt = loadedAt;
        this.rows = rows;
        this.sortLang = sortLang;
    }

    /** One row plus its folded search key. */
    private record IndexedRow(NkodDatasetRow row, String searchKey) {
    }

    public static NkodDatasetSnapshot empty() {
        return new NkodDatasetSnapshot(Instant.EPOCH, List.of(), "cs");
    }

    /**
     * Indexes harvested rows: folds a search key over title and description, then sorts by
     * the preferred-language title so paging is stable and alphabetical.
     */
    public static NkodDatasetSnapshot build(Instant loadedAt, List<NkodDatasetRow> harvested, String lang) {
        List<IndexedRow> indexed = new ArrayList<>(harvested.size());
        for (NkodDatasetRow row : harvested) {
            indexed.add(new IndexedRow(row, fold(searchableText(row))));
        }
        Comparator<IndexedRow> byTitle = Comparator
                .comparing((IndexedRow r) -> fold(preferred(r.row().name(), lang)))
                .thenComparing(r -> r.row().iri());
        indexed.sort(byTitle);
        return new NkodDatasetSnapshot(loadedAt, List.copyOf(indexed), lang);
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    public int size() {
        return rows.size();
    }

    public boolean isStale(Duration ttl, Clock clock) {
        return loadedAt.plus(ttl).isBefore(clock.instant());
    }

    /**
     * Rows matching {@code query} (folded substring over title and description), or all rows
     * when the query is blank. Returns the matches in sorted order; the caller pages them.
     */
    public List<NkodDatasetRow> search(String query) {
        return search(query, sortLang);
    }

    /**
     * As {@link #search(String)}, but ordered by the title in {@code lang}.
     *
     * <p>Rows are stored pre-sorted in {@link #sortLang}, so that language is returned
     * as-is; any other re-sorts the matches.
     */
    public List<NkodDatasetRow> search(String query, String lang) {
        List<NkodDatasetRow> matches = matching(query);
        if (lang == null || lang.isBlank() || lang.equals(sortLang)) {
            return matches;
        }
        return matches.stream()
                .sorted(Comparator
                        .comparing((NkodDatasetRow r) -> fold(preferred(r.name(), lang)))
                        .thenComparing(NkodDatasetRow::iri))
                .toList();
    }

    private List<NkodDatasetRow> matching(String query) {
        if (query == null || query.isBlank()) {
            return rows.stream().map(IndexedRow::row).toList();
        }
        String needle = fold(query);
        return rows.stream()
                .filter(r -> r.searchKey().contains(needle))
                .map(IndexedRow::row)
                .toList();
    }

    private static String searchableText(NkodDatasetRow row) {
        StringBuilder sb = new StringBuilder();
        row.name().values().forEach(v -> sb.append(v).append(' '));
        row.description().values().forEach(v -> sb.append(v).append(' '));
        return sb.toString();
    }

    /** Preferred-language value, falling back to any other language, then empty. */
    public static String preferred(Map<String, String> byLang, String lang) {
        if (byLang == null || byLang.isEmpty()) {
            return "";
        }
        String exact = byLang.get(lang);
        if (exact != null) {
            return exact;
        }
        return byLang.values().iterator().next();
    }

    /**
     * Lowercases and strips diacritics, so a query typed without Czech accents still
     * matches.
     */
    static String fold(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }
}