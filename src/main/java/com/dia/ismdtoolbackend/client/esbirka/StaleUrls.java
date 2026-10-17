package com.dia.ismdtoolbackend.client.esbirka;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Converts between canonical e-Sbírka ELI IRIs and REST API {@code staleUrl}s.
 *
 * <p>A staleUrl is the ELI path without {@code /eli/cz}: IRI
 * {@code https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/1997/49/2025-11-01} has staleUrl
 * {@code /sb/1997/49/2025-11-01}. In a request path the staleUrl is one fully percent-encoded
 * segment ({@code %2Fsb%2F1997%2F49}); raw slashes 404.
 */
final class StaleUrls {

    static final String IRI_BASE = "https://opendata.eselpoint.gov.cz/esel-esb";
    private static final String ELI_CZ = "/eli/cz";
    private static final String ELI_IRI_PREFIX = IRI_BASE + ELI_CZ + "/";
    private static final Pattern VERSION_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private StaleUrls() {
    }

    /** Law staleUrl ({@code /sb/1997/49}) of any law, version or fragment IRI. */
    static String lawStaleUrl(String iri) {
        String[] segments = segmentsOf(iri);
        if (segments.length < 3 || segments[0].isEmpty() || segments[1].isEmpty() || segments[2].isEmpty()) {
            throw new IllegalArgumentException("Not an e-Sbírka law IRI: " + iri);
        }
        return "/" + segments[0] + "/" + segments[1] + "/" + segments[2];
    }

    /** Version staleUrl ({@code /sb/1997/49/2025-11-01}) of a version or fragment IRI. */
    static String versionStaleUrl(String iri) {
        String[] segments = segmentsOf(iri);
        if (segments.length < 4 || !VERSION_DATE.matcher(segments[3]).matches()) {
            throw new IllegalArgumentException("Not an e-Sbírka version IRI: " + iri);
        }
        return lawStaleUrl(iri) + "/" + segments[3];
    }

    /** Law staleUrl of a law or version staleUrl: drops a trailing {@code /YYYY-MM-DD}. */
    static String toLawStaleUrl(String staleUrl) {
        String[] segments = staleUrl.substring(1).split("/");
        if (segments.length < 3) {
            throw new IllegalArgumentException("Not an e-Sbírka staleUrl: " + staleUrl);
        }
        return "/" + segments[0] + "/" + segments[1] + "/" + segments[2];
    }

    /** Canonical IRI of a law or version staleUrl. */
    static String toIri(String staleUrl) {
        return IRI_BASE + ELI_CZ + staleUrl;
    }

    /** Canonical IRI of a REST {@code eli} field ({@code /eli/cz/sb/...}). */
    static String fromEli(String eli) {
        return IRI_BASE + eli;
    }

    /** The staleUrl as one path segment: every byte outside RFC 3986 unreserved is percent-encoded. */
    static String encodePathSegment(String staleUrl) {
        byte[] bytes = staleUrl.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (isUnreserved(c)) {
                out.append((char) c);
            } else {
                out.append('%').append(HEX[c >> 4]).append(HEX[c & 0x0F]);
            }
        }
        return out.toString();
    }

    private static String[] segmentsOf(String iri) {
        if (iri == null || !iri.startsWith(ELI_IRI_PREFIX)) {
            throw new IllegalArgumentException("Not a canonical e-Sbírka IRI: " + iri);
        }
        return iri.substring(ELI_IRI_PREFIX.length()).split("/");
    }

    private static boolean isUnreserved(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
    }
}
