package com.dia.ismdtoolbackend.models.concept;

import tools.jackson.databind.DatabindException;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        assertThat(model.getAltName().get("cs")).isEqualTo(List.of("Obec"));
    }

    @Test
    void listPerLanguage_keepsEveryLabel() throws Exception {
        AltNameModel model = read("{\"altName\":{\"cs\":[\"Obec\",\"Municipalita\"]}}");

        assertThat(model.getAltName().get("cs")).isEqualTo(List.of("Obec", "Municipalita"));
    }

    @Test
    void mixedShapesAcrossLanguages_bothRead() throws Exception {
        AltNameModel model = read("{\"altName\":{\"cs\":[\"Obec\",\"Municipalita\"],\"en\":\"Municipality\"}}");

        assertThat(model.getAltName().get("cs")).isEqualTo(List.of("Obec", "Municipalita"));
        assertThat(model.getAltName().get("en")).isEqualTo(List.of("Municipality"));
    }

    @Test
    void blankAndNullValues_areDropped() throws Exception {
        AltNameModel model = read("{\"altName\":{\"cs\":[\"Obec\",\"  \",null],\"en\":\"\"}}");

        assertThat(model.getAltName().get("cs")).isEqualTo(List.of("Obec"));
        assertThat(model.getAltName().get("en")).as("an all-blank language is omitted entirely").isNull();
    }

    @Test
    void valuesAreTrimmed() throws Exception {
        AltNameModel model = read("{\"altName\":{\"cs\":[\"  Obec  \"]}}");

        assertThat(model.getAltName().get("cs")).isEqualTo(List.of("Obec"));
    }

    @Test
    void nullAltName_staysNull() throws Exception {
        AltNameModel model = read("{\"altName\":null}");

        assertThat(model.getAltName()).isNull();
    }

    @Test
    void serializationAlwaysEmitsTheListForm() throws Exception {
        AltNameModel model = read("{\"altName\":{\"cs\":\"Obec\"}}");

        assertThat(mapper.writeValueAsString(model)).isEqualTo("{\"altName\":{\"cs\":[\"Obec\"]}}");
    }

    /**
     * RDF stores {@code skos:altLabel} as a set, so a repeated label collapses to one triple on write.
     * Keeping the duplicate in memory would make the next read-back differ from what was submitted, and
     * every subsequent save would re-detect a change and rewrite the property forever.
     */
    @Test
    void duplicatesWithinALanguage_areCollapsed() throws Exception {
        AltNameModel model = read("{\"altName\":{\"cs\":[\"Obec\",\"Obec\"]}}");

        assertThat(model.getAltName().get("cs")).isEqualTo(List.of("Obec"));
    }

    @Test
    void duplicatesDifferingOnlyByWhitespace_areCollapsed() throws Exception {
        AltNameModel model = read("{\"altName\":{\"cs\":[\"Obec\",\"  Obec  \"]}}");

        assertThat(model.getAltName().get("cs")).isEqualTo(List.of("Obec"));
    }

    @Test
    void firstOccurrenceOrderIsPreservedWhenCollapsing() throws Exception {
        AltNameModel model = read("{\"altName\":{\"cs\":[\"Obec\",\"Municipalita\",\"Obec\"]}}");

        assertThat(model.getAltName().get("cs")).isEqualTo(List.of("Obec", "Municipalita"));
    }

    // --- strictness: malformed values are rejected, never coerced or silently dropped -------------
    //
    // Dropping a malformed language would leave its key absent, and the edit path reads an absent key
    // as "no alt names" — silently deleting the stored labels. A 400 is the honest outcome.

    @Test
    void numberValue_isRejected() {
        assertThatThrownBy(() -> read("{\"altName\":{\"cs\":42}}")).isInstanceOf(DatabindException.class);
    }

    @Test
    void booleanValue_isRejected() {
        assertThatThrownBy(() -> read("{\"altName\":{\"cs\":true}}")).isInstanceOf(DatabindException.class);
    }

    @Test
    void nestedArrayValue_isRejected() {
        assertThatThrownBy(() -> read("{\"altName\":{\"cs\":[[\"a\",\"b\"]]}}")).isInstanceOf(DatabindException.class);
    }

    @Test
    void nestedObjectValue_isRejected() {
        assertThatThrownBy(() -> read("{\"altName\":{\"cs\":{\"x\":1}}}")).isInstanceOf(DatabindException.class);
    }

    @Test
    void numberInsideAnArray_isRejected() {
        assertThatThrownBy(() -> read("{\"altName\":{\"cs\":[\"Obec\",42]}}")).isInstanceOf(DatabindException.class);
    }

    @Test
    void nonObjectAltName_isRejected() {
        assertThatThrownBy(() -> read("{\"altName\":[\"Obec\"]}")).isInstanceOf(DatabindException.class);
    }

    /** An explicit empty object still means "clear the field" — that is a legitimate instruction. */
    @Test
    void emptyObject_isAcceptedAsAnExplicitClear() throws Exception {
        AltNameModel model = read("{\"altName\":{}}");

        assertThat(model.getAltName()).isNotNull();
        assertThat(model.getAltName()).isEmpty();
    }
}
