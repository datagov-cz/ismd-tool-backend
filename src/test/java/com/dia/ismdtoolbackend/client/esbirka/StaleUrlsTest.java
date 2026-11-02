package com.dia.ismdtoolbackend.client.esbirka;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StaleUrlsTest {

    private static final String LAW_IRI = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/1997/49";
    private static final String VERSION_IRI = LAW_IRI + "/2025-11-01";
    private static final String FRAGMENT_IRI = VERSION_IRI + "/dokument/norma/cast_1/par_22b/frag_3305333/pism_a";

    @Test
    void encodesSlashesAsOnePathSegment() {
        assertEquals("%2Fsb%2F1997%2F49", StaleUrls.encodePathSegment("/sb/1997/49"));
    }

    @Test
    void encodesHashOfFragmentStaleUrl() {
        assertEquals("%2Fsb%2F2012%2F89%2F2026-01-01%23par_3000",
                StaleUrls.encodePathSegment("/sb/2012/89/2026-01-01#par_3000"));
    }

    @Test
    void encodesNonAsciiAsUtf8Bytes() {
        assertEquals("%2Fa%C5%A1b%20c", StaleUrls.encodePathSegment("/ašb c"));
    }

    @Test
    void lawStaleUrlOfLawVersionAndFragmentIris() {
        assertEquals("/sb/1997/49", StaleUrls.lawStaleUrl(LAW_IRI));
        assertEquals("/sb/1997/49", StaleUrls.lawStaleUrl(VERSION_IRI));
        assertEquals("/sb/1997/49", StaleUrls.lawStaleUrl(FRAGMENT_IRI));
    }

    @Test
    void versionStaleUrlOfVersionAndFragmentIris() {
        assertEquals("/sb/1997/49/2025-11-01", StaleUrls.versionStaleUrl(VERSION_IRI));
        assertEquals("/sb/1997/49/2025-11-01", StaleUrls.versionStaleUrl(FRAGMENT_IRI));
        assertEquals("/sb/1997/49/0000-00-00", StaleUrls.versionStaleUrl(LAW_IRI + "/0000-00-00"));
    }

    @Test
    void versionStaleUrlRejectsLawIri() {
        assertThrows(IllegalArgumentException.class, () -> StaleUrls.versionStaleUrl(LAW_IRI));
        assertThrows(IllegalArgumentException.class, () -> StaleUrls.versionStaleUrl(LAW_IRI + "/dokument"));
    }

    @Test
    void rejectsNonCanonicalIris() {
        assertThrows(IllegalArgumentException.class,
                () -> StaleUrls.lawStaleUrl("https://www.e-sbirka.cz/eli/cz/sb/1997/49"));
        assertThrows(IllegalArgumentException.class,
                () -> StaleUrls.lawStaleUrl("https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/1997"));
        assertThrows(IllegalArgumentException.class, () -> StaleUrls.lawStaleUrl(null));
    }

    @Test
    void toIriInvertsStaleUrl() {
        assertEquals(VERSION_IRI, StaleUrls.toIri(StaleUrls.versionStaleUrl(VERSION_IRI)));
        assertEquals(LAW_IRI, StaleUrls.toIri("/sb/1997/49"));
    }

    @Test
    void toLawStaleUrlDropsVersionDate() {
        assertEquals("/sm/2020/12", StaleUrls.toLawStaleUrl("/sm/2020/12/0000-00-00"));
        assertEquals("/sb/1997/49", StaleUrls.toLawStaleUrl("/sb/1997/49"));
    }
}
