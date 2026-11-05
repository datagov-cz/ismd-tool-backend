package com.dia.ismdtoolbackend.utility.detail;

import com.dia.ismdtoolbackend.utility.exporter.json.ConceptData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.dia.ismdtoolbackend.constants.VocabularyConstants.DEFINICNI_OBOR;
import static com.dia.ismdtoolbackend.constants.VocabularyConstants.OBOR_HODNOT;

/**
 * Domain/range lookup over an ontology's concept list, built in one pass so per-concept
 * property/relationship extraction is a map hit instead of a full rescan.
 *
 * <p>Insertion order of the underlying concept list is preserved within each bucket, and a
 * relationship whose domain and range are the same concept appears once — both matching the
 * single-pass scan this replaces.
 */
record DetailMemberIndex(Map<String, List<Map<String, Object>>> propertiesByDomain,
                         Map<String, List<Map<String, Object>>> relationshipsByConcept) {

    @SuppressWarnings("unchecked")
    static DetailMemberIndex build(ConceptData conceptData) {
        Map<String, List<Map<String, Object>>> byDomain = new HashMap<>();
        Map<String, List<Map<String, Object>>> byConcept = new HashMap<>();

        for (Map<String, Object> conceptMap : conceptData.getConcepts()) {
            List<String> types = (List<String>) conceptMap.get("typ");
            if (types == null) {
                continue;
            }
            Object domainObj = conceptMap.get(DEFINICNI_OBOR);
            String domain = domainObj instanceof String s ? s : null;

            if (types.contains("Vlastnost")) {
                if (domain != null) {
                    byDomain.computeIfAbsent(domain, k -> new ArrayList<>()).add(conceptMap);
                }
            } else if (types.contains("Vztah")) {
                Object rangeObj = conceptMap.get(OBOR_HODNOT);
                String range = rangeObj instanceof String s ? s : null;

                if (domain != null) {
                    byConcept.computeIfAbsent(domain, k -> new ArrayList<>()).add(conceptMap);
                }
                // Only when range differs, so a self-referencing vztah is not listed twice.
                if (range != null && !range.equals(domain)) {
                    byConcept.computeIfAbsent(range, k -> new ArrayList<>()).add(conceptMap);
                }
            }
        }
        return new DetailMemberIndex(byDomain, byConcept);
    }

    List<Map<String, Object>> propertiesWithDomain(String conceptIri) {
        return propertiesByDomain.getOrDefault(conceptIri, List.of());
    }

    List<Map<String, Object>> relationshipsTouching(String conceptIri) {
        return relationshipsByConcept.getOrDefault(conceptIri, List.of());
    }
}
