package com.dia.ismdtoolbackend.service.impl;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Assembled list-item metadata for one ontology. {@code nameMap} is the single-entry
 * {@code {cs: <label>}} map (empty when unlabelled); {@code descriptionMap} is the
 * multilingual description map (empty when none). Dates are the raw literal strings or
 * null. The {@link Builder} folds the multi-row SELECT result.
 */
record NkdListItemMeta(Map<String, String> nameMap,
                       Map<String, String> descriptionMap,
                       String creationDate,
                       String modificationDate) {

    static final String DEFAULT_LANG = "cs";

    static final class Builder {
        private String label;       // rdfs:label (wins)
        private String prefLabel;   // skos:prefLabel (fallback)
        private final Map<String, String> description = new LinkedHashMap<>();
        private String creationDate;
        private String modificationDate;

        void offerLabel(String labelVal, String prefLabelVal) {
            if (label == null && labelVal != null && !labelVal.trim().isEmpty()) {
                label = labelVal;
            }
            if (prefLabel == null && prefLabelVal != null && !prefLabelVal.trim().isEmpty()) {
                prefLabel = prefLabelVal;
            }
        }

        void putDescription(String lang, String value) {
            // First value per lang wins — deterministic, matches single-pass extractor.
            description.putIfAbsent(lang, value);
        }

        void offerCreation(String dateTime, String date) {
            if (creationDate == null) {
                creationDate = firstNonBlank(dateTime, date);
            }
        }

        void offerModification(String dateTime, String date) {
            if (modificationDate == null) {
                modificationDate = firstNonBlank(dateTime, date);
            }
        }

        private static String firstNonBlank(String a, String b) {
            if (a != null && !a.trim().isEmpty()) return a;
            if (b != null && !b.trim().isEmpty()) return b;
            return null;
        }

        NkdListItemMeta build() {
            String name = (label != null) ? label : prefLabel;
            Map<String, String> nameMap = (name == null || name.trim().isEmpty())
                    ? Map.of()
                    : Map.of(DEFAULT_LANG, name);
            return new NkdListItemMeta(nameMap, Map.copyOf(description), creationDate, modificationDate);
        }
    }
}
