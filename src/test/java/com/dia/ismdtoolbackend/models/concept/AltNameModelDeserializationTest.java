package com.dia.ismdtoolbackend.models.concept;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
}
