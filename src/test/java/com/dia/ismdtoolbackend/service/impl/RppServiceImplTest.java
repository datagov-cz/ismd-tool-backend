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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        assertEquals(List.of(), service.searchAgendas("anything", 20));
    }

    @Test
    void emptySnapshotReturnsEmptyListForIsvsSearch() {
        when(snapshotHolder.get()).thenReturn(RppSnapshot.empty());
        assertEquals(List.of(), service.searchIsvs("anything", 20, "A1382"));
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
        assertEquals(List.of("1", "2"), codes(out));
    }

    @Test
    void blankQueryBehavesLikeNull() {
        when(snapshotHolder.get()).thenReturn(snapshot(
                List.of(new RppAgenda("iri-1", "1", "Alfa")),
                List.of()));
        assertEquals(1, service.searchAgendas("   ", 10).size());
    }

    @Test
    void diacriticInsensitiveMatchOnNazev() {
        when(snapshotHolder.get()).thenReturn(snapshot(
                List.of(new RppAgenda("iri-1", "1", "Správa daní"),
                        new RppAgenda("iri-2", "2", "Jiné něco")),
                List.of()));
        List<RppSearchResultDto> out = service.searchAgendas("sprava", 10);
        assertEquals(List.of("1"), codes(out));
    }

    @Test
    void caseInsensitiveMatchOnNazev() {
        when(snapshotHolder.get()).thenReturn(snapshot(
                List.of(new RppAgenda("iri-1", "1", "Daň z příjmu")),
                List.of()));
        assertEquals(1, service.searchAgendas("DAN", 10).size());
    }

    @Test
    void codeFieldMatches() {
        when(snapshotHolder.get()).thenReturn(snapshot(
                List.of(new RppAgenda("iri-1", "A1382", "něco"),
                        new RppAgenda("iri-2", "B999", "jiné")),
                List.of()));
        List<RppSearchResultDto> out = service.searchAgendas("a1", 10);
        assertEquals(List.of("A1382"), codes(out));
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
        assertEquals(2, service.searchAgendas("match", 2).size());
    }

    @Test
    void limitLargerThanMatchesReturnsAll() {
        when(snapshotHolder.get()).thenReturn(snapshot(
                List.of(new RppAgenda("i1", "1", "match"),
                        new RppAgenda("i2", "2", "nope")),
                List.of()));
        assertEquals(1, service.searchAgendas("match", 50).size());
    }

    @Test
    void isvsPreferredAgendaBringsMatchingToFront() {
        RppIsvs i1 = new RppIsvs("isvs-1", "1", "Alfa", List.of("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A1382"));
        RppIsvs i2 = new RppIsvs("isvs-2", "2", "Beta", List.of("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A9999"));
        RppIsvs i3 = new RppIsvs("isvs-3", "3", "Gama", List.of("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A1382"));
        when(snapshotHolder.get()).thenReturn(snapshot(List.of(), List.of(i1, i2, i3)));

        List<RppSearchResultDto> out = service.searchIsvs(null, 10, "A1382");
        assertEquals(List.of("1", "3", "2"), codes(out));
    }

    @Test
    void isvsPreferredOrderingIsStableWithinGroup() {
        RppIsvs i1 = new RppIsvs("isvs-1", "1", "Alfa", List.of("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A1382"));
        RppIsvs i2 = new RppIsvs("isvs-2", "2", "Beta", List.of("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A1382"));
        RppIsvs i3 = new RppIsvs("isvs-3", "3", "Gama", List.of("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A9999"));
        when(snapshotHolder.get()).thenReturn(snapshot(List.of(), List.of(i1, i2, i3)));

        List<RppSearchResultDto> out = service.searchIsvs(null, 10, "A1382");
        assertEquals(List.of("1", "2", "3"), codes(out));
    }

    @Test
    void invalidPreferredAgendaFallsBackSilently() {
        RppIsvs i1 = new RppIsvs("isvs-1", "1", "Alfa", List.of("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A1382"));
        RppIsvs i2 = new RppIsvs("isvs-2", "2", "Beta", List.of("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A9999"));
        when(snapshotHolder.get()).thenReturn(snapshot(List.of(), List.of(i1, i2)));

        List<RppSearchResultDto> out = service.searchIsvs(null, 10, "###not-valid###");
        assertEquals(List.of("1", "2"), codes(out));
    }

    @Test
    void blankPreferredAgendaSkipsOrdering() {
        RppIsvs i1 = new RppIsvs("isvs-1", "1", "Alfa", List.of());
        RppIsvs i2 = new RppIsvs("isvs-2", "2", "Beta", List.of());
        when(snapshotHolder.get()).thenReturn(snapshot(List.of(), List.of(i1, i2)));

        assertEquals(List.of("1", "2"), codes(service.searchIsvs(null, 10, "")));
        assertEquals(List.of("1", "2"), codes(service.searchIsvs(null, 10, null)));
    }

    @Test
    void numericCodeOrderingPreservedFromSnapshot() {
        when(snapshotHolder.get()).thenReturn(snapshot(
                List.of(new RppAgenda("i1", "9", "n9"),
                        new RppAgenda("i2", "10", "n10"),
                        new RppAgenda("i3", "100", "n100")),
                List.of()));
        assertEquals(List.of("9", "10", "100"), codes(service.searchAgendas(null, 10)));
    }

    @Test
    void dtoMappingProducesIriCodeNazev() {
        when(snapshotHolder.get()).thenReturn(snapshot(
                List.of(new RppAgenda("iri-x", "42", "Název X")),
                List.of()));
        RppSearchResultDto dto = service.searchAgendas(null, 10).get(0);
        assertEquals("iri-x", dto.getIri());
        assertEquals("42", dto.getCode());
        assertEquals("Název X", dto.getNazev());
    }

    @Test
    void preferredAgendaLimitAppliesAfterPartition() {
        RppIsvs i1 = new RppIsvs("isvs-1", "1", "Alfa", List.of("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A9999"));
        RppIsvs i2 = new RppIsvs("isvs-2", "2", "Beta", List.of("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A1382"));
        when(snapshotHolder.get()).thenReturn(snapshot(List.of(), List.of(i1, i2)));

        List<RppSearchResultDto> out = service.searchIsvs(null, 1, "A1382");
        assertEquals(1, out.size());
        assertEquals("2", out.get(0).getCode(), "preferred match should win the single limit slot");
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
