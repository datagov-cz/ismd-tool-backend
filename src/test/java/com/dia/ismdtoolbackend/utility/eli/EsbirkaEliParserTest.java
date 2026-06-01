package com.dia.ismdtoolbackend.utility.eli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsbirkaEliParserTest {

    private static final String CANONICAL_LAW = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2000/361";
    private static final String CANONICAL_VERSION = CANONICAL_LAW + "/2024-04-01";
    private static final String CANONICAL_FRAGMENT = CANONICAL_VERSION
            + "/dokument/norma/cast_1/hlava_1/par_2/pism_d";

    @Test
    void parse_lawOnlyIri_returnsLawLevel() {
        ParsedEli p = EsbirkaEliParser.parse(CANONICAL_LAW);

        assertTrue(p.isValid());
        assertEquals(ParsedEli.Level.LAW, p.level());
        assertEquals("361", p.lawNumber());
        assertEquals(2000, p.lawYear());
        assertEquals("sb", p.sbirkaCode());
        assertEquals(CANONICAL_LAW, p.lawIri());
        assertNull(p.versionIri());
        assertNull(p.fragmentIri());
        assertNull(p.versionDate());
        assertTrue(p.fragmentSegments().isEmpty());
        assertEquals("/eli/cz/sb/2000/361", p.eliPath());
        assertEquals("https://opendata.eselpoint.gov.cz/esel-esb", p.domain());
    }

    @Test
    void parse_versionIri_returnsVersionLevel() {
        ParsedEli p = EsbirkaEliParser.parse(CANONICAL_VERSION);

        assertTrue(p.isValid());
        assertEquals(ParsedEli.Level.VERSION, p.level());
        assertEquals(LocalDate.of(2024, 4, 1), p.versionDate());
        assertEquals(CANONICAL_VERSION, p.versionIri());
        assertEquals(CANONICAL_LAW, p.lawIri());
        assertNull(p.fragmentIri());
        assertTrue(p.fragmentSegments().isEmpty());
    }

    @Test
    void parse_fragmentIri_returnsFragmentLevel() {
        ParsedEli p = EsbirkaEliParser.parse(CANONICAL_FRAGMENT);

        assertTrue(p.isValid());
        assertEquals(ParsedEli.Level.FRAGMENT, p.level());
        assertTrue(p.isFragment());
        assertEquals(CANONICAL_FRAGMENT, p.fragmentIri());
        assertEquals(CANONICAL_VERSION, p.versionIri());
        assertEquals(CANONICAL_LAW, p.lawIri());

        assertEquals(4, p.fragmentSegments().size());
        assertEquals(new ParsedEli.FragmentSegment("cast", "1"), p.fragmentSegments().get(0));
        assertEquals(new ParsedEli.FragmentSegment("hlava", "1"), p.fragmentSegments().get(1));
        assertEquals(new ParsedEli.FragmentSegment("par", "2"), p.fragmentSegments().get(2));
        assertEquals(new ParsedEli.FragmentSegment("pism", "d"), p.fragmentSegments().get(3));
    }

    @Test
    void parse_legacyHost_normalizes() {
        String legacyFragment = "https://opendata.eselpoint.cz/esel-esb/eli/cz/sb/2000/361"
                + "/2024-04-01/dokument/norma/par_2/pism_d";

        ParsedEli p = EsbirkaEliParser.parse(legacyFragment);

        assertTrue(p.isValid());
        assertEquals(legacyFragment, p.originalUrl());
        assertEquals("https://opendata.eselpoint.gov.cz/esel-esb", p.domain());
        assertTrue(p.lawIri().startsWith("https://opendata.eselpoint.gov.cz/esel-esb/"));
        assertTrue(p.versionIri().startsWith("https://opendata.eselpoint.gov.cz/esel-esb/"));
        assertTrue(p.fragmentIri().startsWith("https://opendata.eselpoint.gov.cz/esel-esb/"));
    }

    @Test
    void parse_legacyLawOnly_normalizes() {
        String legacyLaw = "https://opendata.eselpoint.cz/esel-esb/eli/cz/sb/2000/361";

        ParsedEli p = EsbirkaEliParser.parse(legacyLaw);

        assertTrue(p.isValid());
        assertEquals(ParsedEli.Level.LAW, p.level());
        assertEquals(legacyLaw, p.originalUrl());
        assertEquals(CANONICAL_LAW, p.lawIri());
    }

    @Test
    void parse_null_returnsInvalid() {
        ParsedEli p = EsbirkaEliParser.parse(null);
        assertFalse(p.isValid());
        assertNull(p.level());
        assertNull(p.originalUrl());
    }

    @Test
    void parse_blank_returnsInvalid() {
        ParsedEli p = EsbirkaEliParser.parse("   ");
        assertFalse(p.isValid());
    }

    @Test
    void parse_foreignHost_returnsInvalid() {
        ParsedEli p = EsbirkaEliParser.parse("https://example.com/eli/cz/sb/2000/361");
        assertFalse(p.isValid());
        assertNull(p.level());
    }

    @Test
    void parse_nonEliPath_returnsInvalid() {
        ParsedEli p = EsbirkaEliParser.parse("https://opendata.eselpoint.gov.cz/esel-esb/foo/bar");
        assertFalse(p.isValid());
    }

    @Test
    void parse_malformedDate_keepsLevelVersionDateNull() {
        String url = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2000/361/not-a-date";
        ParsedEli p = EsbirkaEliParser.parse(url);

        assertTrue(p.isValid());
        assertEquals(ParsedEli.Level.VERSION, p.level());
        assertNull(p.versionDate());
        assertNotNull(p.versionIri());
    }

    @Test
    void parse_fragmentMissingDokumentNorma_returnsInvalid() {
        String bogusFragment = CANONICAL_VERSION + "/something/else/par_2";
        ParsedEli p = EsbirkaEliParser.parse(bogusFragment);
        assertFalse(p.isValid());
    }

    @Test
    void parse_yearNotInteger_returnsInvalid() {
        String url = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/twentythousand/361";
        ParsedEli p = EsbirkaEliParser.parse(url);
        assertFalse(p.isValid());
    }

    @ParameterizedTest
    @ValueSource(strings = {"par", "odst", "pism", "bod", "ppc", "dil", "hlava", "oddil", "cast", "frag"})
    void parse_allTenKinds_extractsKindCorrectly(String kind) {
        String url = CANONICAL_VERSION + "/dokument/norma/" + kind + "_1";

        ParsedEli p = EsbirkaEliParser.parse(url);

        assertTrue(p.isValid());
        assertEquals(ParsedEli.Level.FRAGMENT, p.level());
        assertEquals(1, p.fragmentSegments().size());
        assertEquals(kind, p.fragmentSegments().get(0).kind());
        assertEquals("1", p.fragmentSegments().get(0).number());
    }

    @Test
    void parse_preservesOriginalUrlVerbatim() {
        String original = "https://opendata.eselpoint.cz/esel-esb/eli/cz/sb/2000/361";
        ParsedEli p = EsbirkaEliParser.parse(original);
        assertEquals(original, p.originalUrl());
    }
}
