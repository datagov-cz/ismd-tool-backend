package com.dia.ismdtoolbackend.models.nkod;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NkodDatasetSnapshotTest {

    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");

    private static NkodDatasetRow row(String iri, String title, String description) {
        return new NkodDatasetRow(iri, Map.of("cs", title),
                description == null ? Map.of() : Map.of("cs", description));
    }

    private static NkodDatasetSnapshot snapshotOf(NkodDatasetRow... rows) {
        return NkodDatasetSnapshot.build(NOW, List.of(rows), "cs");
    }

    @Test
    void sortsAlphabeticallyByTitleIgnoringDiacritics() {
        NkodDatasetSnapshot snapshot = snapshotOf(
                row("urn:c", "Železnice", null),
                row("urn:a", "Číselník", null),
                row("urn:b", "Adresy", null));

        assertThat(snapshot.search(null))
                .extracting(NkodDatasetRow::iri)
                .containsExactly("urn:b", "urn:a", "urn:c");
    }

    /**
     * The whole reason search is served from the snapshot: the endpoint offers only LCASE,
     * which does not fold diacritics, so an unaccented query would miss accented titles.
     */
    @Test
    void searchMatchesAccentedTitleFromUnaccentedQuery() {
        NkodDatasetSnapshot snapshot = snapshotOf(row("urn:a", "Číselník území", null));

        assertThat(snapshot.search("ciselnik")).extracting(NkodDatasetRow::iri)
                .containsExactly("urn:a");
    }

    @Test
    void searchMatchesAccentedQueryToo() {
        NkodDatasetSnapshot snapshot = snapshotOf(row("urn:a", "Číselník území", null));

        assertThat(snapshot.search("číselník")).hasSize(1);
    }

    @Test
    void searchIsCaseInsensitive() {
        NkodDatasetSnapshot snapshot = snapshotOf(row("urn:a", "Registr Řidičů", null));

        assertThat(snapshot.search("REGISTR")).hasSize(1);
    }

    @Test
    void searchAlsoMatchesDescription() {
        NkodDatasetSnapshot snapshot = snapshotOf(
                row("urn:a", "Adresy", "Obsahuje údaje o územních prvcích"));

        assertThat(snapshot.search("uzemnich")).extracting(NkodDatasetRow::iri)
                .containsExactly("urn:a");
    }

    @Test
    void blankQueryReturnsEverything() {
        NkodDatasetSnapshot snapshot = snapshotOf(
                row("urn:a", "Adresy", null),
                row("urn:b", "Budovy", null));

        assertThat(snapshot.search("   ")).hasSize(2);
    }

    @Test
    void nonMatchingQueryReturnsEmpty() {
        NkodDatasetSnapshot snapshot = snapshotOf(row("urn:a", "Adresy", null));

        assertThat(snapshot.search("neexistuje")).isEmpty();
    }

    /**
     * The lang parameter must actually reorder results. The original tests all passed "cs",
     * so a completely ignored lang still passed them — these titles sort in the opposite
     * order in each language, so an ignored parameter cannot survive.
     */
    @Test
    void sortsByRequestedLanguageNotOnlyTheHarvestLanguage() {
        NkodDatasetRow a = new NkodDatasetRow("urn:a",
                Map.of("cs", "Adresy", "en", "Zebra"), Map.of());
        NkodDatasetRow b = new NkodDatasetRow("urn:b",
                Map.of("cs", "Zvířata", "en", "Addresses"), Map.of());
        NkodDatasetSnapshot snapshot = NkodDatasetSnapshot.build(NOW, List.of(a, b), "cs");

        assertThat(snapshot.search(null, "cs")).extracting(NkodDatasetRow::iri)
                .containsExactly("urn:a", "urn:b");
        assertThat(snapshot.search(null, "en")).extracting(NkodDatasetRow::iri)
                .containsExactly("urn:b", "urn:a");
    }

    /** A dataset lacking the requested language still appears, ordered by its fallback title. */
    @Test
    void sortingByMissingLanguageFallsBackRatherThanDropping() {
        NkodDatasetRow onlyCs = new NkodDatasetRow("urn:a", Map.of("cs", "Adresy"), Map.of());
        NkodDatasetRow withEn = new NkodDatasetRow("urn:b",
                Map.of("cs", "Zvířata", "en", "Budovy"), Map.of());
        NkodDatasetSnapshot snapshot = NkodDatasetSnapshot.build(NOW, List.of(onlyCs, withEn), "cs");

        assertThat(snapshot.search(null, "en")).extracting(NkodDatasetRow::iri)
                .containsExactly("urn:a", "urn:b");
    }

    @Test
    void blankLanguageKeepsHarvestOrder() {
        NkodDatasetSnapshot snapshot = snapshotOf(
                row("urn:b", "Beta", null), row("urn:a", "Alfa", null));

        assertThat(snapshot.search(null, "  ")).extracting(NkodDatasetRow::iri)
                .containsExactly("urn:a", "urn:b");
    }

    @Test
    void emptySnapshotIsEmptyAndStale() {
        assertThat(NkodDatasetSnapshot.empty().isEmpty()).isTrue();
    }

    /** Falls back to another language rather than rendering a blank row. */
    @Test
    void preferredFallsBackToAnyLanguageWhenRequestedMissing() {
        assertThat(NkodDatasetSnapshot.preferred(Map.of("en", "Addresses"), "cs"))
                .isEqualTo("Addresses");
    }

    @Test
    void preferredReturnsEmptyForNoLabels() {
        assertThat(NkodDatasetSnapshot.preferred(Map.of(), "cs")).isEmpty();
    }
}