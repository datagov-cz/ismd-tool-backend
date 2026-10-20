package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.EsbirkaClient;
import com.dia.ismdtoolbackend.controller.dto.LawSearchGroupDto;
import com.dia.ismdtoolbackend.controller.dto.LawSearchResultDto;
import com.dia.ismdtoolbackend.models.eli.LawModel;

import java.util.List;

/**
 * The name mode of grouped law search: a query starting with a letter ("civilním letectví") is
 * matched against act names, one group per act in rank order. A query starting with a digit stays
 * in number mode, so "49/1997 Sb." and "49 letectví" never reach here.
 */
final class EsbirkaNameSearchResult {

    /** Shorter queries match almost everything and rank poorly; each name query costs 2–4 upstream calls. */
    static final int MIN_LETTERS = 3;

    private EsbirkaNameSearchResult() {
    }

    static boolean isNameQuery(String needle) {
        return needle != null && !needle.isEmpty() && Character.isLetter(needle.charAt(0));
    }

    /** {@code truncated} is set when the result fills {@code limit}: more matches may exist. */
    static LawSearchResultDto search(String needle, int limit, EsbirkaClient client) {
        long letters = needle.chars().filter(Character::isLetter).count();
        List<LawModel> laws = letters < MIN_LETTERS ? List.of() : client.searchLawsByName(needle, limit);
        List<LawSearchGroupDto> groups = laws.stream()
                .map(law -> LawSearchGroupDto.builder()
                        .cislo(law.getCislo())
                        .count(1)
                        .exactNumberMatch(false)
                        .laws(List.of(EsbirkaLawSearch.toLawDto(law)))
                        .build())
                .toList();
        return LawSearchResultDto.builder()
                .query(needle)
                .ambiguous(groups.size() > 1)
                .totalMatches(groups.size())
                .truncated(laws.size() >= limit)
                .groups(groups)
                .build();
    }
}