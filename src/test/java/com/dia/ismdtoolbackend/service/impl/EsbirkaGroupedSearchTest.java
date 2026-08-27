package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.EsbirkaSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.LawSearchGroupDto;
import com.dia.ismdtoolbackend.controller.dto.LawSearchResultDto;
import com.dia.ismdtoolbackend.models.eli.LawModel;
import com.dia.ismdtoolbackend.models.eli.LawNumberGroupModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Grouped law search. Czech acts renumber from 1 every year, so "49" matches dozens of
 * unrelated laws that are NOT versions of one another, and a flat list ordered by rok desc
 * pushes the wanted law off the page.
 */
@ExtendWith(MockitoExtension.class)
class EsbirkaGroupedSearchTest {

    private static final String BASE = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/";

    @Mock
    private EsbirkaSparqlClient client;

    private EsbirkaServiceImpl service;

    @org.junit.jupiter.api.BeforeEach
    void buildService() {
        // In production `self` is the Spring proxy that adds @Cacheable; here it is a plain
        // self-reference, so the one-arg delegation is exercised without the caching layer.
        service = new EsbirkaServiceImpl(client, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    private static LawModel law(String cislo, int rok) {
        return new LawModel(BASE + rok + "/" + cislo, cislo + "/" + rok + " Sb.", cislo, rok, "sb");
    }

    /**
     * Mirrors the reported Postman response: many years of číslo 49, plus noise.
     * Deliberately NOT in year order — a pre-sorted fixture makes the ordering assertions
     * vacuous (they pass even with the sort deleted).
     *
     * <p>{@code 1/2049} is the year-only noise the flat search matches; grouped search
     * prefix-matches the číslo, so it is correctly absent from every {@code q=49} result here.
     */
    private static List<LawModel> fortyNineRows() {
        List<LawModel> rows = new ArrayList<>();
        for (int rok : new int[]{1997, 2022, 2026, 2020, 2024, 2021, 2025, 2023}) {
            rows.add(law("49", rok));
        }
        rows.add(law("490", 2001));
        rows.add(law("1", 2049));
        return rows;
    }


    /**
     * Stub the two-step client: aggregate groups derived from the given rows, then the rows.
     *
     * <p>Both stubs apply the filters the real SPARQL applies — číslo prefix AND rok prefix,
     * matched on the exact arguments. A stub ignoring them would pass even if the service
     * dropped the year half of the needle, which is the behaviour under test here.
     */
    private void stubGroupedSearch(String cisloPrefix, String rokPrefix, List<LawModel> rows) {
        List<LawModel> matching = rows.stream()
                .filter(m -> cisloPrefix == null || m.getCislo().startsWith(cisloPrefix))
                .filter(m -> rokPrefix == null || String.valueOf(m.getRok()).startsWith(rokPrefix))
                .toList();

        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (LawModel m : matching) {
            counts.merge(m.getCislo(), 1, Integer::sum);
        }
        List<LawNumberGroupModel> groups = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(java.util.Comparator
                        .comparingInt((java.util.Map.Entry<String, Integer> e) -> e.getKey().length())
                        .thenComparing(java.util.Map.Entry::getKey))
                .forEach(e -> groups.add(new LawNumberGroupModel(e.getKey(), e.getValue())));

        when(client.searchLawNumberGroups(eq(cisloPrefix), eq(rokPrefix), anyInt()))
                .thenReturn(groups);
        when(client.fetchLawsByNumbers(org.mockito.ArgumentMatchers.anyList(),
                eq(rokPrefix), anyInt()))
                .thenAnswer(inv -> {
                    List<String> wanted = inv.getArgument(0);
                    return matching.stream().filter(m -> wanted.contains(m.getCislo())).toList();
                });
    }

    /** Bare-number needle — no year half. */
    private void stubGroupedSearch(String q, List<LawModel> rows) {
        stubGroupedSearch(q, null, rows);
    }

    @Test
    void bareNumberCollapsesEightyRowsIntoOneChoice() {
        stubGroupedSearch("49", fortyNineRows());

        LawSearchResultDto out = service.searchLawsGrouped("49", 20);

        // The flat list was 10 rows of near-identical noise; grouped it is 2 decisions
        // (49 and 490 — 1/2049 is year-only noise that a číslo prefix never matches).
        assertEquals(2, out.getGroups().size());
        LawSearchGroupDto first = out.getGroups().get(0);
        assertEquals("49", first.getCislo(), "the exact-number group must lead");
        assertTrue(first.isExactNumberMatch());
        assertEquals(8, first.getCount(), "every act numbered 49 stays in one group");
        // 49/1997 is reachable inside the group even though it is the OLDEST — the flat
        // list dropped it off the page entirely.
        assertTrue(first.getLaws().stream().anyMatch(l -> "49/1997 Sb.".equals(l.getCitace())));
    }

    @Test
    void bareNumberIsFlaggedAmbiguousSoTheFeCanAskForAYear() {
        stubGroupedSearch("49", fortyNineRows());
        LawSearchResultDto out = service.searchLawsGrouped("49", 20);

        assertTrue(out.isAmbiguous(), "a bare number leaves the year unresolved");
        assertEquals("49", out.getQuery());
        assertEquals(9, out.getTotalMatches(), "eight acts numbered 49, plus 490/2001");
    }

    @Test
    void fullyQualifiedQueryIsNotAmbiguous() {
        // "49/1997" pins exactly one act — nothing left to disambiguate. The needle is split,
        // so the číslo half reaches step 1 and the year half narrows it to one act, out of
        // the eight years numbered 49 in the fixture.
        stubGroupedSearch("49", "1997", fortyNineRows());

        LawSearchResultDto out = service.searchLawsGrouped("49/1997", 20);

        assertFalse(out.isAmbiguous(), "a single matching act needs no further choice");
        assertEquals(1, out.getGroups().size());
        assertEquals(1, out.getGroups().get(0).getCount());
        assertEquals("49/1997 Sb.", out.getGroups().get(0).getLaws().get(0).getCitace());
    }

    @Test
    void qualifiedQueryReturnsGroupsInsteadOfBlanking() {
        // The reported bug: the needle went to step 1 whole, and "49/1997" prefix-matched no
        // číslo (which holds "49", never a slash), so the FE's result list emptied the moment
        // the user typed the slash — HTTP 200, groups: [], indistinguishable from "not found".
        stubGroupedSearch("49", "1997", fortyNineRows());

        LawSearchResultDto out = service.searchLawsGrouped("49/1997", 20);

        assertFalse(out.getGroups().isEmpty(), "a qualified needle must not blank the list");
        assertEquals(1, out.getTotalMatches());
        assertEquals("49/1997", out.getQuery(), "the echoed query stays as the user typed it");
    }

    @Test
    void exactNumberMatchIsTrueForAQualifiedNeedle() {
        // Compared against the číslo half, not the raw needle — against the raw needle it was
        // permanently false for anything containing '/', so the FE lost its exactness signal.
        stubGroupedSearch("49", "1997", fortyNineRows());

        assertTrue(service.searchLawsGrouped("49/1997", 20).getGroups().get(0).isExactNumberMatch());
    }

    @Test
    void yearNarrowingReachesBothQuerySteps() {
        // Step 1's counts are year-scoped; if step 2 kept fetching every act of that číslo,
        // the group would display 8 acts while claiming a count of 1.
        stubGroupedSearch("49", "1997", fortyNineRows());

        service.searchLawsGrouped("49/1997", 20);

        org.mockito.Mockito.verify(client).searchLawNumberGroups("49", "1997", 20);
        org.mockito.Mockito.verify(client).fetchLawsByNumbers(
                org.mockito.ArgumentMatchers.anyList(), eq("1997"),
                eq(EsbirkaServiceImpl.GROUPED_ROW_LIMIT));
    }

    @Test
    void partialYearKeepsResultsVisibleWhileTyping() {
        // The FE searches per keystroke. "49/", "49/1", "49/19" are all states a user passes
        // through; each must narrow rather than blank, or the list flickers empty mid-typing.
        stubGroupedSearch("49", null, fortyNineRows());
        assertFalse(service.searchLawsGrouped("49/", 20).getGroups().isEmpty(),
                "a trailing slash carries no year — behave like the bare number");

        stubGroupedSearch("49", "202", fortyNineRows());
        LawSearchResultDto out = service.searchLawsGrouped("49/202", 20);
        assertEquals(7, out.getTotalMatches(),
                "the seven 49/202x acts — 49/1997 and 490/2001 both fall outside the prefix");
    }

    @Test
    void trailingSbSuffixIsStrippedFromAQualifiedNeedle() {
        stubGroupedSearch("49", "1997", fortyNineRows());
        assertEquals(1, service.searchLawsGrouped("  49/1997 Sb.  ", 20).getTotalMatches());
    }

    @Test
    void nonNumericYearIsIgnoredRatherThanBlankingTheCisloResults() {
        // "49/abc" cannot match a year; applying it as a filter would return nothing. Dropping
        // it keeps the číslo half's results on screen.
        stubGroupedSearch("49", null, fortyNineRows());

        assertFalse(service.searchLawsGrouped("49/abc", 20).getGroups().isEmpty());
    }

    @Test
    void needleSplitCoversEveryShapeTheFeCanSend() {
        assertEquals(new EsbirkaServiceImpl.GroupedNeedle("49", "1997"),
                EsbirkaServiceImpl.splitGroupedNeedle("49/1997"));
        assertEquals(new EsbirkaServiceImpl.GroupedNeedle("49", null),
                EsbirkaServiceImpl.splitGroupedNeedle("49"));
        assertEquals(new EsbirkaServiceImpl.GroupedNeedle("49", null),
                EsbirkaServiceImpl.splitGroupedNeedle("49/"));
        assertEquals(new EsbirkaServiceImpl.GroupedNeedle("49", "19"),
                EsbirkaServiceImpl.splitGroupedNeedle("49/19"));
        assertEquals(new EsbirkaServiceImpl.GroupedNeedle("49", "1997"),
                EsbirkaServiceImpl.splitGroupedNeedle(" 49/1997 Sb. "));
        assertEquals(new EsbirkaServiceImpl.GroupedNeedle(null, null),
                EsbirkaServiceImpl.splitGroupedNeedle("  "));
        assertEquals(new EsbirkaServiceImpl.GroupedNeedle(null, null),
                EsbirkaServiceImpl.splitGroupedNeedle(null));
        // A leading slash leaves no číslo, so nothing is pinned but the year still filters.
        assertEquals(new EsbirkaServiceImpl.GroupedNeedle(null, "1997"),
                EsbirkaServiceImpl.splitGroupedNeedle("/1997"));
    }

    @Test
    void noMatchesIsNotAmbiguous() {
        // Nothing to choose between — the FE should say "not found", not "pick a year".
        when(client.searchLawNumberGroups(eq("zzz"), org.mockito.ArgumentMatchers.any(), anyInt())).thenReturn(List.of());
        LawSearchResultDto out = service.searchLawsGrouped("zzz", 20);
        // No numbers matched, so the act fetch must not even be attempted.
        org.mockito.Mockito.verify(client, org.mockito.Mockito.never())
                .fetchLawsByNumbers(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), anyInt());

        assertFalse(out.isAmbiguous());
        assertTrue(out.getGroups().isEmpty());
        assertEquals(0, out.getTotalMatches());
    }

    @Test
    void withinAGroupNewestYearComesFirst() {
        stubGroupedSearch("49", fortyNineRows());
        List<com.dia.ismdtoolbackend.controller.dto.LawDto> laws =
                service.searchLawsGrouped("49", 20).getGroups().get(0).getLaws();

        assertEquals(2026, laws.get(0).getRok());
        assertEquals(1997, laws.get(laws.size() - 1).getRok());
    }

    @Test
    void exactNumberGroupBeatsALargerPrefixGroup() {
        // "49" must lead even when a prefix-matching číslo has MORE acts — exactness
        // outranks size, otherwise 490 would hijack a search for 49.
        List<LawModel> rows = new ArrayList<>();
        rows.add(law("49", 1997));
        for (int rok = 2000; rok < 2006; rok++) {
            rows.add(law("490", rok));
        }
        stubGroupedSearch("49", rows);

        LawSearchResultDto out = service.searchLawsGrouped("49", 20);
        assertEquals("49", out.getGroups().get(0).getCislo());
        assertTrue(out.getGroups().get(0).isExactNumberMatch());
        assertEquals("490", out.getGroups().get(1).getCislo());
        assertFalse(out.getGroups().get(1).isExactNumberMatch());
    }

    @Test
    void limitCapsGroupsNotRows() {
        // SPARQL applies the group cap, so only the capped groups come back — but each
        // returned group still carries ALL its acts. Capping must never cut inside a group,
        // or the wanted year disappears again.
        when(client.searchLawNumberGroups(eq("49"), org.mockito.ArgumentMatchers.any(), eq(1)))
                .thenReturn(List.of(new LawNumberGroupModel("49", 8)));
        when(client.fetchLawsByNumbers(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenReturn(fortyNineRows().stream()
                        .filter(m -> "49".equals(m.getCislo())).toList());

        LawSearchResultDto out = service.searchLawsGrouped("49", 1);

        assertEquals(1, out.getGroups().size(), "limit bounds groups");
        assertEquals(8, out.getGroups().get(0).getCount());
        assertEquals(8, out.getGroups().get(0).getLaws().size(), "the group keeps every act");
    }

    @Test
    void limitIsAppliedToTheAggregateNotToRows() {
        // The group cap goes to SPARQL's GROUP BY. A row cap cannot work here: "1"
        // prefix-matches 12 037 acts, so any row limit would slice groups in half and
        // recreate the bug. The act fetch is then scoped to the chosen numbers only.
        stubGroupedSearch("49", fortyNineRows());

        service.searchLawsGrouped("49", 5);

        org.mockito.Mockito.verify(client).searchLawNumberGroups("49", null, 5);
        org.mockito.ArgumentCaptor<List<String>> cap =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(client).fetchLawsByNumbers(cap.capture(), org.mockito.ArgumentMatchers.any(), anyInt());
        assertTrue(cap.getValue().contains("49"), "act fetch is scoped to the chosen čísla");
    }

    @Test
    void groupCountComesFromTheAggregateNotTheFetchedRows() {
        // The aggregate knows the dataset-wide total (120 acts numbered 49); the display
        // fetch may legitimately return fewer. The count must report the former.
        when(client.searchLawNumberGroups(eq("49"), org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenReturn(List.of(new LawNumberGroupModel("49", 120)));
        when(client.fetchLawsByNumbers(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenReturn(List.of(law("49", 1997), law("49", 2026)));

        LawSearchResultDto out = service.searchLawsGrouped("49", 20);
        assertEquals(120, out.getGroups().get(0).getCount(), "count is dataset-wide");
        assertEquals(2, out.getGroups().get(0).getLaws().size(), "laws hold what was fetched");
        assertEquals(120, out.getTotalMatches());
    }

    @Test
    void fillingTheGroupCapIsReportedAsTruncated() {
        // As many groups as the cap allows means more numbers matched than were returned —
        // the FE must say "refine your search" rather than imply completeness.
        List<LawNumberGroupModel> full = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            full.add(new LawNumberGroupModel("4" + i, 5));
        }
        when(client.searchLawNumberGroups(eq("4"), org.mockito.ArgumentMatchers.any(), anyInt())).thenReturn(full);
        when(client.fetchLawsByNumbers(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenReturn(List.of());

        assertTrue(service.searchLawsGrouped("4", 20).isTruncated());
    }

    @Test
    void normalResultIsNotFlaggedTruncated() {
        stubGroupedSearch("49", fortyNineRows());
        assertFalse(service.searchLawsGrouped("49", 20).isTruncated());
    }

    @Test
    void blankQueryReportsNullNeedleAndNoExactMatch() {
        stubGroupedSearch(null, List.of(law("49", 2026), law("1", 2049)));

        LawSearchResultDto out = service.searchLawsGrouped(null, 20);
        assertEquals(null, out.getQuery());
        assertTrue(out.getGroups().stream().noneMatch(LawSearchGroupDto::isExactNumberMatch),
                "no needle means no group can be an exact match");
    }

    @Test
    void singleGroupWithManyYearsIsStillAmbiguous() {
        // The PRIMARY real case: "49" resolves to one číslo but 120 acts across years, so the
        // user must still pick a year. Only the multi-group branch was covered before.
        when(client.searchLawNumberGroups(eq("49"), org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenReturn(List.of(new LawNumberGroupModel("49", 8)));
        when(client.fetchLawsByNumbers(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenReturn(fortyNineRows().stream()
                        .filter(m -> "49".equals(m.getCislo())).toList());

        LawSearchResultDto out = service.searchLawsGrouped("49", 20);
        assertEquals(1, out.getGroups().size());
        assertTrue(out.isAmbiguous(),
                "one číslo spanning many years still needs a year choice");
    }

    @Test
    void groupsAreOrderedShortestCisloFirstAmongNonExactMatches() {
        // Guards the číslo-length tiebreak in BEST_GROUP_FIRST, which no assertion reached.
        when(client.searchLawNumberGroups(eq("4"), org.mockito.ArgumentMatchers.any(), anyInt())).thenReturn(List.of(
                new LawNumberGroupModel("4999", 2),
                new LawNumberGroupModel("49", 5),
                new LawNumberGroupModel("499", 3)));
        when(client.fetchLawsByNumbers(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenReturn(List.of());

        LawSearchResultDto out = service.searchLawsGrouped("4", 20);
        assertEquals(List.of("49", "499", "4999"),
                out.getGroups().stream().map(LawSearchGroupDto::getCislo).toList(),
                "shortest číslo first — NOT largest group first");
    }

    @Test
    void groupDisplayIsCappedButCountStaysDatasetWide() {
        // A group larger than the display cap must be truncated in `laws` while `count`
        // still reports the true total, or the FE shows a false number.
        List<LawModel> many = new ArrayList<>();
        for (int i = 0; i < EsbirkaServiceImpl.MAX_LAWS_PER_GROUP + 25; i++) {
            many.add(law("49", 1900 + i));
        }
        when(client.searchLawNumberGroups(eq("49"), org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenReturn(List.of(new LawNumberGroupModel("49", 120)));
        when(client.fetchLawsByNumbers(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenReturn(many);

        LawSearchGroupDto g = service.searchLawsGrouped("49", 20).getGroups().get(0);
        assertEquals(EsbirkaServiceImpl.MAX_LAWS_PER_GROUP, g.getLaws().size(), "display capped");
        assertEquals(120, g.getCount(), "count stays dataset-wide");
        // The cap must keep the NEWEST acts, not an arbitrary slice.
        assertEquals(1900 + EsbirkaServiceImpl.MAX_LAWS_PER_GROUP + 24, g.getLaws().get(0).getRok());
    }

    @Test
    void actFetchIsScopedToTheChosenCislaAndRowCapped() {
        // The real query filters by číslo; a stub returning everything would hide a bug where
        // the service passes the wrong list. Assert what was actually requested.
        stubGroupedSearch("49", fortyNineRows());

        service.searchLawsGrouped("49", 20);

        org.mockito.ArgumentCaptor<List<String>> cisla =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.ArgumentCaptor<Integer> rowLimit =
                org.mockito.ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(client).fetchLawsByNumbers(cisla.capture(), org.mockito.ArgumentMatchers.any(), rowLimit.capture());
        assertEquals(List.of("49", "490"), cisla.getValue().stream().sorted().toList(),
                "exactly the čísla step 1 returned");
        assertEquals(EsbirkaServiceImpl.GROUPED_ROW_LIMIT, rowLimit.getValue(),
                "act fetch must be row-capped — group size is unbounded");
    }
}
