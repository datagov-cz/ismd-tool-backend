package com.dia.ismdtoolbackend.utility;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class UtilityMethodsUnitTest {

    private static final String AGENDA = "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/";
    private static final String ISVS = "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/isvs/";

    @ParameterizedTest
    @ValueSource(chars = {'a', 'Z', '_', '7', ':'})
    void isValidFirstChar_accepts(char ch) {
        assertThat(UtilityMethods.isValidFirstChar(ch)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(chars = {'-', '.', ' ', '!'})
    void isValidFirstChar_rejects(char ch) {
        assertThat(UtilityMethods.isValidFirstChar(ch)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(chars = {'a', 'Z', '_', '5', '-', '.', ':', '·', '\u0300', '\u036F', '\u203F', '\u2040'})
    void isValidSubsequentChar_accepts(char ch) {
        assertThat(UtilityMethods.isValidSubsequentChar(ch)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(chars = {' ', '!', '@', '\u02FF', '\u0375', '\u203E', '\u2041'})
    void isValidSubsequentChar_rejects(char ch) {
        assertThat(UtilityMethods.isValidSubsequentChar(ch)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(chars = {'a', 'Z', '_', 'ñ', 'é'})
    void isLetterOrUnderscore_accepts(char ch) {
        assertThat(UtilityMethods.isLetterOrUnderscore(ch)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(chars = {'5', '-', ' '})
    void isLetterOrUnderscore_rejects(char ch) {
        assertThat(UtilityMethods.isLetterOrUnderscore(ch)).isFalse();
    }

    @Test
    void isValidUrl() {
        assertThat(UtilityMethods.isValidUrl("https://example.com")).isTrue();
        assertThat(UtilityMethods.isValidUrl("http://localhost:8080")).isTrue();
        assertThat(UtilityMethods.isValidUrl("ftp://server.edu/path/file.txt")).isTrue();

        assertThat(UtilityMethods.isValidUrl("not a url")).as("unparseable").isFalse();
        assertThat(UtilityMethods.isValidUrl("http:/missing-slash")).as("no host").isFalse();
        assertThat(UtilityMethods.isValidUrl("example.com/path")).as("no scheme").isFalse();
        assertThat(UtilityMethods.isValidUrl(null)).isFalse();
    }

    @Test
    void ensureNamespaceEndsWithDelimiter() {
        assertThat(UtilityMethods.ensureNamespaceEndsWithDelimiter("http://example.com/")).isEqualTo("http://example.com/");
        assertThat(UtilityMethods.ensureNamespaceEndsWithDelimiter("http://example.com#")).isEqualTo("http://example.com#");
        assertThat(UtilityMethods.ensureNamespaceEndsWithDelimiter("http://example.com")).isEqualTo("http://example.com/");
        assertThat(UtilityMethods.ensureNamespaceEndsWithDelimiter("")).isEqualTo("/");
    }

    @ParameterizedTest
    @CsvSource({
            "Hello World, hello-world",
            "Test 123, test-123",
            "a@b#c, a-b-c",
            "!@#hello!@#, hello",
            "hello!!!world, hello-world",
            "@123, 123",
            "!@#$%^, unnamed"
    })
    void sanitizeForIRI(String input, String expected) {
        assertThat(UtilityMethods.sanitizeForIRI(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void sanitizeForIRI_nullOrEmpty_isUnnamed(String input) {
        assertThat(UtilityMethods.sanitizeForIRI(input)).isEqualTo("unnamed");
    }

    @ParameterizedTest
    @CsvSource({
            "https://e-sbirka.gov.cz/eli/cz/sb/2005/348/2026-01-01/dokument/norma/cast_1/par_2/odst_1/, https://e-sbirka.gov.cz/eli/cz/sb/2005/348/2026-01-01/dokument/norma/cast_1/par_2/odst_1",
            "https://e-sbirka.gov.cz/eli/cz/sb/2005/348/2026-01-01/dokument/norma/cast_1/par_2/odst_1, https://e-sbirka.gov.cz/eli/cz/sb/2005/348/2026-01-01/dokument/norma/cast_1/par_2/odst_1",
            "https://slovník.gov.cz/agendový/104/pojem/adresa//, https://slovník.gov.cz/agendový/104/pojem/adresa",
            "http://www.w3.org/2000/01/rdf-schema#Literal, http://www.w3.org/2000/01/rdf-schema#Literal",
            "https://, https://",
            "'', ''"
    })
    void removeTrailingSlash(String input, String expected) {
        assertThat(UtilityMethods.removeTrailingSlash(input)).isEqualTo(expected);
    }

    @Test
    void removeTrailingSlash_null() {
        assertThat(UtilityMethods.removeTrailingSlash(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"104", "A104", " A104/ ", AGENDA + "A104", AGENDA + "A104/", "https://jinde.cz/agenda/A104"})
    void isValidAgendaValue_accepts(String value) {
        assertThat(UtilityMethods.isValidAgendaValue(value)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "B104", "A", "http://jinde.cz/agenda/A104", AGENDA + "104"})
    void isValidAgendaValue_rejects(String value) {
        assertThat(UtilityMethods.isValidAgendaValue(value)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "104, " + AGENDA + "A104",
            "A104, " + AGENDA + "A104",
            "' A104/ ', " + AGENDA + "A104",
            AGENDA + "A104/, " + AGENDA + "A104",
            "https://jinde.cz/agenda/A104, " + AGENDA + "A104",
            "B104, B104"
    })
    void transformAgendaValue(String input, String expected) {
        assertThat(UtilityMethods.transformAgendaValue(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void transformAgendaValue_blank_isReturnedAsIs(String input) {
        assertThat(UtilityMethods.transformAgendaValue(input)).isEqualTo(input);
    }

    @ParameterizedTest
    @ValueSource(strings = {"326", " 326/ ", ISVS + "326", ISVS + "326/", "https://jinde.cz/isvs/326"})
    void isValidAISValue_accepts(String value) {
        assertThat(UtilityMethods.isValidAISValue(value)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "A326", "http://jinde.cz/isvs/326", ISVS + "abc"})
    void isValidAISValue_rejects(String value) {
        assertThat(UtilityMethods.isValidAISValue(value)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "326, " + ISVS + "326",
            "' 326/ ', " + ISVS + "326",
            ISVS + "326/, " + ISVS + "326",
            "https://jinde.cz/isvs/326, " + ISVS + "326",
            "A326, A326"
    })
    void transformAISValue(String input, String expected) {
        assertThat(UtilityMethods.transformAISValue(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void transformAISValue_blank_isReturnedAsIs(String input) {
        assertThat(UtilityMethods.transformAISValue(input)).isEqualTo(input);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/pojem/a",
            "  https://example.com/a  ",
            "urn:isbn:0451450523",
            "mailto:someone@example.com"
    })
    void isValidIRI_accepts(String iri) {
        assertThat(UtilityMethods.isValidIRI(iri)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "https://example.com/some text", "ne-uvedeno", "INVALID", "n/a", "TBD", "todo",
            "https://example.com/a b", "https://example.com/a\tb", "https://example.com/a\nb",
            "https://example.com/a\rb",
            "no-scheme", "1http://example.com", "https://",
            "https://example.com/<a>", "https://example.com/\"a\"", "https://example.com/{a}",
            "https://example.com/a|b", "https://example.com/a\\b", "https://example.com/a^b",
            "https://example.com/a`b"
    })
    void isValidIRI_rejects(String iri) {
        assertThat(UtilityMethods.isValidIRI(iri)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "https://example.com/pojem/adresa, adresa",
            "http://www.w3.org/2000/01/rdf-schema#Literal, Literal",
            "https://example.com/a#b/c, c",
            "https://example.com/pojem/, https://example.com/pojem/",
            "adresa, adresa"
    })
    void extractNameFromIRI(String iri, String expected) {
        assertThat(UtilityMethods.extractNameFromIRI(iri)).isEqualTo(expected);
    }

    @Test
    void extractNameFromIRI_null() {
        assertThat(UtilityMethods.extractNameFromIRI(null)).isNull();
    }
}
