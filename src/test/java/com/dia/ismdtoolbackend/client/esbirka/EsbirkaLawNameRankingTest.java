package com.dia.ismdtoolbackend.client.esbirka;

import com.dia.ismdtoolbackend.models.eli.LawModel;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaLawTitle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Name-search ranking on responses recorded from the live API (2026-10-19): suggester and title
 * search for each query, plus the act-type-stripped query where {@link EsbirkaLawNameRanking#queries}
 * sends one. {@code queries.json} maps each query to the law it must rank first, or null for the
 * four queries whose words are not in the title in that form (an upstream limit).
 */
class EsbirkaLawNameRankingTest {

    private static final String B = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz";

    static Stream<Arguments> expectedFirst() {
        return queries().entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(e -> Arguments.of(e.getKey(), e.getValue()));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("expectedFirst")
    void ranksTheIntendedLawFirst(String q, String expectedLaw) {
        List<LawModel> ranked = rank(q);

        assertEquals(B + expectedLaw, ranked.get(0).getIri(),
                () -> "top 5 for " + q + ": " + ranked.stream().limit(5)
                        .map(l -> l.getCitace() + " " + l.getNazev()).toList());
    }

    @Test
    void accentlessZakonOFindsTheLawOnlyThroughTheStrippedQuery() {
        assertEquals(List.of("zakon o civilnim letectvi", "o civilnim letectvi"),
                EsbirkaLawNameRanking.queries("zakon o civilnim letectvi"));
        assertEquals(List.of("zákoník práce"), EsbirkaLawNameRanking.queries("zákoník práce"),
                "only the 'zákon o …' form is stripped");
        assertEquals(List.of("civilním letectví"), EsbirkaLawNameRanking.queries("civilním letectví"));
    }

    @Test
    void derivedActsRankBelowTheLawTheyDeriveFrom() {
        List<LawModel> ranked = rank("civilním letectví");

        // 370/2025 amends 49/1997, 108/1997 implements it; both name it in their titles.
        int law = indexOf(ranked, "/sb/1997/49");
        assertTrue(law < indexOf(ranked, "/sb/2025/370"));
        assertTrue(law < indexOf(ranked, "/sb/1997/108"));
    }

    @Test
    void namingTheKindOfDerivedActRanksThatKindFirst() {
        var ruling = new EsbirkaRestDtos.SearchRow("/sb/2019/303", "303/2019 Sb.",
                "Nález Ústavního soudu ze dne 1. října 2019 ve věci daní z příjmů", "VYHLASENY_BEZ_UCINNOSTI");
        var law = new EsbirkaRestDtos.SearchRow("/sb/1992/586", "586/1992 Sb.",
                "Zákon České národní rady o daních z příjmů", "AKTUALNE_PLATNY");
        var rows = List.of(new EsbirkaRestDtos.SearchResult(2, List.of(law, ruling)));

        assertEquals(B + "/sb/1992/586",
                EsbirkaLawNameRanking.rank("daní z příjmů", List.of(), rows, 5).get(0).getIri());
        assertEquals(B + "/sb/2019/303",
                EsbirkaLawNameRanking.rank("nález daní z příjmů", List.of(), rows, 5).get(0).getIri(),
                "the user asked for a ruling");
    }

    @Test
    void tiesGoToSbThenTheNewerActThenTheShorterTitle() {
        var older = row("/sb/2001/1", "Zákon o letectví");
        var newer = row("/sb/2010/1", "Zákon o letectví");
        var treaty = row("/sm/2015/1", "Zákon o letectví");
        var longer = row("/sb/2010/2", "Zákon o letectví a o změně dalších zákonů");
        var rows = List.of(new EsbirkaRestDtos.SearchResult(4, List.of(treaty, longer, older, newer)));

        List<String> ranked = EsbirkaLawNameRanking.rank("letectví", List.of(), rows, 5).stream()
                .map(LawModel::getIri).toList();

        assertEquals(List.of(B + "/sb/2010/1", B + "/sb/2010/2", B + "/sb/2001/1", B + "/sm/2015/1"), ranked);
    }

    private static EsbirkaRestDtos.SearchRow row(String staleUrl, String nazev) {
        String[] s = staleUrl.split("/");
        return new EsbirkaRestDtos.SearchRow(staleUrl, s[3] + "/" + s[2] + " Sb.", nazev, "AKTUALNE_PLATNY");
    }

    @Test
    void repealedNamesakeRanksBelowTheLawInForce() {
        List<LawModel> ranked = rank("zákoník práce");

        assertTrue(indexOf(ranked, "/sb/2006/262") < indexOf(ranked, "/sb/1965/65"),
                "65/1965 \"Zákoník práce\" is repealed");
    }

    @Test
    void wordsNotInTheTitleFindNothingUsable() {
        // Upstream matches whole inflected title words: "svobodný přístup" is not "svobodném přístupu".
        assertTrue(rank("svobodný přístup k informacím").isEmpty());
        assertTrue(rank("o registru práv a povinností").isEmpty());
    }

    @Test
    void rankedLawsCarryTheirTitleAndLawLevelIri() {
        LawModel top = rank("o daních z příjmů").get(0);

        assertEquals(B + "/sb/1992/586", top.getIri());
        assertEquals("586/1992 Sb.", top.getCitace());
        assertEquals("Zákon České národní rady o daních z příjmů", top.getNazev());
        assertEquals("586", top.getCislo());
        assertEquals(1992, top.getRok());
        assertEquals("sb", top.getSbirka());
    }

    private static List<LawModel> rank(String q) {
        List<EsbirkaRestDtos.Suggestions> suggestions = new ArrayList<>();
        List<EsbirkaRestDtos.SearchResult> titles = new ArrayList<>();
        for (String sent : EsbirkaLawNameRanking.queries(q)) {
            String slug = String.join("-", EsbirkaLawTitle.fold(sent).split(" "));
            suggestions.add(read("name-search/" + slug + ".nabidka.json", new TypeReference<>() { }));
            titles.add(read("name-search/" + slug + ".nazev.json", new TypeReference<>() { }));
        }
        return EsbirkaLawNameRanking.rank(q, suggestions, titles, 50);
    }

    private static int indexOf(List<LawModel> ranked, String law) {
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).getIri().equals(B + law)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static Map<String, String> queries() {
        return read("name-search/queries.json", new TypeReference<LinkedHashMap<String, String>>() { });
    }

    private static <T> T read(String path, TypeReference<T> type) {
        return EsbirkaRestFixtures.JSON.readValue(EsbirkaRestFixtures.text(path), type);
    }
}