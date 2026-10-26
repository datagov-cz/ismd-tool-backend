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

import static com.dia.ismdtoolbackend.testsupport.fixtures.EsbirkaFixtures.law;
import static org.assertj.core.api.Assertions.assertThat;
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

    @Mock
    private EsbirkaSparqlClient client;

    private EsbirkaServiceImpl service;

    @org.junit.jupiter.api.BeforeEach
    void buildService() {
        // The two stubbed SPARQL queries below run behind the client's real single entry point.
        org.mockito.Mockito.lenient().when(client.searchLawsByNumber(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenCallRealMethod();
        // Built without Spring, so the search runs without the caching layer.
        service = EsbirkaServiceWiring.build(client, null, null);
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
        assertThat(out.getGroups()).hasSize(2);
        LawSearchGroupDto first = out.getGroups().get(0);
        assertThat(first.getCislo()).as("the exact-number group must lead").isEqualTo("49");
        assertThat(first.isExactNumberMatch()).isTrue();
        assertThat(first.getCount()).as("every act numbered 49 stays in one group").isEqualTo(8);
        // 49/1997 is reachable inside the group even though it is the OLDEST — the flat
        // list dropped it off the page entirely.
        assertThat(first.getLaws().stream().anyMatch(l -> "49/1997 Sb.".equals(l.getCitace()))).isTrue();
    }

    @Test
    void bareNumberIsFlaggedAmbiguousSoTheFeCanAskForAYear() {
        stubGroupedSearch("49", fortyNineRows());
        LawSearchResultDto out = service.searchLawsGrouped("49", 20);

        assertThat(out.isAmbiguous()).as("a bare number leaves the year unresolved").isTrue();
        assertThat(out.getQuery()).isEqualTo("49");
        assertThat(out.getTotalMatches()).as("eight acts numbered 49, plus 490/2001").isEqualTo(9);
    }

    @Test
    void fullyQualifiedQueryIsNotAmbiguous() {
        // "49/1997" pins exactly one act — nothing left to disambiguate. The needle is split,
        // so the číslo half reaches step 1 and the year half narrows it to one act, out of
        // the eight years numbered 49 in the fixture.
        stubGroupedSearch("49", "1997", fortyNineRows());

        LawSearchResultDto out = service.searchLawsGrouped("49/1997", 20);

        assertThat(out.isAmbiguous()).as("a single matching act needs no further choice").isFalse();
        assertThat(out.getGroups()).hasSize(1);
        assertThat(out.getGroups().get(0).getCount()).isEqualTo(1);
        assertThat(out.getGroups().get(0).getLaws().get(0).getCitace()).isEqualTo("49/1997 Sb.");
    }

    @Test
    void qualifiedQueryReturnsGroupsInsteadOfBlanking() {
        // The reported bug: the needle went to step 1 whole, and "49/1997" prefix-matched no
        // číslo (which holds "49", never a slash), so the FE's result list emptied the moment
        // the user typed the slash — HTTP 200, groups: [], indistinguishable from "not found".
        stubGroupedSearch("49", "1997", fortyNineRows());

        LawSearchResultDto out = service.searchLawsGrouped("49/1997", 20);

        assertThat(out.getGroups()).as("a qualified needle must not blank the list").isNotEmpty();
        assertThat(out.getTotalMatches()).isEqualTo(1);
        assertThat(out.getQuery()).as("the echoed query stays as the user typed it").isEqualTo("49/1997");
    }

    @Test
    void exactNumberMatchIsTrueForAQualifiedNeedle() {
        // Compared against the číslo half, not the raw needle — against the raw needle it was
        // permanently false for anything containing '/', so the FE lost its exactness signal.
        stubGroupedSearch("49", "1997", fortyNineRows());

        assertThat(service.searchLawsGrouped("49/1997", 20).getGroups().get(0).isExactNumberMatch()).isTrue();
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
                eq(EsbirkaSparqlClient.GROUPED_ROW_LIMIT));
    }

    @Test
    void partialYearKeepsResultsVisibleWhileTyping() {
        // The FE searches per keystroke. "49/", "49/1", "49/19" are all states a user passes
        // through; each must narrow rather than blank, or the list flickers empty mid-typing.
        stubGroupedSearch("49", null, fortyNineRows());
        assertThat(service.searchLawsGrouped("49/", 20).getGroups())
                .as("a trailing slash carries no year — behave like the bare number")
                .isNotEmpty();

        stubGroupedSearch("49", "202", fortyNineRows());
        LawSearchResultDto out = service.searchLawsGrouped("49/202", 20);
        assertThat(out.getTotalMatches())
                .as("the seven 49/202x acts — 49/1997 and 490/2001 both fall outside the prefix")
                .isEqualTo(7);
    }

    @Test
    void trailingSbSuffixIsStrippedFromAQualifiedNeedle() {
        stubGroupedSearch("49", "1997", fortyNineRows());
        assertThat(service.searchLawsGrouped("  49/1997 Sb.  ", 20).getTotalMatches()).isEqualTo(1);
    }

    @Test
    void nonNumericYearIsIgnoredRatherThanBlankingTheCisloResults() {
        // "49/abc" cannot match a year; applying it as a filter would return nothing. Dropping
        // it keeps the číslo half's results on screen.
        stubGroupedSearch("49", null, fortyNineRows());

        assertThat(service.searchLawsGrouped("49/abc", 20).getGroups()).isNotEmpty();
    }

    @Test
    void needleSplitCoversEveryShapeTheFeCanSend() {
        assertThat(EsbirkaLawSearch.splitGroupedNeedle("49/1997"))
                .isEqualTo(new EsbirkaLawSearch.GroupedNeedle("49", "1997"));
        assertThat(EsbirkaLawSearch.splitGroupedNeedle("49")).isEqualTo(new EsbirkaLawSearch.GroupedNeedle("49", null));
        assertThat(EsbirkaLawSearch.splitGroupedNeedle("49/"))
                .isEqualTo(new EsbirkaLawSearch.GroupedNeedle("49", null));
        assertThat(EsbirkaLawSearch.splitGroupedNeedle("49/19"))
                .isEqualTo(new EsbirkaLawSearch.GroupedNeedle("49", "19"));
        assertThat(EsbirkaLawSearch.splitGroupedNeedle(" 49/1997 Sb. "))
                .isEqualTo(new EsbirkaLawSearch.GroupedNeedle("49", "1997"));
        assertThat(EsbirkaLawSearch.splitGroupedNeedle("  ")).isEqualTo(new EsbirkaLawSearch.GroupedNeedle(null, null));
        assertThat(EsbirkaLawSearch.splitGroupedNeedle(null)).isEqualTo(new EsbirkaLawSearch.GroupedNeedle(null, null));
        // A leading slash leaves no číslo, so nothing is pinned but the year still filters.
        assertThat(EsbirkaLawSearch.splitGroupedNeedle("/1997"))
                .isEqualTo(new EsbirkaLawSearch.GroupedNeedle(null, "1997"));
    }

    @Test
    void noMatchesIsNotAmbiguous() {
        // Nothing to choose between — the FE should say "not found", not "pick a year".
        when(client.searchLawNumberGroups(eq("999999"), org.mockito.ArgumentMatchers.any(), anyInt())).thenReturn(List.of());
        LawSearchResultDto out = service.searchLawsGrouped("999999", 20);
        // No numbers matched, so the act fetch must not even be attempted.
        org.mockito.Mockito.verify(client, org.mockito.Mockito.never())
                .fetchLawsByNumbers(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), anyInt());

        assertThat(out.isAmbiguous()).isFalse();
        assertThat(out.getGroups()).isEmpty();
        assertThat(out.getTotalMatches()).isEqualTo(0);
    }

    @Test
    void withinAGroupNewestYearComesFirst() {
        stubGroupedSearch("49", fortyNineRows());
        List<com.dia.ismdtoolbackend.controller.dto.LawDto> laws =
                service.searchLawsGrouped("49", 20).getGroups().get(0).getLaws();

        assertThat(laws.get(0).getRok()).isEqualTo(2026);
        assertThat(laws.get(laws.size() - 1).getRok()).isEqualTo(1997);
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
        assertThat(out.getGroups().get(0).getCislo()).isEqualTo("49");
        assertThat(out.getGroups().get(0).isExactNumberMatch()).isTrue();
        assertThat(out.getGroups().get(1).getCislo()).isEqualTo("490");
        assertThat(out.getGroups().get(1).isExactNumberMatch()).isFalse();
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

        assertThat(out.getGroups()).as("limit bounds groups").hasSize(1);
        assertThat(out.getGroups().get(0).getCount()).isEqualTo(8);
        assertThat(out.getGroups().get(0).getLaws()).as("the group keeps every act").hasSize(8);
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
        assertThat(cap.getValue()).as("act fetch is scoped to the chosen čísla").contains("49");
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
        assertThat(out.getGroups().get(0).getCount()).as("count is dataset-wide").isEqualTo(120);
        assertThat(out.getGroups().get(0).getLaws()).as("laws hold what was fetched").hasSize(2);
        assertThat(out.getTotalMatches()).isEqualTo(120);
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

        assertThat(service.searchLawsGrouped("4", 20).isTruncated()).isTrue();
    }

    @Test
    void normalResultIsNotFlaggedTruncated() {
        stubGroupedSearch("49", fortyNineRows());
        assertThat(service.searchLawsGrouped("49", 20).isTruncated()).isFalse();
    }

    @Test
    void blankQueryReportsNullNeedleAndNoExactMatch() {
        stubGroupedSearch(null, List.of(law("49", 2026), law("1", 2049)));

        LawSearchResultDto out = service.searchLawsGrouped(null, 20);
        assertThat(out.getQuery()).isNull();
        assertThat(out.getGroups().stream().noneMatch(LawSearchGroupDto::isExactNumberMatch))
                .as("no needle means no group can be an exact match")
                .isTrue();
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
        assertThat(out.getGroups()).hasSize(1);
        assertThat(out.isAmbiguous()).as("one číslo spanning many years still needs a year choice").isTrue();
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
        assertThat(out.getGroups().stream().map(LawSearchGroupDto::getCislo).toList())
                .as("shortest číslo first — NOT largest group first")
                .isEqualTo(List.of("49", "499", "4999"));
    }

    @Test
    void groupDisplayIsCappedButCountStaysDatasetWide() {
        // A group larger than the display cap must be truncated in `laws` while `count`
        // still reports the true total, or the FE shows a false number.
        List<LawModel> many = new ArrayList<>();
        for (int i = 0; i < EsbirkaLawSearch.MAX_LAWS_PER_GROUP + 25; i++) {
            many.add(law("49", 1900 + i));
        }
        when(client.searchLawNumberGroups(eq("49"), org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenReturn(List.of(new LawNumberGroupModel("49", 120)));
        when(client.fetchLawsByNumbers(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenReturn(many);

        LawSearchGroupDto g = service.searchLawsGrouped("49", 20).getGroups().get(0);
        assertThat(g.getLaws()).as("display capped").hasSize(EsbirkaLawSearch.MAX_LAWS_PER_GROUP);
        assertThat(g.getCount()).as("count stays dataset-wide").isEqualTo(120);
        // The cap must keep the NEWEST acts, not an arbitrary slice.
        assertThat(g.getLaws().get(0).getRok()).isEqualTo(1900 + EsbirkaLawSearch.MAX_LAWS_PER_GROUP + 24);
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
        assertThat(cisla.getValue().stream().sorted().toList())
                .as("exactly the čísla step 1 returned")
                .isEqualTo(List.of("49", "490"));
        assertThat(rowLimit.getValue())
                .as("act fetch must be row-capped — group size is unbounded")
                .isEqualTo(EsbirkaSparqlClient.GROUPED_ROW_LIMIT);
    }
}
