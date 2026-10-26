package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.utility.eli.EsbirkaEliParser;
import com.dia.ismdtoolbackend.utility.eli.ParsedEli;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import com.dia.ismdtoolbackend.utility.UtilityMethods;

/** Reads law references: a {@code číslo/rok} or an e-Sbírka ELI IRI, in any accepted host spelling. */
final class EsbirkaLawReference {

    private EsbirkaLawReference() {
    }

    /** Cheap tell that a law reference is an IRI rather than a číslo/rok, before full parsing. */
    static final String ELI_MARKER = "/eli/";

    static String canonicalize(String iri) {
        return iri == null ? null
                : UtilityMethods.removeTrailingSlash(EsbirkaEliParser.canonicalizeHost(iri.trim()));
    }

    /** Canonicalize, then validate — a legacy host is a spelling, not an invalid identifier. */
    static String requireValidIri(String iri, String message) {
        String canonical = canonicalize(iri);
        if (!SparqlIriValidator.isEsbirkaEliIri(canonical)) {
            throw new IllegalArgumentException(message);
        }
        return canonical;
    }

    /**
     * Parse a law reference into its parts, accepting either a "number/year" reference
     * (tolerating surrounding whitespace and a trailing " Sb.") or an e-Sbírka ELI IRI at law,
     * version or fragment level. Partial input (e.g. "49") is rejected; callers use /law/search
     * for that.
     *
     * <p>An IRI is delegated to {@link EsbirkaEliParser}, not split here: its path is
     * {@code .../{rok}/{číslo}/...} — year before number, the reverse of the human reference —
     * and it carries legacy hosts that need canonicalizing first.
     */
    static NumberYear parseNumberYear(String lawRef) {
        if (lawRef == null || lawRef.isBlank()) {
            throw new IllegalArgumentException(
                    "Zadejte referenci právního aktu ve tvaru číslo/rok (např. 49/1997).");
        }
        NumberYear fromIri = numberYearFromIri(lawRef);
        if (fromIri != null) {
            return fromIri;
        }
        String cleaned = lawRef.trim();
        int sb = cleaned.indexOf(" Sb");
        if (sb > 0) {
            cleaned = cleaned.substring(0, sb).trim();
        }
        int slash = cleaned.indexOf('/');
        if (slash <= 0 || slash >= cleaned.length() - 1) {
            throw new IllegalArgumentException(
                    "Referenci zadejte ve tvaru číslo/rok (např. 49/1997).");
        }
        String number = cleaned.substring(0, slash).trim();
        String yearStr = cleaned.substring(slash + 1).trim();
        int year;
        try {
            year = Integer.parseInt(yearStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Rok v referenci musí být číslo (např. 49/1997).");
        }
        if (number.isBlank() || !number.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(
                    "Číslo předpisu v referenci musí být číselné (např. 49/1997).");
        }
        return new NumberYear(number, year);
    }

    /**
     * The číslo/rok of an e-Sbírka ELI IRI, or null when {@code lawRef} is not one — including
     * an IRI-shaped string the parser rejects, which falls through to the číslo/rok parse and
     * its message rather than being reported as a bad IRI.
     */
    private static NumberYear numberYearFromIri(String lawRef) {
        String trimmed = lawRef.trim();
        if (!trimmed.contains(ELI_MARKER)) {
            return null;
        }
        ParsedEli parsed = EsbirkaEliParser.parse(trimmed);
        if (parsed.level() == null || parsed.lawNumber() == null || parsed.lawYear() == null) {
            return null;
        }
        return new NumberYear(parsed.lawNumber(), parsed.lawYear());
    }

    record NumberYear(String number, int year) {}
}
