package com.dia.ismdtoolbackend.utility.eli;

import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

public final class EsbirkaEliParser {

    private static final String CANONICAL_DOMAIN = "https://opendata.eselpoint.gov.cz/esel-esb";
    private static final String CANONICAL_HOST_PREFIX = "://opendata.eselpoint.gov.cz/esel-esb/";
    private static final String LEGACY_HOST_PREFIX = "://opendata.eselpoint.cz/esel-esb/";
    private static final String BARE_LEGACY_HOST_PREFIX = "://eselpoint.cz/";

    private static final String ELI_MARKER = "/eli/";
    private static final String DOKUMENT_NORMA = "dokument/norma";

    private EsbirkaEliParser() {
    }

    public static ParsedEli parse(String url) {
        if (url == null || url.isBlank()) {
            return invalid(url);
        }
        String normalized = normalizeHost(url);
        if (!SparqlIriValidator.isEsbirkaEliIri(normalized)) {
            return invalid(url);
        }
        int eliIdx = normalized.indexOf(ELI_MARKER);
        if (eliIdx < 0) {
            return invalid(url);
        }
        String eliPath = normalized.substring(eliIdx);
        String pathBody = eliPath.substring(ELI_MARKER.length());
        String[] segs = pathBody.split("/");
        if (segs.length < 4 || !"cz".equals(segs[0])) {
            return invalid(url);
        }
        String sbirkaCode = segs[1];
        Integer lawYear = parseIntOrNull(segs[2]);
        String lawNumber = segs[3];
        if (lawYear == null || lawNumber.isBlank()) {
            return invalid(url);
        }

        String lawEliPath = ELI_MARKER + "cz/" + sbirkaCode + "/" + lawYear + "/" + lawNumber;
        String lawIri = CANONICAL_DOMAIN + lawEliPath;

        if (segs.length == 4) {
            return new ParsedEli(url, CANONICAL_DOMAIN, lawEliPath,
                    lawIri, null, null, lawNumber, lawYear, sbirkaCode,
                    null, List.of(), ParsedEli.Level.LAW);
        }

        String dateSeg = segs[4];
        LocalDate versionDate = parseDateOrNull(dateSeg);
        String versionEliPath = lawEliPath + "/" + dateSeg;
        String versionIri = CANONICAL_DOMAIN + versionEliPath;

        if (segs.length == 5) {
            return new ParsedEli(url, CANONICAL_DOMAIN, versionEliPath,
                    lawIri, versionIri, null, lawNumber, lawYear, sbirkaCode,
                    versionDate, List.of(), ParsedEli.Level.VERSION);
        }

        if (segs.length < 7 || !DOKUMENT_NORMA.equals(segs[5] + "/" + segs[6])) {
            return invalid(url);
        }

        List<ParsedEli.FragmentSegment> fragmentSegments = new ArrayList<>();
        for (int i = 7; i < segs.length; i++) {
            fragmentSegments.add(splitSegment(segs[i]));
        }
        if (fragmentSegments.isEmpty()) {
            return invalid(url);
        }
        String fragmentIri = CANONICAL_DOMAIN + eliPath;
        return new ParsedEli(url, CANONICAL_DOMAIN, eliPath,
                lawIri, versionIri, fragmentIri, lawNumber, lawYear, sbirkaCode,
                versionDate, List.copyOf(fragmentSegments), ParsedEli.Level.FRAGMENT);
    }

    /**
     * Rewrites a legacy e-Sbírka host ({@code opendata.eselpoint.cz}, bare {@code eselpoint.cz}) to the
     * canonical {@code opendata.eselpoint.gov.cz} form, leaving already-canonical or unrelated IRIs
     * untouched. Write paths call this before validating/storing so a legacy IRI is accepted and persisted
     * canonically — matching what {@link #parse} does on the read side.
     */
    public static String canonicalizeHost(String url) {
        return normalizeHost(url);
    }

    static String normalizeHost(String url) {
        int legacy = url.indexOf(LEGACY_HOST_PREFIX);
        if (legacy >= 0) {
            return url.substring(0, legacy) + CANONICAL_HOST_PREFIX + url.substring(legacy + LEGACY_HOST_PREFIX.length());
        }
        int bareLegacy = url.indexOf(BARE_LEGACY_HOST_PREFIX);
        if (bareLegacy >= 0 && url.indexOf(ELI_MARKER, bareLegacy) > 0) {
            return url.substring(0, bareLegacy) + CANONICAL_HOST_PREFIX + url.substring(bareLegacy + BARE_LEGACY_HOST_PREFIX.length());
        }
        return url;
    }

    private static ParsedEli invalid(String url) {
        return new ParsedEli(url, null, null, null, null, null, null, null, null, null, List.of(), null);
    }

    private static Integer parseIntOrNull(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDateOrNull(String s) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static ParsedEli.FragmentSegment splitSegment(String seg) {
        int us = seg.indexOf('_');
        if (us <= 0 || us >= seg.length() - 1) {
            return new ParsedEli.FragmentSegment(seg, "");
        }
        return new ParsedEli.FragmentSegment(seg.substring(0, us), seg.substring(us + 1));
    }
}
