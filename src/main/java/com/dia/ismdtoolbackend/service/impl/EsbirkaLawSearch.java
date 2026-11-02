package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.EsbirkaClient;
import com.dia.ismdtoolbackend.controller.dto.LawDto;
import com.dia.ismdtoolbackend.controller.dto.LawSearchGroupDto;
import com.dia.ismdtoolbackend.controller.dto.LawSearchResultDto;
import com.dia.ismdtoolbackend.models.eli.LawModel;
import com.dia.ismdtoolbackend.models.eli.LawNumberGroupModel;
import com.dia.ismdtoolbackend.models.eli.LawNumberSearchModel;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaLawTitle;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Searches laws in e-Sbírka, grouped by předpis number or matched by act name. */
@Component
@RequiredArgsConstructor
@Slf4j
class EsbirkaLawSearch {

    private final EsbirkaClient client;

    /** Acts shown per group. The rest are reachable by narrowing the query (or by year). */
    static final int MAX_LAWS_PER_GROUP = 60;

    /**
     * Grouped law search: the client returns the acts grouped by číslo with per-group counts;
     * here groups are ordered best first and capped at {@code limit} <em>groups</em>. A query
     * starting with a letter searches act names instead ({@link EsbirkaNameSearchResult}).
     */
    // The key joins the raw values rather than Objects.hash(q, limit), which collides across
    // needle/limit pairs and would serve another search's results. '\u0000' cannot occur in a URL query value,
    // so it is an unambiguous separator.
    @Cacheable(cacheNames = "esbirkaLawSearch",
            key = "'grouped:' + (#q == null ? '' : #q) + '\u0000' + #limit",
            sync = true)
    public LawSearchResultDto searchLawsGrouped(String q, int limit) {
        String needle = q == null ? null : q.trim();
        if (EsbirkaNameSearchResult.isNameQuery(needle)) {
            return EsbirkaNameSearchResult.search(needle, limit, client);
        }
        GroupedNeedle parsed = splitGroupedNeedle(needle);

        LawNumberSearchModel found = client.searchLawsByNumber(parsed.cislo(), parsed.rok(), limit);
        List<LawNumberGroupModel> numberGroups = found.groups();
        if (numberGroups.isEmpty()) {
            return LawSearchResultDto.builder()
                    .query(blankToNull(needle))
                    .ambiguous(false)
                    .totalMatches(0)
                    .truncated(false)
                    .groups(List.of())
                    .build();
        }

        Map<String, List<LawDto>> byCislo = new HashMap<>();
        for (LawModel m : found.laws()) {
            byCislo.computeIfAbsent(m.getCislo() == null ? "" : m.getCislo(),
                    k -> new ArrayList<>()).add(toLawDto(m));
        }

        List<LawSearchGroupDto> groups = new ArrayList<>(numberGroups.size());
        for (LawNumberGroupModel g : numberGroups) {
            List<LawDto> laws = byCislo.get(g.cislo());
            if (laws == null) {
                laws = new ArrayList<>();
            } else {
                // Sorted here, not in SPARQL: re-bucketing by číslo above discards any
                // server-side ordering.
                laws.sort(NEWEST_ROK_FIRST);
                if (laws.size() > MAX_LAWS_PER_GROUP) {
                    laws = new ArrayList<>(laws.subList(0, MAX_LAWS_PER_GROUP));
                }
            }
            groups.add(LawSearchGroupDto.builder()
                    .cislo(g.cislo())
                    // Dataset-wide total from the client, not laws.size().
                    .count(g.pocet())
                    // Compared against the číslo part alone: "49/1997" pins číslo 49 exactly,
                    // and matching the raw needle would make this permanently false.
                    .exactNumberMatch(parsed.cislo() != null && parsed.cislo().equalsIgnoreCase(g.cislo()))
                    .laws(laws)
                    .build());
        }
        groups.sort(BEST_GROUP_FIRST);
        boolean truncated = found.moreGroups() || groups.size() > limit;
        groups = groups.size() > limit ? new ArrayList<>(groups.subList(0, limit)) : groups;
        int totalMatches = groups.stream().mapToInt(LawSearchGroupDto::getCount).sum();

        return LawSearchResultDto.builder()
                .query(blankToNull(needle))
                .ambiguous(isAmbiguous(groups))
                .totalMatches(totalMatches)
                .truncated(truncated)
                .groups(groups)
                .build();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** A grouped-search needle split into its číslo prefix and optional rok prefix. */
    record GroupedNeedle(String cislo, String rok) {}

    /**
     * Split a grouped-search needle on '/' into číslo and rok, tolerating a trailing " Sb." and
     * surrounding whitespace. Both halves are prefixes, and both may be blank.
     *
     * <p>Deliberately tolerant, unlike {@link EsbirkaLawReference#parseNumberYear}: this runs per keystroke, so
     * every intermediate state a user types — "49", "49/", "49/19", "49/1997 Sb." — must return
     * results rather than throw. Anything that is not a číslo/rok shape (letters, a second
     * slash) is passed through as a číslo prefix, where it simply matches nothing.
     */
    static GroupedNeedle splitGroupedNeedle(String needle) {
        if (needle == null || needle.isBlank()) {
            return new GroupedNeedle(null, null);
        }
        String cleaned = needle.trim();
        int sb = cleaned.indexOf(" Sb");
        if (sb > 0) {
            cleaned = cleaned.substring(0, sb).trim();
        }
        int slash = cleaned.indexOf('/');
        if (slash < 0) {
            return new GroupedNeedle(blankToNull(cleaned), null);
        }
        String cislo = cleaned.substring(0, slash).trim();
        String rok = cleaned.substring(slash + 1).trim();
        // A non-numeric year would match no act anyway, but as a prefix filter it would also
        // silently drop the číslo half's results. Ignoring it keeps the číslo results visible.
        if (!rok.isEmpty() && !rok.chars().allMatch(Character::isDigit)) {
            rok = null;
        }
        return new GroupedNeedle(blankToNull(cislo), blankToNull(rok));
    }

    /**
     * Ambiguous = the user still has a choice to make: more than one group, or a single group
     * holding several acts. A single act, or no match, is unambiguous.
     */
    private static boolean isAmbiguous(List<LawSearchGroupDto> groups) {
        if (groups.isEmpty()) {
            return false;
        }
        return groups.size() > 1 || groups.get(0).getCount() > 1;
    }

    /** Newest rok first within a group; null roky sink to the bottom. */
    private static final Comparator<LawDto> NEWEST_ROK_FIRST =
            Comparator.comparing(LawDto::getRok,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(LawDto::getCitace, Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * Exact-number group first, then shortest číslo, then číslo ascending — mirroring how
     * people type ("49" before "490" before "4900"). Group size is not a criterion.
     */
    private static final Comparator<LawSearchGroupDto> BEST_GROUP_FIRST =
            Comparator.comparing(LawSearchGroupDto::isExactNumberMatch, Comparator.reverseOrder())
                    .thenComparing(g -> g.getCislo() == null ? 0 : g.getCislo().length())
                    .thenComparing(LawSearchGroupDto::getCislo,
                            Comparator.nullsLast(Comparator.naturalOrder()));

    static LawDto toLawDto(LawModel m) {
        String eliPath = SparqlIriValidator.extractEsbirkaEliPath(m.getIri());
        return new LawDto(
                m.getIri(),
                eliPath,
                SparqlIriValidator.esbirkaDomain(),
                m.getCitace(),
                EsbirkaLawTitle.displayName(m.getCitace(), m.getNazev()),
                m.getCislo(),
                m.getRok(),
                m.getSbirka()
        );
    }
}
