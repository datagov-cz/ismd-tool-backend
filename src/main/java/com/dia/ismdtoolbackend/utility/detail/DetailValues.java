package com.dia.ismdtoolbackend.utility.detail;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Coercions of the raw export-map values the detail models are built from. */
@Slf4j
final class DetailValues {

    private DetailValues() {
    }

    static String extractFirstAvailableName(Map<String, String> nameMap) {
        if (nameMap == null || nameMap.isEmpty()) {
            return null;
        }

        if (nameMap.containsKey("cs")) {
            return nameMap.get("cs");
        }

        return nameMap.values().iterator().next();
    }

    /**
     * Coerce a raw multilingual property value from the {@code ConceptJsonMapper} export map
     * into a strict {@code Map<String, String>} (lang → single label).
     *
     * <p>{@code ConceptLabelJsonMapper.addValueToLanguageMap} promotes a language entry to a
     * {@code List} when a concept carries more than one value for the same language
     * (e.g. two {@code skos:prefLabel @cs}). The detail contract is single-valued, so we
     * keep the first String and warn-log the dropped extras. Without this, the raw
     * {@code (Map<String, String>)} cast slips a {@code List} past type erasure and Jackson
     * later throws {@link ClassCastException} while serializing the whole ontology detail.
     */
    static Map<String, String> coerceToStringMap(Object value, String conceptIri, String field) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String lang)) {
                continue;
            }
            Object v = e.getValue();
            if (v instanceof String s) {
                out.put(lang, s);
            } else if (v instanceof List<?> list) {
                String first = list.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .findFirst()
                        .orElse(null);
                if (first != null) {
                    out.put(lang, first);
                    if (list.size() > 1) {
                        log.warn("Concept {} has {} '{}' values for lang '{}'; keeping first, dropping {} extra(s)",
                                conceptIri, list.size(), field, lang, list.size() - 1);
                    }
                }
            }
        }
        return out.isEmpty() ? null : out;
    }

    static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    static Map<String, String> asMultilingualMap(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() instanceof String key && e.getValue() instanceof String val) {
                out.put(key, val);
            }
        }
        return out.isEmpty() ? null : out;
    }

    static String extractStringFromValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof String) {
                return (String) list.get(0);
            }

        return null;
    }
}
