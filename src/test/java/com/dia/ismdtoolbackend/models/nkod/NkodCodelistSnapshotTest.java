package com.dia.ismdtoolbackend.models.nkod;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NkodCodelistSnapshotTest {

    private static final Instant LOADED = Instant.parse("2026-09-29T10:00:00Z");

    private static NkodCodelistEntry entry(String iri, String title, String... urls) {
        return new NkodCodelistEntry(
                NkodCodelist.builder().datasetIri(iri).title(title).publisher("DIA").build(),
                List.of(urls));
    }

    @Test
    void notLoaded_isNotLoadedAndFindsNothing() {
        NkodCodelistSnapshot snap = NkodCodelistSnapshot.notLoaded();

        assertThat(snap.isLoaded()).isFalse();
        assertThat(snap.size()).isZero();
        assertThat(snap.find("https://data.gov.cz/zdroj/datové-sady/x")).isEmpty();
    }

    @Test
    void of_noEntries_isLoaded() {
        NkodCodelistSnapshot snap = NkodCodelistSnapshot.of(LOADED, List.of());

        assertThat(snap.isLoaded()).isTrue();
        assertThat(snap.size()).isZero();
    }

    @Test
    void find_looksUpByDatasetIri() {
        NkodCodelistSnapshot snap = NkodCodelistSnapshot.of(LOADED, List.of(
                entry("https://data.gov.cz/zdroj/datové-sady/17651921/a", "Aktér"),
                entry("https://data.gov.cz/zdroj/datové-sady/17651921/b", "Pohlaví")));

        assertThat(snap.find("https://data.gov.cz/zdroj/datové-sady/17651921/b"))
                .map(e -> e.codelist().getTitle()).contains("Pohlaví");
        assertThat(snap.find("https://data.gov.cz/zdroj/datové-sady/17651921/missing")).isEmpty();
        assertThat(snap.find(null)).isEmpty();
    }

    @Test
    void find_percentEncodedIriFindsTheRawEntry() {
        NkodCodelistSnapshot snap = NkodCodelistSnapshot.of(LOADED, List.of(
                entry("https://data.gov.cz/zdroj/datové-sady/17651921/b", "Pohlaví")));

        assertThat(snap.find("https://data.gov.cz/zdroj/datov%C3%A9-sady/17651921/b"))
                .map(e -> e.codelist().getTitle()).contains("Pohlaví");
        assertThat(snap.find(" https://data.gov.cz/zdroj/datové-sady/17651921/b "))
                .map(e -> e.codelist().getTitle()).contains("Pohlaví");
    }

    @Test
    void find_percentEncodedEntryIsFoundByTheRawIri() {
        NkodCodelistSnapshot snap = NkodCodelistSnapshot.of(LOADED, List.of(
                entry("https://data.gov.cz/zdroj/datov%C3%A9-sady/17651921/b", "Pohlaví")));

        assertThat(snap.find("https://data.gov.cz/zdroj/datové-sady/17651921/b")).isPresent();
    }

    @Test
    void entries_keepTheGivenOrder() {
        NkodCodelistSnapshot snap = NkodCodelistSnapshot.of(LOADED, List.of(
                entry("https://x/b", "Beta"), entry("https://x/a", "Alfa")));

        assertThat(snap.getEntries()).extracting(NkodCodelistEntry::datasetIri)
                .containsExactly("https://x/b", "https://x/a");
    }

    @Test
    void repeatedDatasetIri_firstEntryWins() {
        NkodCodelistSnapshot snap = NkodCodelistSnapshot.of(LOADED, List.of(
                entry("https://x/a", "First"), entry("https://x/a", "Second")));

        assertThat(snap.find("https://x/a")).map(e -> e.codelist().getTitle()).contains("First");
    }

    @Test
    void isImmutable_againstTheSourceList() {
        List<NkodCodelistEntry> source = new ArrayList<>(List.of(entry("https://x/a", "Alfa")));
        NkodCodelistSnapshot snap = NkodCodelistSnapshot.of(LOADED, source);

        source.add(entry("https://x/b", "Beta"));

        assertThat(snap.size()).isEqualTo(1);
        assertThat(snap.find("https://x/b")).isEmpty();
        assertThatThrownBy(() -> snap.getEntries().add(entry("https://x/c", "C")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static NkodCodelistEntry described(String iri, String title, String description) {
        return new NkodCodelistEntry(
                NkodCodelist.builder().datasetIri(iri).title(title).description(description).publisher("DIA").build(),
                List.of());
    }

    private static NkodCodelistSnapshot searchable() {
        return NkodCodelistSnapshot.of(LOADED, List.of(
                described("https://x/jazyk", "Jazyk", null),
                described("https://x/obce", "Obce", "Číselník územních jednotek"),
                described("https://x/pohlavi", "Pohlaví", "Kód ČSÚ: 102")));
    }

    @Test
    void search_blankOrNullQuery_returnsEverythingInOrder() {
        assertThat(searchable().search(null)).extracting(NkodCodelistEntry::datasetIri)
                .containsExactly("https://x/jazyk", "https://x/obce", "https://x/pohlavi");
        assertThat(searchable().search("   ")).hasSize(3);
    }

    @Test
    void search_matchesAnAccentedTitleFromAnUnaccentedQuery_ignoringCase() {
        assertThat(searchable().search("POHLAVI")).extracting(NkodCodelistEntry::datasetIri)
                .containsExactly("https://x/pohlavi");
        assertThat(searchable().search(" pohlaví ")).hasSize(1);
    }

    @Test
    void search_alsoMatchesTheDescription() {
        assertThat(searchable().search("uzemnich")).extracting(NkodCodelistEntry::datasetIri)
                .containsExactly("https://x/obce");
    }

    @Test
    void search_noMatch_isEmpty() {
        assertThat(searchable().search("neexistuje")).isEmpty();
    }

    @Test
    void isStale_afterTtl() {
        NkodCodelistSnapshot snap = NkodCodelistSnapshot.of(LOADED, List.of(entry("https://x/a", "Alfa")));
        Duration ttl = Duration.ofHours(24);

        assertThat(snap.isStale(ttl, Clock.fixed(LOADED.plus(Duration.ofHours(23)), ZoneOffset.UTC))).isFalse();
        assertThat(snap.isStale(ttl, Clock.fixed(LOADED.plus(Duration.ofHours(25)), ZoneOffset.UTC))).isTrue();
    }

    @Test
    void entry_copiesUrlsAndTreatsNullAsEmpty() {
        List<String> urls = new ArrayList<>(List.of("https://x/a.jsonld"));
        NkodCodelistEntry e = new NkodCodelistEntry(
                NkodCodelist.builder().datasetIri("https://x/a").title("A").publisher("P").build(), urls);
        urls.add("https://x/b.jsonld");

        assertThat(e.downloadUrls()).containsExactly("https://x/a.jsonld");
        assertThat(new NkodCodelistEntry(e.codelist(), null).downloadUrls()).isEmpty();
    }

    @Test
    void entry_isUnresolvedUntilGivenACodeListIri() {
        NkodCodelistEntry e = entry("https://x/a", "A", "https://x/a.jsonld");

        assertThat(e.isResolved()).isFalse();
        assertThat(e.codeListIri()).isNull();

        NkodCodelistEntry resolved = e.withCodeListIri("https://x/ciselnik/a");

        assertThat(resolved.isResolved()).isTrue();
        assertThat(resolved.codeListIri()).isEqualTo("https://x/ciselnik/a");
        assertThat(resolved.codelist().getTitle()).isEqualTo("A");
        assertThat(resolved.downloadUrls()).containsExactly("https://x/a.jsonld");
        assertThat(e.isResolved()).as("original untouched").isFalse();
        assertThat(resolved.withCodeListIri(null).isResolved()).isFalse();
    }
}
