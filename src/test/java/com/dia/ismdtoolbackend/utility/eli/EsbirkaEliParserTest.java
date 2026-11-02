package com.dia.ismdtoolbackend.utility.eli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class EsbirkaEliParserTest {

    private static final String CANONICAL_LAW = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2000/361";
    private static final String CANONICAL_VERSION = CANONICAL_LAW + "/2024-04-01";
    private static final String CANONICAL_FRAGMENT = CANONICAL_VERSION
            + "/dokument/norma/cast_1/hlava_1/par_2/pism_d";

    @Test
    void parse_lawOnlyIri_returnsLawLevel() {
        ParsedEli p = EsbirkaEliParser.parse(CANONICAL_LAW);

        assertThat(p.isValid()).isTrue();
        assertThat(p.level()).isEqualTo(ParsedEli.Level.LAW);
        assertThat(p.lawNumber()).isEqualTo("361");
        assertThat(p.lawYear()).isEqualTo(2000);
        assertThat(p.sbirkaCode()).isEqualTo("sb");
        assertThat(p.lawIri()).isEqualTo(CANONICAL_LAW);
        assertThat(p.versionIri()).isNull();
        assertThat(p.fragmentIri()).isNull();
        assertThat(p.versionDate()).isNull();
        assertThat(p.fragmentSegments()).isEmpty();
        assertThat(p.eliPath()).isEqualTo("/eli/cz/sb/2000/361");
        assertThat(p.domain()).isEqualTo("https://opendata.eselpoint.gov.cz/esel-esb");
    }

    @Test
    void parse_versionIri_returnsVersionLevel() {
        ParsedEli p = EsbirkaEliParser.parse(CANONICAL_VERSION);

        assertThat(p.isValid()).isTrue();
        assertThat(p.level()).isEqualTo(ParsedEli.Level.VERSION);
        assertThat(p.versionDate()).isEqualTo(LocalDate.of(2024, 4, 1));
        assertThat(p.versionIri()).isEqualTo(CANONICAL_VERSION);
        assertThat(p.lawIri()).isEqualTo(CANONICAL_LAW);
        assertThat(p.fragmentIri()).isNull();
        assertThat(p.fragmentSegments()).isEmpty();
    }

    @Test
    void parse_fragmentIri_returnsFragmentLevel() {
        ParsedEli p = EsbirkaEliParser.parse(CANONICAL_FRAGMENT);

        assertThat(p.isValid()).isTrue();
        assertThat(p.level()).isEqualTo(ParsedEli.Level.FRAGMENT);
        assertThat(p.isFragment()).isTrue();
        assertThat(p.fragmentIri()).isEqualTo(CANONICAL_FRAGMENT);
        assertThat(p.versionIri()).isEqualTo(CANONICAL_VERSION);
        assertThat(p.lawIri()).isEqualTo(CANONICAL_LAW);

        assertThat(p.fragmentSegments()).hasSize(4);
        assertThat(p.fragmentSegments().get(0)).isEqualTo(new ParsedEli.FragmentSegment("cast", "1"));
        assertThat(p.fragmentSegments().get(1)).isEqualTo(new ParsedEli.FragmentSegment("hlava", "1"));
        assertThat(p.fragmentSegments().get(2)).isEqualTo(new ParsedEli.FragmentSegment("par", "2"));
        assertThat(p.fragmentSegments().get(3)).isEqualTo(new ParsedEli.FragmentSegment("pism", "d"));
    }

    @Test
    void parse_legacyHost_normalizes() {
        String legacyFragment = "https://opendata.eselpoint.cz/esel-esb/eli/cz/sb/2000/361"
                + "/2024-04-01/dokument/norma/par_2/pism_d";

        ParsedEli p = EsbirkaEliParser.parse(legacyFragment);

        assertThat(p.isValid()).isTrue();
        assertThat(p.originalUrl()).isEqualTo(legacyFragment);
        assertThat(p.domain()).isEqualTo("https://opendata.eselpoint.gov.cz/esel-esb");
        assertThat(p.lawIri()).startsWith("https://opendata.eselpoint.gov.cz/esel-esb/");
        assertThat(p.versionIri()).startsWith("https://opendata.eselpoint.gov.cz/esel-esb/");
        assertThat(p.fragmentIri()).startsWith("https://opendata.eselpoint.gov.cz/esel-esb/");
    }

    @Test
    void parse_legacyLawOnly_normalizes() {
        String legacyLaw = "https://opendata.eselpoint.cz/esel-esb/eli/cz/sb/2000/361";

        ParsedEli p = EsbirkaEliParser.parse(legacyLaw);

        assertThat(p.isValid()).isTrue();
        assertThat(p.level()).isEqualTo(ParsedEli.Level.LAW);
        assertThat(p.originalUrl()).isEqualTo(legacyLaw);
        assertThat(p.lawIri()).isEqualTo(CANONICAL_LAW);
    }

    @Test
    void parse_null_returnsInvalid() {
        ParsedEli p = EsbirkaEliParser.parse(null);
        assertThat(p.isValid()).isFalse();
        assertThat(p.level()).isNull();
        assertThat(p.originalUrl()).isNull();
    }

    @Test
    void parse_blank_returnsInvalid() {
        ParsedEli p = EsbirkaEliParser.parse("   ");
        assertThat(p.isValid()).isFalse();
    }

    @Test
    void parse_foreignHost_returnsInvalid() {
        ParsedEli p = EsbirkaEliParser.parse("https://example.com/eli/cz/sb/2000/361");
        assertThat(p.isValid()).isFalse();
        assertThat(p.level()).isNull();
    }

    @Test
    void parse_nonEliPath_returnsInvalid() {
        ParsedEli p = EsbirkaEliParser.parse("https://opendata.eselpoint.gov.cz/esel-esb/foo/bar");
        assertThat(p.isValid()).isFalse();
    }

    @Test
    void parse_malformedDate_keepsLevelVersionDateNull() {
        String url = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2000/361/not-a-date";
        ParsedEli p = EsbirkaEliParser.parse(url);

        assertThat(p.isValid()).isTrue();
        assertThat(p.level()).isEqualTo(ParsedEli.Level.VERSION);
        assertThat(p.versionDate()).isNull();
        assertThat(p.versionIri()).isNotNull();
    }

    @Test
    void parse_fragmentMissingDokumentNorma_returnsInvalid() {
        String bogusFragment = CANONICAL_VERSION + "/something/else/par_2";
        ParsedEli p = EsbirkaEliParser.parse(bogusFragment);
        assertThat(p.isValid()).isFalse();
    }

    @Test
    void parse_yearNotInteger_returnsInvalid() {
        String url = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/twentythousand/361";
        ParsedEli p = EsbirkaEliParser.parse(url);
        assertThat(p.isValid()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"par", "odst", "pism", "bod", "ppc", "dil", "hlava", "oddil", "cast", "frag"})
    void parse_allTenKinds_extractsKindCorrectly(String kind) {
        String url = CANONICAL_VERSION + "/dokument/norma/" + kind + "_1";

        ParsedEli p = EsbirkaEliParser.parse(url);

        assertThat(p.isValid()).isTrue();
        assertThat(p.level()).isEqualTo(ParsedEli.Level.FRAGMENT);
        assertThat(p.fragmentSegments()).hasSize(1);
        assertThat(p.fragmentSegments().get(0).kind()).isEqualTo(kind);
        assertThat(p.fragmentSegments().get(0).number()).isEqualTo("1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"norma", "poznamkypodcarou", "postfix", "novela", "prilohy", "prefix", "zaver"})
    void parse_containerRoot_isValidFragmentWithNoSegments(String container) {
        String url = CANONICAL_VERSION + "/dokument/" + container;

        ParsedEli p = EsbirkaEliParser.parse(url);

        assertThat(p.isValid()).as("container root must resolve: " + container).isTrue();
        assertThat(p.level()).isEqualTo(ParsedEli.Level.FRAGMENT);
        assertThat(p.isContainerRoot()).isTrue();
        assertThat(p.isDocumentRoot()).isFalse();
        assertThat(p.container()).isEqualTo(container);
        assertThat(p.fragmentSegments()).isEmpty();
        assertThat(p.fragmentIri()).isEqualTo(url);
        assertThat(p.versionIri()).isEqualTo(CANONICAL_VERSION);
        assertThat(p.lawIri()).isEqualTo(CANONICAL_LAW);
    }

    @ParameterizedTest
    @ValueSource(strings = {"poznamkypodcarou", "postfix", "novela", "prilohy"})
    void parse_nonNormaContainerWithFragment_isValid(String container) {
        String url = CANONICAL_VERSION + "/dokument/" + container + "/par_2";

        ParsedEli p = EsbirkaEliParser.parse(url);

        assertThat(p.isValid()).as("non-norma container must resolve: " + container).isTrue();
        assertThat(p.level()).isEqualTo(ParsedEli.Level.FRAGMENT);
        assertThat(p.isContainerRoot()).isFalse();
        assertThat(p.container()).isEqualTo(container);
        assertThat(p.fragmentSegments()).hasSize(1);
        assertThat(p.fragmentSegments().get(0)).isEqualTo(new ParsedEli.FragmentSegment("par", "2"));
    }

    @Test
    void parse_containerWithSiblingSuffix_stripsSuffixFromContainer() {
        ParsedEli p = EsbirkaEliParser.parse(CANONICAL_VERSION + "/dokument/prilohy:4");

        assertThat(p.isValid()).isTrue();
        assertThat(p.container()).isEqualTo("prilohy");
        assertThat(p.isContainerRoot()).isTrue();
    }

    /**
     * {@code <versionIri>/dokument} is the document root — a real, typed node with its own
     * {@code pořadí} and children, so it resolves rather than being rejected as a truncated IRI.
     */
    @Test
    void parse_documentRoot_isValidFragmentWithNoContainer() {
        String url = CANONICAL_VERSION + "/dokument";

        ParsedEli p = EsbirkaEliParser.parse(url);

        assertThat(p.isValid()).isTrue();
        assertThat(p.level()).isEqualTo(ParsedEli.Level.FRAGMENT);
        assertThat(p.isDocumentRoot()).isTrue();
        assertThat(p.isContainerRoot()).isFalse();
        assertThat(p.container()).isNull();
        assertThat(p.fragmentSegments()).isEmpty();
        assertThat(p.fragmentIri()).isEqualTo(url);
        assertThat(p.versionIri()).isEqualTo(CANONICAL_VERSION);
    }

    /** The promulgated-text version ({@code VYHLZNE}) is a real version whose date is not a date. */
    @Test
    void parse_documentRootOnZeroDateVersion_isValid() {
        String url = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2024/23/0000-00-00/dokument";

        ParsedEli p = EsbirkaEliParser.parse(url);

        assertThat(p.isValid()).isTrue();
        assertThat(p.isDocumentRoot()).isTrue();
        assertThat(p.versionDate()).isNull();
        assertThat(p.fragmentIri()).isEqualTo(url);
    }

    @Test
    void parse_nonDokumentSixthSegment_returnsInvalid() {
        ParsedEli p = EsbirkaEliParser.parse(CANONICAL_VERSION + "/neco");
        assertThat(p.isValid()).isFalse();
    }

    @Test
    void parse_preservesOriginalUrlVerbatim() {
        String original = "https://opendata.eselpoint.cz/esel-esb/eli/cz/sb/2000/361";
        ParsedEli p = EsbirkaEliParser.parse(original);
        assertThat(p.originalUrl()).isEqualTo(original);
    }
}
