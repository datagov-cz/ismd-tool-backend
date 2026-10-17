package com.dia.ismdtoolbackend.client.esbirka;

import com.dia.ismdtoolbackend.client.esbirka.EsbirkaRestDtos.SearchCondition;
import com.dia.ismdtoolbackend.client.esbirka.EsbirkaRestDtos.SearchRequest;
import com.dia.ismdtoolbackend.client.esbirka.EsbirkaRestDtos.SearchResult;
import com.dia.ismdtoolbackend.client.esbirka.EsbirkaRestDtos.SearchRow;
import com.dia.ismdtoolbackend.models.eli.LawModel;
import com.dia.ismdtoolbackend.models.eli.LawNumberGroupModel;
import com.dia.ismdtoolbackend.models.eli.LawNumberSearchModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Grouped number search on {@code POST /rozsirena-vyhledavani}, in one call.
 *
 * <p>Rows are requested sorted by {@code kodDokumentuSbirky}: string order of the code puts every
 * act numbered exactly {@code 1} ahead of {@code 10…}, so the exact-number group arrives complete.
 * The default order is relevance-like — for "1" its first 600 rows hold 22 of the 125 acts
 * numbered 1. When the match exceeds the fetched rows, the last fetched group may be partial and
 * is dropped.
 *
 * <p>Upstream traps: {@code CISLO_PRAVNIHO_AKTU ROVNO} always returns nothing, so the číslo is
 * matched with {@code ZACINA}; {@code ROK_VYHLASENI} rejects {@code ZACINA}, so a year prefix
 * becomes a range.
 */
final class EsbirkaRestNumberSearch {

    /** Rows fetched per search; enough for the exact group of any číslo (125 acts for "1"). */
    static final int ROW_LIMIT = 600;
    private static final String AND = "AND";

    private EsbirkaRestNumberSearch() {
    }

    /** Whether the prefixes can match anything; letters in a číslo match no act. */
    static boolean isSearchable(String cisloPrefix, String rokPrefix) {
        return isDigits(cisloPrefix) && isDigits(rokPrefix);
    }

    static SearchRequest request(String cisloPrefix, String rokPrefix) {
        List<SearchCondition> conditions = new ArrayList<>();
        if (cisloPrefix != null) {
            conditions.add(new SearchCondition("CISLO_PRAVNIHO_AKTU", "ZACINA", cisloPrefix, AND));
        }
        if (rokPrefix != null) {
            int pad = Math.max(0, 4 - rokPrefix.length());
            conditions.add(new SearchCondition("ROK_VYHLASENI", "VETSI_ROVNO_NEZ", rokPrefix + "0".repeat(pad), AND));
            conditions.add(new SearchCondition("ROK_VYHLASENI", "MENSI_ROVNO_NEZ", rokPrefix + "9".repeat(pad), AND));
        }
        List<String> order = List.of(cisloPrefix == null ? "-datumVyhlaseni" : "+kodDokumentuSbirky");
        return new SearchRequest(0, ROW_LIMIT, order, conditions.isEmpty() ? null : conditions);
    }

    static LawNumberSearchModel group(SearchResult result, String cisloPrefix) {
        Map<String, List<LawModel>> byCislo = new LinkedHashMap<>();
        List<SearchRow> rows = result.seznam() == null ? List.of() : result.seznam();
        for (SearchRow row : rows) {
            if (row.staleUrl() == null || row.kodDokumentuSbirky() == null) {
                continue;
            }
            LawModel law = EsbirkaRestMapper.toLaw(row.staleUrl(), row.kodDokumentuSbirky());
            if (cisloPrefix == null || law.getCislo().startsWith(cisloPrefix)) {
                byCislo.computeIfAbsent(law.getCislo(), k -> new ArrayList<>()).add(law);
            }
        }
        boolean truncated = result.pocetCelkem() > rows.size();
        if (truncated && byCislo.size() > 1) {
            String last = null;
            for (String cislo : byCislo.keySet()) {
                last = cislo;
            }
            byCislo.remove(last);
        }
        List<LawNumberGroupModel> groups = new ArrayList<>(byCislo.size());
        List<LawModel> laws = new ArrayList<>(rows.size());
        for (Map.Entry<String, List<LawModel>> e : byCislo.entrySet()) {
            groups.add(new LawNumberGroupModel(e.getKey(), e.getValue().size()));
            laws.addAll(e.getValue());
        }
        return new LawNumberSearchModel(groups, laws, truncated);
    }

    private static boolean isDigits(String s) {
        return s == null || (!s.isEmpty() && s.chars().allMatch(c -> c >= '0' && c <= '9'));
    }
}
