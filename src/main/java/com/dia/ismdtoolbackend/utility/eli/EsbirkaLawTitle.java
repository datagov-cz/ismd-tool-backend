package com.dia.ismdtoolbackend.utility.eli;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An e-Sbírka act title split into act type, issuer and subject:
 * {@code "Zákon České národní rady o daních z příjmů"} → {@code Zákon} · {@code České národní rady} ·
 * {@code o daních z příjmů}. Titles without a known act type ({@code "Zákoník práce"}, 65/1965)
 * have a null type and the whole title as subject.
 *
 * @param actType e.g. {@code "Zákon"}, {@code "Nařízení vlády"}; null when the title starts otherwise
 * @param subject the rest after type and issuer, e.g. {@code "o civilním letectví"},
 *                {@code "kterým se mění …"}, {@code "občanský zákoník"}; may be empty
 */
public record EsbirkaLawTitle(String actType, String subject) {

    /** Longest first, so "Nařízení vlády" wins over "Nařízení". */
    private static final List<String> ACT_TYPES = List.of(
            "Ústavní zákon", "Zákonné opatření", "Nařízení vlády", "Vládní nařízení", "Redakční sdělení",
            "Úplné znění", "Zákon", "Vyhláška", "Nařízení", "Sdělení", "Nález", "Usnesení", "Rozhodnutí",
            "Oznámení", "Opatření", "Dekret", "Výnos");

    /** Issuers that older titles write in lower case ("Vyhláška ministra financí o …"). */
    private static final Set<String> LOWER_CASE_ISSUERS = Set.of(
            "ministra", "ministerstva", "ministerstev", "rady", "vlády", "nejvyššího", "federálního", "úřadu",
            "předsednictva", "předsedy", "zemského", "státního", "krajského", "ústředního", "generálního");

    /** Where an issuer ends and the subject begins. */
    private static final Pattern ISSUER_END = Pattern.compile(", | o | ze dne | k | jímž | kterým | kterou ");

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    public static EsbirkaLawTitle parse(String nazev) {
        String title = nazev == null ? "" : nazev.trim();
        String type = ACT_TYPES.stream().filter(t -> startsWithWord(title, t)).findFirst().orElse(null);
        if (type == null) {
            return new EsbirkaLawTitle(null, title);
        }
        String rest = title.substring(type.length());
        if (rest.startsWith(",")) {
            return new EsbirkaLawTitle(type, rest.substring(1).trim());
        }
        rest = rest.trim();
        if (hasIssuer(rest)) {
            var end = ISSUER_END.matcher(rest);
            rest = end.find() ? rest.substring(end.start()).replaceFirst("^,", "").trim() : "";
        }
        return new EsbirkaLawTitle(type, rest);
    }

    /**
     * The picker label: Czech citation form {@code "Zákon č. 49/1997 Sb., o civilním letectví"}.
     * Without a title (SPARQL rows) it stays {@code "Zákon č. <citace>"}; a title without a known act
     * type gives {@code "<citace>, <title>"}.
     */
    public static String displayName(String citace, String nazev) {
        if (nazev == null || nazev.isBlank()) {
            return "Zákon č. " + citace;
        }
        EsbirkaLawTitle title = parse(nazev);
        if (title.actType() == null) {
            return citace + ", " + title.subject();
        }
        String label = title.actType() + " č. " + citace;
        return title.subject().isEmpty() ? label : label + ", " + title.subject();
    }

    /** Lower case without diacritics, whitespace collapsed: {@code "Zákoník  práce"} → {@code "zakonik prace"}. */
    public static String fold(String s) {
        if (s == null) {
            return "";
        }
        String stripped = MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("");
        return stripped.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static boolean hasIssuer(String rest) {
        if (rest.isEmpty() || rest.startsWith("o ")) {
            return false;
        }
        String first = rest.split("[\\s,]", 2)[0];
        return !first.isEmpty() && (Character.isUpperCase(first.charAt(0)) || LOWER_CASE_ISSUERS.contains(first));
    }

    private static boolean startsWithWord(String title, String prefix) {
        if (!title.startsWith(prefix)) {
            return false;
        }
        return title.length() == prefix.length() || !Character.isLetter(title.charAt(prefix.length()));
    }
}