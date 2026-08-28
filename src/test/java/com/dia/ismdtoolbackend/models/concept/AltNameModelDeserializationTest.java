package com.dia.ismdtoolbackend.models.concept;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tolerant reader on {@link AltNameModel}: a language key may arrive as a bare string (the legacy
 * shape the FE still sends) or as a list, and both must round-trip without losing labels.
 */
class AltNameModelDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private AltNameModel read(String json) throws Exception {
        return mapper.readValue(json, AltNameModel.class);
    }

    @Test
    void legacyStringPerLanguage_isWrappedIntoASingleElementList() throws Exception {
        AltNameModel model = read("{\"altName\":{\"cs\":\"Obec\"}}");

        assertEquals(List.of("Obec"), model.getAltName().get("cs"));
    }

    @Test
    void listPerLanguage_keepsEveryLabel() throws Exception {
        AltNameModel model = read("{\"altName\":{\"cs\":[\"Obec\",\"Municipalita\"]}}");

        assertEquals(List.of("Obec", "Municipalita"), model.getAltName().get("cs"));
    }

    @Test
    void mixedShapesAcrossLanguages_bothRead() throws Exception {
        AltNameModel model = read("{\"altName\":{\"cs\":[\"Obec\",\"Municipalita\"],\"en\":\"Municipality\"}}");

        assertEquals(List.of("Obec", "Municipalita"), model.getAltName().get("cs"));
        assertEquals(List.of("Municipality"), model.getAltName().get("en"));
    }

    @Test
    void blankAndNullValues_areDropped() throws Exception {
        AltNameModel model = read("{\"altName\":{\"cs\":[\"Obec\",\"  \",null],\"en\":\"\"}}");

        assertEquals(List.of("Obec"), model.getAltName().get("cs"));
        assertTrue(model.getAltName().get("en") == null, "an all-blank language is omitted entirely");
    }

    @Test
    void valuesAreTrimmed() throws Exception {
        AltNameModel model = read("{\"altName\":{\"cs\":[\"  Obec  \"]}}");

        assertEquals(List.of("Obec"), model.getAltName().get("cs"));
    }

    @Test
    void nullAltName_staysNull() throws Exception {
        AltNameModel model = read("{\"altName\":null}");

        assertNull(model.getAltName());
    }

    @Test
    void serializationAlwaysEmitsTheListForm() throws Exception {
        AltNameModel model = read("{\"altName\":{\"cs\":\"Obec\"}}");

        assertEquals("{\"altName\":{\"cs\":[\"Obec\"]}}", mapper.writeValueAsString(model));
    }

    /**
     * RDF stores {@code skos:altLabel} as a set, so a repeated label collapses to one triple on write.
     * Keeping the duplicate in memory would make the next read-back differ from what was submitted, and
     * every subsequent save would re-detect a change and rewrite the property forever.
     */
    @Test
    void duplicatesWithinALanguage_areCollapsed() throws Exception {
        AltNameModel model = read("{\"altName\":{\"cs\":[\"Obec\",\"Obec\"]}}");

        assertEquals(List.of("Obec"), model.getAltName().get("cs"));
    }

    @Test
    void duplicatesDifferingOnlyByWhitespace_areCollapsed() throws Exception {
        AltNameModel model = read("{\"altName\":{\"cs\":[\"Obec\",\"  Obec  \"]}}");

        assertEquals(List.of("Obec"), model.getAltName().get("cs"));
    }

    @Test
    void firstOccurrenceOrderIsPreservedWhenCollapsing() throws Exception {
        AltNameModel model = read("{\"altName\":{\"cs\":[\"Obec\",\"Municipalita\",\"Obec\"]}}");

        assertEquals(List.of("Obec", "Municipalita"), model.getAltName().get("cs"));
    }

    // --- strictness: malformed values are rejected, never coerced or silently dropped -------------
    //
    // Dropping a malformed language would leave its key absent, and the edit path reads an absent key
    // as "no alt names" — silently deleting the stored labels. A 400 is the honest outcome.

    @Test
    void numberValue_isRejected() {
        assertThrows(JsonMappingException.class, () -> read("{\"altName\":{\"cs\":42}}"));
    }

    @Test
    void booleanValue_isRejected() {
        assertThrows(JsonMappingException.class, () -> read("{\"altName\":{\"cs\":true}}"));
    }

    @Test
    void nestedArrayValue_isRejected() {
        assertThrows(JsonMappingException.class, () -> read("{\"altName\":{\"cs\":[[\"a\",\"b\"]]}}"));
    }

    @Test
    void nestedObjectValue_isRejected() {
        assertThrows(JsonMappingException.class, () -> read("{\"altName\":{\"cs\":{\"x\":1}}}"));
    }

    @Test
    void numberInsideAnArray_isRejected() {
        assertThrows(JsonMappingException.class, () -> read("{\"altName\":{\"cs\":[\"Obec\",42]}}"));
    }

    @Test
    void nonObjectAltName_isRejected() {
        assertThrows(JsonMappingException.class, () -> read("{\"altName\":[\"Obec\"]}"));
    }

    /** An explicit empty object still means "clear the field" — that is a legitimate instruction. */
    @Test
    void emptyObject_isAcceptedAsAnExplicitClear() throws Exception {
        AltNameModel model = read("{\"altName\":{}}");

        assertNotNull(model.getAltName());
        assertTrue(model.getAltName().isEmpty());
    }
}
