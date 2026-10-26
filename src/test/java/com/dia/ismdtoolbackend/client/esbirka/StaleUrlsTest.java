package com.dia.ismdtoolbackend.client.esbirka;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaleUrlsTest {

    private static final String LAW_IRI = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/1997/49";
    private static final String VERSION_IRI = LAW_IRI + "/2025-11-01";
    private static final String FRAGMENT_IRI = VERSION_IRI + "/dokument/norma/cast_1/par_22b/frag_3305333/pism_a";

    @Test
    void encodesSlashesAsOnePathSegment() {
        assertThat(StaleUrls.encodePathSegment("/sb/1997/49")).isEqualTo("%2Fsb%2F1997%2F49");
    }

    @Test
    void encodesHashOfFragmentStaleUrl() {
        assertThat(StaleUrls.encodePathSegment("/sb/2012/89/2026-01-01#par_3000"))
                .isEqualTo("%2Fsb%2F2012%2F89%2F2026-01-01%23par_3000");
    }

    @Test
    void encodesNonAsciiAsUtf8Bytes() {
        assertThat(StaleUrls.encodePathSegment("/ašb c")).isEqualTo("%2Fa%C5%A1b%20c");
    }

    @Test
    void lawStaleUrlOfLawVersionAndFragmentIris() {
        assertThat(StaleUrls.lawStaleUrl(LAW_IRI)).isEqualTo("/sb/1997/49");
        assertThat(StaleUrls.lawStaleUrl(VERSION_IRI)).isEqualTo("/sb/1997/49");
        assertThat(StaleUrls.lawStaleUrl(FRAGMENT_IRI)).isEqualTo("/sb/1997/49");
    }

    @Test
    void versionStaleUrlOfVersionAndFragmentIris() {
        assertThat(StaleUrls.versionStaleUrl(VERSION_IRI)).isEqualTo("/sb/1997/49/2025-11-01");
        assertThat(StaleUrls.versionStaleUrl(FRAGMENT_IRI)).isEqualTo("/sb/1997/49/2025-11-01");
        assertThat(StaleUrls.versionStaleUrl(LAW_IRI + "/0000-00-00")).isEqualTo("/sb/1997/49/0000-00-00");
    }

    @Test
    void versionStaleUrlRejectsLawIri() {
        assertThatThrownBy(() -> StaleUrls.versionStaleUrl(LAW_IRI)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StaleUrls.versionStaleUrl(LAW_IRI + "/dokument"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonCanonicalIris() {
        assertThatThrownBy(() -> StaleUrls.lawStaleUrl("https://www.e-sbirka.cz/eli/cz/sb/1997/49"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StaleUrls.lawStaleUrl("https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/1997"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StaleUrls.lawStaleUrl(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toIriInvertsStaleUrl() {
        assertThat(StaleUrls.toIri(StaleUrls.versionStaleUrl(VERSION_IRI))).isEqualTo(VERSION_IRI);
        assertThat(StaleUrls.toIri("/sb/1997/49")).isEqualTo(LAW_IRI);
    }

    @Test
    void toLawStaleUrlDropsVersionDate() {
        assertThat(StaleUrls.toLawStaleUrl("/sm/2020/12/0000-00-00")).isEqualTo("/sm/2020/12");
        assertThat(StaleUrls.toLawStaleUrl("/sb/1997/49")).isEqualTo("/sb/1997/49");
    }
}
