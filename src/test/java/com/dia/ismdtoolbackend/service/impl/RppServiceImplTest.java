package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.RppSearchResultDto;
import com.dia.ismdtoolbackend.models.rpp.RppAgenda;
import com.dia.ismdtoolbackend.models.rpp.RppIsvs;
import com.dia.ismdtoolbackend.models.rpp.RppSnapshot;
import com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RppServiceImplTest {

    @Mock
    private RppSnapshotHolder snapshotHolder;

    @InjectMocks
    private RppServiceImpl service;

    @BeforeEach
    void setUp() {
        // Each test stubs snapshotHolder.get() with its own fixture.
    }

    @Test
    void emptySnapshotReturnsEmptyListForAgendaSearch() {
        when(snapshotHolder.get()).thenReturn(RppSnapshot.empty());
        assertThat(service.searchAgendas("anything", 20)).isEqualTo(List.of());
    }

    @Test
    void emptySnapshotReturnsEmptyListForIsvsSearch() {
        when(snapshotHolder.get()).thenReturn(RppSnapshot.empty());
        assertThat(service.searchIsvs("anything", 20, "A1382")).isEqualTo(List.of());
    }

    @Test
    void emptyQueryReturnsFirstLimitAgendasInSnapshotOrder() {
        when(snapshotHolder.get()).thenReturn(snapshot(
                List.of(
                        new RppAgenda("iri-1", "1", "Alfa"),
                        new RppAgenda("iri-2", "2", "Beta"),
                        new RppAgenda("iri-3", "3", "Gama")),
                List.of()));
        List<RppSearchResultDto> out = service.searchAgendas(null, 2);
        assertThat(codes(out)).isEqualTo(List.of("1", "2"));
    }

    @Test
    void blankQueryBehavesLikeNull() {
        when(snapshotHolder.get()).thenReturn(snapshot(
                List.of(new RppAgenda("iri-1", "1", "Alfa")),
                List.of()));
        assertThat(service.searchAgendas("   ", 10)).hasSize(1);
    }

    @Test
    void diacriticInsensitiveMatchOnNazev() {
        when(snapshotHolder.get()).thenReturn(snapshot(
                List.of(new RppAgenda("iri-1", "1", "Správa daní"),
                        new RppAgenda("iri-2", "2", "Jiné něco")),
                List.of()));
        List<RppSearchResultDto> out = service.searchAgendas("sprava", 10);
        assertThat(codes(out)).isEqualTo(List.of("1"));
    }

    @Test
    void caseInsensitiveMatchOnNazev() {
        when(snapshotHolder.get()).thenReturn(snapshot(
                List.of(new RppAgenda("iri-1", "1", "Daň z příjmu")),
                List.of()));
        assertThat(service.searchAgendas("DAN", 10)).hasSize(1);
    }

    @Test
    void codeFieldMatches() {
        when(snapshotHolder.get()).thenReturn(snapshot(
                List.of(new RppAgenda("iri-1", "A1382", "něco"),
                        new RppAgenda("iri-2", "B999", "jiné")),
                List.of()));
        List<RppSearchResultDto> out = service.searchAgendas("a1", 10);
        assertThat(codes(out)).isEqualTo(List.of("A1382"));
    }

    @Test
    void limitTruncatesMatches() {
        when(snapshotHolder.get()).thenReturn(snapshot(
                List.of(
                        new RppAgenda("i1", "1", "match a"),
                        new RppAgenda("i2", "2", "match b"),
                        new RppAgenda("i3", "3", "match c"),
                        new RppAgenda("i4", "4", "match d"),
                        new RppAgenda("i5", "5", "match e")),
                List.of()));
        assertThat(service.searchAgendas("match", 2)).hasSize(2);
    }

    @Test
    void limitLargerThanMatchesReturnsAll() {
        when(snapshotHolder.get()).thenReturn(snapshot(
                List.of(new RppAgenda("i1", "1", "match"),
                        new RppAgenda("i2", "2", "nope")),
                List.of()));
        assertThat(service.searchAgendas("match", 50)).hasSize(1);
    }

    @Test
    void isvsPreferredAgendaBringsMatchingToFront() {
        RppIsvs i1 = new RppIsvs("isvs-1", "1", "Alfa", List.of("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A1382"));
        RppIsvs i2 = new RppIsvs("isvs-2", "2", "Beta", List.of("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A9999"));
        RppIsvs i3 = new RppIsvs("isvs-3", "3", "Gama", List.of("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A1382"));
        when(snapshotHolder.get()).thenReturn(snapshot(List.of(), List.of(i1, i2, i3)));

        List<RppSearchResultDto> out = service.searchIsvs(null, 10, "A1382");
        assertThat(codes(out)).isEqualTo(List.of("1", "3", "2"));
    }

    @Test
    void isvsPreferredOrderingIsStableWithinGroup() {
        RppIsvs i1 = new RppIsvs("isvs-1", "1", "Alfa", List.of("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A1382"));
        RppIsvs i2 = new RppIsvs("isvs-2", "2", "Beta", List.of("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A1382"));
        RppIsvs i3 = new RppIsvs("isvs-3", "3", "Gama", List.of("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A9999"));
        when(snapshotHolder.get()).thenReturn(snapshot(List.of(), List.of(i1, i2, i3)));

        List<RppSearchResultDto> out = service.searchIsvs(null, 10, "A1382");
        assertThat(codes(out)).isEqualTo(List.of("1", "2", "3"));
    }

    @Test
    void invalidPreferredAgendaFallsBackSilently() {
        RppIsvs i1 = new RppIsvs("isvs-1", "1", "Alfa", List.of("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A1382"));
        RppIsvs i2 = new RppIsvs("isvs-2", "2", "Beta", List.of("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A9999"));
        when(snapshotHolder.get()).thenReturn(snapshot(List.of(), List.of(i1, i2)));

        List<RppSearchResultDto> out = service.searchIsvs(null, 10, "###not-valid###");
        assertThat(codes(out)).isEqualTo(List.of("1", "2"));
    }

    @Test
    void blankPreferredAgendaSkipsOrdering() {
        RppIsvs i1 = new RppIsvs("isvs-1", "1", "Alfa", List.of());
        RppIsvs i2 = new RppIsvs("isvs-2", "2", "Beta", List.of());
        when(snapshotHolder.get()).thenReturn(snapshot(List.of(), List.of(i1, i2)));

        assertThat(codes(service.searchIsvs(null, 10, ""))).isEqualTo(List.of("1", "2"));
        assertThat(codes(service.searchIsvs(null, 10, null))).isEqualTo(List.of("1", "2"));
    }

    @Test
    void numericCodeOrderingPreservedFromSnapshot() {
        when(snapshotHolder.get()).thenReturn(snapshot(
                List.of(new RppAgenda("i1", "9", "n9"),
                        new RppAgenda("i2", "10", "n10"),
                        new RppAgenda("i3", "100", "n100")),
                List.of()));
        assertThat(codes(service.searchAgendas(null, 10))).isEqualTo(List.of("9", "10", "100"));
    }

    @Test
    void dtoMappingProducesIriCodeNazev() {
        when(snapshotHolder.get()).thenReturn(snapshot(
                List.of(new RppAgenda("iri-x", "42", "Název X")),
                List.of()));
        RppSearchResultDto dto = service.searchAgendas(null, 10).get(0);
        assertThat(dto.getIri()).isEqualTo("iri-x");
        assertThat(dto.getCode()).isEqualTo("42");
        assertThat(dto.getNazev()).isEqualTo("Název X");
    }

    @Test
    void preferredAgendaLimitAppliesAfterPartition() {
        RppIsvs i1 = new RppIsvs("isvs-1", "1", "Alfa", List.of("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A9999"));
        RppIsvs i2 = new RppIsvs("isvs-2", "2", "Beta", List.of("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A1382"));
        when(snapshotHolder.get()).thenReturn(snapshot(List.of(), List.of(i1, i2)));

        List<RppSearchResultDto> out = service.searchIsvs(null, 1, "A1382");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getCode()).as("preferred match should win the single limit slot").isEqualTo("2");
    }

    private static List<String> codes(List<RppSearchResultDto> rows) {
        return rows.stream().map(RppSearchResultDto::getCode).toList();
    }

    private static RppSnapshot snapshot(List<RppAgenda> agendas, List<RppIsvs> isvs) {
        Map<String, List<String>> byIsvs = isvs.stream()
                .filter(i -> i.getIri() != null)
                .collect(Collectors.toUnmodifiableMap(
                        RppIsvs::getIri,
                        i -> List.copyOf(i.getAgendaIris()),
                        (a, b) -> a));
        return new RppSnapshot(Instant.parse("2026-04-21T12:00:00Z"),
                List.copyOf(agendas), List.copyOf(isvs), byIsvs);
    }
}
