package com.dia.ismdtoolbackend.client.esbirka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * The parts of the e-Sbírka REST payloads we read. Hand-written rather than generated: the
 * published spec (Jan 2024) has 158 schemas and lags live payloads, which carry fields it lacks.
 * Unknown properties are ignored.
 */
final class EsbirkaRestDtos {

    private EsbirkaRestDtos() {
    }

    /** {@code GET /dokumenty-sbirky/{staleUrl}} — the current version for a law staleUrl. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DocumentDetail(String staleUrl, String typZneni, LocalDate datumUcinnostiZneniOd,
                          String zkracenaCitace, String kodDokumentuSbirky, String nazev, String eli) {
    }

    /** {@code GET /dokumenty-sbirky/{staleUrl}/historie} — every version of a law. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Historie(List<HistorieEntry> historie) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HistorieEntry(String staleUrl, String typZneni, LocalDate datumUcinnostiZneniOd,
                         LocalDate datumUcinnostiZneniDo, Integer cisloZneni) {
    }

    /** {@code GET /dokumenty-sbirky/{versionStaleUrl}/fragmenty?cisloStranky=N} — 1000 per page. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FragmentPage(int pocetStranek, List<Fragment> seznam) {
    }

    /**
     * One fragment in document order. {@code hloubka} is its depth (0 = document root);
     * {@code xhtml} is its own text, not its subtree's.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Fragment(String eli, String kodTypuFragmentu, int hloubka, String xhtml, String zkracenaCitace) {
    }

    /** {@code POST /rozsirena-vyhledavani} body. {@code razeni} entries are {@code "+attr"} / {@code "-attr"}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SearchRequest(int start, int pocet, List<String> razeni, List<SearchCondition> dalsiPodminky) {
    }

    /** One condition; {@code logickaOperace} is required on each once there are several. */
    record SearchCondition(String polozka, String operace, String hodnotaText, String logickaOperace) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResult(int pocetCelkem, List<SearchRow> seznam) {
    }

    /** A matched act; {@code staleUrl} may be version-level for full-text hits. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchRow(String staleUrl, String kodDokumentuSbirky, String nazev) {
    }
}
