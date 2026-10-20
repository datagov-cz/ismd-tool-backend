package com.dia.ismdtoolbackend.utility;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for {@link UtilityMethods}.
 *
 * @see UtilityMethods
 */
class UtilityMethodsUnitTest {

    @Test
    void testIsValidFirstChar() {
        // Valid first characters: letters, digits, underscore, colon
        assertTrue(UtilityMethods.isValidFirstChar('a'));
        assertTrue(UtilityMethods.isValidFirstChar('Z'));
        assertTrue(UtilityMethods.isValidFirstChar('_'));
        assertTrue(UtilityMethods.isValidFirstChar('7'));
        assertTrue(UtilityMethods.isValidFirstChar(':'));

        // Invalid first characters
        assertFalse(UtilityMethods.isValidFirstChar('-'));
        assertFalse(UtilityMethods.isValidFirstChar('.'));
        assertFalse(UtilityMethods.isValidFirstChar(' '));
        assertFalse(UtilityMethods.isValidFirstChar('!'));
    }

    @Test
    void testIsValidSubsequentChar() {
        // Valid subsequent characters
        assertTrue(UtilityMethods.isValidSubsequentChar('a'));
        assertTrue(UtilityMethods.isValidSubsequentChar('Z'));
        assertTrue(UtilityMethods.isValidSubsequentChar('_'));
        assertTrue(UtilityMethods.isValidSubsequentChar('5'));
        assertTrue(UtilityMethods.isValidSubsequentChar('-'));
        assertTrue(UtilityMethods.isValidSubsequentChar('.'));
        assertTrue(UtilityMethods.isValidSubsequentChar(':'));
        assertTrue(UtilityMethods.isValidSubsequentChar('·'));

        // Test Unicode combining characters (U+0300 to U+036F)
        assertTrue(UtilityMethods.isValidSubsequentChar('̀'));  // Combining grave accent
        assertTrue(UtilityMethods.isValidSubsequentChar('ͯ'));  // Combining Latin small letter x

        // Test Unicode joining characters (U+203F to U+2040)
        assertTrue(UtilityMethods.isValidSubsequentChar('‿'));  // Undertie
        assertTrue(UtilityMethods.isValidSubsequentChar('⁀'));  // Character tie

        // Invalid subsequent characters
        assertFalse(UtilityMethods.isValidSubsequentChar(' '));
        assertFalse(UtilityMethods.isValidSubsequentChar('!'));
        assertFalse(UtilityMethods.isValidSubsequentChar('@'));
    }

    @Test
    void testIsLetterOrUnderscore() {
        // Valid: letters and underscore
        assertTrue(UtilityMethods.isLetterOrUnderscore('a'));
        assertTrue(UtilityMethods.isLetterOrUnderscore('Z'));
        assertTrue(UtilityMethods.isLetterOrUnderscore('_'));
        assertTrue(UtilityMethods.isLetterOrUnderscore('ñ'));
        assertTrue(UtilityMethods.isLetterOrUnderscore('é'));

        // Invalid: not letters or underscore
        assertFalse(UtilityMethods.isLetterOrUnderscore('5'));
        assertFalse(UtilityMethods.isLetterOrUnderscore('-'));
        assertFalse(UtilityMethods.isLetterOrUnderscore(' '));
    }

    @Test
    void testIsValidUrl() {
        // Valid URLs
        assertTrue(UtilityMethods.isValidUrl("https://example.com"));
        assertTrue(UtilityMethods.isValidUrl("http://localhost:8080"));
        assertTrue(UtilityMethods.isValidUrl("ftp://server.edu/path/file.txt"));

        // Invalid URLs
        assertFalse(UtilityMethods.isValidUrl("not a url"));
        assertFalse(UtilityMethods.isValidUrl("http:/missing-slash"));
        assertFalse(UtilityMethods.isValidUrl("://no-protocol.com"));
        assertFalse(UtilityMethods.isValidUrl(null));
    }

    @Test
    void testEnsureNamespaceEndsWithDelimiter() {
        // Already has delimiter
        assertEquals("http://example.com/", UtilityMethods.ensureNamespaceEndsWithDelimiter("http://example.com/"));
        assertEquals("http://example.com#", UtilityMethods.ensureNamespaceEndsWithDelimiter("http://example.com#"));

        // Needs delimiter
        assertEquals("http://example.com/", UtilityMethods.ensureNamespaceEndsWithDelimiter("http://example.com"));

        // Edge cases
        assertEquals("/", UtilityMethods.ensureNamespaceEndsWithDelimiter(""));
    }

    @Test
    void testSanitizeForIRI() {
        // Basic sanitization
        assertEquals("hello-world", UtilityMethods.sanitizeForIRI("Hello World"));
        assertEquals("test-123", UtilityMethods.sanitizeForIRI("Test 123"));

        // Special characters
        assertEquals("a-b-c", UtilityMethods.sanitizeForIRI("a@b#c"));

        // Leading/trailing special characters
        assertEquals("hello", UtilityMethods.sanitizeForIRI("!@#hello!@#"));

        // Multiple consecutive special characters
        assertEquals("hello-world", UtilityMethods.sanitizeForIRI("hello!!!world"));

        // First character invalid
        assertEquals("123", UtilityMethods.sanitizeForIRI("@123"));

        // Edge cases
        assertEquals("unnamed", UtilityMethods.sanitizeForIRI(null));
        assertEquals("unnamed", UtilityMethods.sanitizeForIRI(""));
        assertEquals("unnamed", UtilityMethods.sanitizeForIRI("!@#$%^"));
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
    void testRemoveTrailingSlash(String input, String expected) {
        assertEquals(expected, UtilityMethods.removeTrailingSlash(input));
    }

    @Test
    void testRemoveTrailingSlash_Null() {
        assertNull(UtilityMethods.removeTrailingSlash(null));
    }

    @Test
    void testTrailingSlashIsRemovedFromDerivedIRIs() {
        assertTrue(UtilityMethods.isValidAgendaValue("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A104/"));
        assertEquals("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A104",
                UtilityMethods.transformAgendaValue("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A104/"));

        assertTrue(UtilityMethods.isValidAISValue("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/isvs/326/"));
        assertEquals("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/isvs/326",
                UtilityMethods.transformAISValue("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/isvs/326/"));
    }

    @Test
    void testTrailingSlashIsRemovedFromConceptIdentifier() {
        URIGenerator uriGenerator = new URIGenerator();
        uriGenerator.setEffectiveNamespace("https://example.com/");
        uriGenerator.setVocabularyName("slovnik");

        assertEquals("https://example.com/slovnik/pojem/adresa",
                uriGenerator.generateConceptURI("Adresa", "https://example.com/slovnik/pojem/adresa/"));
        assertEquals("https://example.com/slovnik/pojem/adresa",
                uriGenerator.generateConceptURI("Adresa", "https://example.com/slovnik/pojem/adresa"));
        assertEquals("https://example.com/slovnik/pojem/adresa",
                uriGenerator.generateConceptURI("Adresa", "https://jinde.cz/adresa/"));
    }
}
