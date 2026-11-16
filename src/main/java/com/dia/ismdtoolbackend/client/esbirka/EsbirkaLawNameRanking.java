package com.dia.ismdtoolbackend.client.esbirka;

import com.dia.ismdtoolbackend.client.esbirka.EsbirkaRestDtos.SearchResult;
import com.dia.ismdtoolbackend.client.esbirka.EsbirkaRestDtos.SearchRow;
import com.dia.ismdtoolbackend.client.esbirka.EsbirkaRestDtos.SuggestedDocument;
import com.dia.ismdtoolbackend.client.esbirka.EsbirkaRestDtos.Suggestions;
import com.dia.ismdtoolbackend.models.eli.LawModel;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaLawTitle;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.dia.ismdtoolbackend.utility.eli.EsbirkaLawTitle.fold;

/**
 * Merges and ranks name-search results. e-Sbírka's own order is weak — for "civilním letectví" the
 * suggester puts 49/1997 14th, behind treaty notices — so candidates from the suggester and the
 * title search are merged by law and sorted on, in order:
 * <ol>
 *   <li>derived acts (amendments, Constitutional Court rulings, notices, consolidated texts) last —
 *       or first when the query names that kind of act ("nález …", "… kterým se mění …");</li>
 *   <li>collections other than Sb. after Sb.;</li>
 *   <li>in force first, then other statuses (title search is limited to valid acts), then status unknown
 *       (suggester only, which also returns repealed acts);</li>
 *   <li>act type: zákon, nařízení, vyhláška, other;</li>
 *   <li>name match against the title without act type and issuer: exact, prefix, contained;</li>
 *   <li>newer act, then shorter title.</li>
 * </ol>
 */
final class EsbirkaLawNameRanking {

    /** Act types whose title-word removal fixes upstream's accent handling: "zakon o …" finds nothing. */
    private static final Set<String> STRIPPABLE_ACT_TYPES = Set.of("zakon", "vyhlaska", "narizeni");

    private static final Set<String> DERIVED_TYPES = Set.of(
            "Nález", "Úplné znění", "Sdělení", "Redakční sdělení", "Rozhodnutí", "Usnesení", "Oznámení");
    private static final Pattern DERIVING_SUBJECT =
            Pattern.compile("^(kter\\w*|jimz) se (\\w+ a )?(meni|zrusuj|provad|dopln)");
    /** Words that, typed by the user, ask for derived acts. */
    private static final Pattern DERIVED_QUERY_WORD =
            Pattern.compile("\\b(nalez|sdeleni|usneseni|rozhodnuti|oznameni|uplne zneni|meni|zrusuj|provad|dopln)");

    private static final String IN_FORCE = "AKTUALNE_PLATNY";

    private EsbirkaLawNameRanking() {
    }

    /**
     * The queries to send: the user's, plus — for "zákon o …" — the same without the act-type word.
     * Upstream's accent handling is inconsistent: "zakon o civilnim letectvi" finds nothing while
     * "o civilnim letectvi" finds 49/1997.
     */
    static List<String> queries(String q) {
        String[] words = q.trim().split("\\s+");
        if (words.length > 2 && STRIPPABLE_ACT_TYPES.contains(fold(words[0])) && "o".equals(fold(words[1]))) {
            return List.of(q.trim(), q.trim().substring(words[0].length()).trim());
        }
        return List.of(q.trim());
    }

    static List<LawModel> rank(String q, List<Suggestions> suggestions, List<SearchResult> titleMatches, int limit) {
        // Title-search rows first: they carry the in-force status, and the first row per law wins.
        Map<String, Candidate> byLaw = new LinkedHashMap<>();
        for (SearchResult result : titleMatches) {
            for (SearchRow row : result.seznam() == null ? List.<SearchRow>of() : result.seznam()) {
                add(byLaw, row.staleUrl(), row.kodDokumentuSbirky(), row.nazev(), row.stavDokumentuSbirky());
            }
        }
        for (Suggestions s : suggestions) {
            for (SuggestedDocument doc : s.seznamDokumentuSbirky() == null ? List.<SuggestedDocument>of()
                    : s.seznamDokumentuSbirky()) {
                add(byLaw, doc.staleUrl(), doc.kodDokumentuSbirky(), doc.nazev(), null);
            }
        }
        Query query = new Query(q);
        return byLaw.values().stream()
                .sorted(Comparator.<Candidate>comparingInt(c -> c.derivedRank(query))
                        .thenComparingInt(c -> "sb".equals(c.law().getSbirka()) ? 0 : 1)
                        .thenComparingInt(Candidate::statusRank)
                        .thenComparingInt(Candidate::typeRank)
                        .thenComparingInt(c -> c.matchRank(query))
                        .thenComparing(c -> c.law().getRok(), Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparingInt(c -> c.law().getNazev().length()))
                .limit(limit)
                .map(Candidate::law)
                .toList();
    }

    private static void add(Map<String, Candidate> byLaw, String staleUrl, String kod, String nazev, String status) {
        if (staleUrl == null || kod == null || nazev == null) {
            return;
        }
        LawModel law = EsbirkaRestMapper.toLaw(staleUrl, kod);
        law.setNazev(nazev);
        byLaw.putIfAbsent(law.getIri(), new Candidate(law, EsbirkaLawTitle.parse(nazev), status));
    }

    private record Query(String folded, String core) {
        Query(String q) {
            this(fold(q), fold(queries(q).get(queries(q).size() - 1)));
        }
    }

    private record Candidate(LawModel law, EsbirkaLawTitle title, String status) {

        /** 0 = derived act the query asks for, 1 = not derived, 2 = derived act the query does not ask for. */
        int derivedRank(Query q) {
            boolean derivedAct = (title.actType() != null && DERIVED_TYPES.contains(title.actType()))
                    || DERIVING_SUBJECT.matcher(fold(title.subject())).find();
            if (!derivedAct) {
                return 1;
            }
            return DERIVED_QUERY_WORD.matcher(q.folded()).find() ? 0 : 2;
        }

        int statusRank() {
            if (IN_FORCE.equals(status)) {
                return 0;
            }
            return status == null ? 2 : 1;
        }

        int typeRank() {
            String type = title.actType();
            if (type == null) {
                return 3;
            }
            if (type.endsWith("zákon") || type.equals("Zákon")) {
                return 0;
            }
            if (type.contains("ařízení")) {
                return 1;
            }
            return type.equals("Vyhláška") ? 2 : 3;
        }

        int matchRank(Query q) {
            String subject = fold(title.subject());
            if (subject.equals(q.core())) {
                return 0;
            }
            if (subject.startsWith(q.core())) {
                return 1;
            }
            return subject.contains(q.core()) ? 2 : 3;
        }
    }
}