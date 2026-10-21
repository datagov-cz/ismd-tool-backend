package com.dia.ismdtoolbackend.utility.detail;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Coercions of raw export-map values in {@link DetailValues}. */
class DetailValuesTest {

    private static Map<Object, Object> rawMap(Object... keyValuePairs) {
        Map<Object, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }

    @Nested
    class FirstAvailableName {

        @Test
        void czechWinsOverOtherLanguages() {
            assertThat(DetailValues.extractFirstAvailableName(Map.of("en", "Person", "cs", "Osoba"))).isEqualTo("Osoba");
        }

        @Test
        void withoutCzechTheFirstValueIsUsed() {
            Map<String, String> names = new LinkedHashMap<>();
            names.put("en", "Person");
            names.put("de", "Person (de)");

            assertThat(DetailValues.extractFirstAvailableName(names)).isEqualTo("Person");
        }

        @Test
        void nullOrEmptyHasNoName() {
            assertThat(DetailValues.extractFirstAvailableName(null)).isNull();
            assertThat(DetailValues.extractFirstAvailableName(Map.of())).isNull();
        }
    }

    @Nested
    class CoerceToStringMap {

        @Test
        void listValuesKeepTheirFirstString() {
            Object raw = rawMap("cs", List.of("První", "Druhý"), "en", List.of(1, "One"), "de", "Eins");

            assertThat(DetailValues.coerceToStringMap(raw, "iri", "název"))
                    .containsExactly(Map.entry("cs", "První"), Map.entry("en", "One"), Map.entry("de", "Eins"));
        }

        @Test
        void entriesWithoutAUsableValueOrStringKeyAreDropped() {
            Object raw = rawMap(1, "číselný klíč", "cs", List.of(1, 2), "en", 42, "de", List.of());

            assertThat(DetailValues.coerceToStringMap(raw, "iri", "název")).isNull();
        }

        @Test
        void nonMapsAndEmptyMapsAreNull() {
            assertThat(DetailValues.coerceToStringMap("text", "iri", "název")).isNull();
            assertThat(DetailValues.coerceToStringMap(null, "iri", "název")).isNull();
            assertThat(DetailValues.coerceToStringMap(new HashMap<>(), "iri", "název")).isNull();
        }
    }

    @Nested
    class MultilingualMap {

        @Test
        void keepsOnlyStringToStringEntries() {
            Object raw = rawMap("cs", "Popis", "en", List.of("x"), 3, "y");

            assertThat(DetailValues.asMultilingualMap(raw)).containsExactly(Map.entry("cs", "Popis"));
        }

        @Test
        void nothingUsableIsNull() {
            assertThat(DetailValues.asMultilingualMap(rawMap("cs", 1))).isNull();
            assertThat(DetailValues.asMultilingualMap(Map.of())).isNull();
            assertThat(DetailValues.asMultilingualMap(List.of("cs"))).isNull();
        }
    }

    @Test
    void stringFromValueTakesAStringOrTheFirstStringOfAList() {
        assertThat(DetailValues.extractStringFromValue("A1")).isEqualTo("A1");
        assertThat(DetailValues.extractStringFromValue(List.of("A1", "A2"))).isEqualTo("A1");
        assertThat(DetailValues.extractStringFromValue(List.of(1, "A2"))).isNull();
        assertThat(DetailValues.extractStringFromValue(List.of())).isNull();
        assertThat(DetailValues.extractStringFromValue(42)).isNull();
        assertThat(DetailValues.extractStringFromValue(null)).isNull();
    }

    @Test
    void asStringAndNullToEmpty() {
        assertThat(DetailValues.asString("x")).isEqualTo("x");
        assertThat(DetailValues.asString(1)).isNull();
        assertThat(DetailValues.nullToEmpty(null)).isEmpty();
        assertThat(DetailValues.nullToEmpty(List.of("a"))).containsExactly("a");
    }
}
