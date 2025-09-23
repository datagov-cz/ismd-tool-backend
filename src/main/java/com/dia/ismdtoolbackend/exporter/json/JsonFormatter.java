package com.dia.ismdtoolbackend.exporter.json;

import com.dia.exceptions.JsonExportException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.*;

@Slf4j
public class JsonFormatter {

    private final ObjectMapper objectMapper;

    public JsonFormatter() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public String formatAsJson(ModelStructure structure, ConceptData conceptData) {
        try {
            log.debug("Formatting JSON structure");

            Map<String, Object> root = new LinkedHashMap<>();

            addModelMetadata(root, structure);

            root.put(JSON_POJMY, orderConceptFields(conceptData.getConcepts()));

            Map<String, Object> filteredRoot = filterEmptyValues(root);

            return objectMapper.writeValueAsString(filteredRoot);

        } catch (Exception e) {
            log.error("Error formatting JSON: {}", e.getMessage(), e);
            throw new JsonExportException("Failed to format JSON: " + e.getMessage(), e);
        }
    }

    private void addModelMetadata(Map<String, Object> root, ModelStructure structure) {
        root.put(JSON_CONTEXT, CONTEXT_JSONLD);
        root.put(JSON_IRI, structure.getOntologyIRI());
        root.put(JSON_TYP, createTypeArray());

        if (structure.getModelName() != null && !structure.getModelName().isEmpty()) {
            Map<String, String> nameObj = new LinkedHashMap<>();
            nameObj.put("cs", structure.getModelName());
            root.put(NAZEV, nameObj);
        } else {
            root.put(NAZEV, createEmptyMultilingualField());
        }

        if (structure.getModelDescription() != null && !structure.getModelDescription().isEmpty()) {
            Map<String, String> descObj = new LinkedHashMap<>();
            descObj.put("cs", structure.getModelDescription());
            root.put(POPIS, descObj);
        } else {
            root.put(POPIS, createEmptyMultilingualField());
        }

        if (structure.getCreationDate() != null) {
            root.put(OKAMZIK_VYTVORENI, structure.getCreationDate());
        }

        if (structure.getModificationDate() != null) {
            root.put(OKAMZIK_POSLEDNI_ZMENY, structure.getModificationDate());
        }
    }

    private List<String> createTypeArray() {
        return Arrays.asList(TYPE_SLOVNIK, TYPE_TEZAURUS, TYPE_KM);
    }

    private Map<String, String> createEmptyMultilingualField() {
        Map<String, String> emptyField = new LinkedHashMap<>();
        emptyField.put("cs", "");
        return emptyField;
    }

    private List<Map<String, Object>> orderConceptFields(List<Map<String, Object>> concepts) {
        List<Map<String, Object>> orderedConcepts = new ArrayList<>();

        for (Map<String, Object> concept : concepts) {
            orderedConcepts.add(orderSingleConceptFields(concept));
        }

        return orderedConcepts;
    }

    private Map<String, Object> orderSingleConceptFields(Map<String, Object> concept) {
        Map<String, Object> orderedConcept = new LinkedHashMap<>();

        for (String fieldName : CONCEPT_FIELD_ORDER) {
            if (concept.containsKey(fieldName)) {
                orderedConcept.put(fieldName, concept.get(fieldName));
            }
        }

        for (Map.Entry<String, Object> entry : concept.entrySet()) {
            if (!orderedConcept.containsKey(entry.getKey())) {
                orderedConcept.put(entry.getKey(), entry.getValue());
            }
        }

        return orderedConcept;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> filterEmptyValues(Map<String, Object> map) {
        Map<String, Object> filtered = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            String key = entry.getKey();

            // Enhanced logging for source properties
            if (key.contains("nelegislativní-zdroj") || key.contains("ustanovení-právního-předpisu")) {
                log.info("SOURCE PROPERTY DEBUG: Filtering field '{}' with value: {}", key, value);
            }

            if (value == null) {
                if (key.contains("nelegislativní-zdroj") || key.contains("ustanovení-právního-předpisu")) {
                    log.warn("SOURCE PROPERTY DEBUG: Removing NULL source property field: {}", key);
                }
                continue;
            }

            if (value instanceof String && ((String) value).isEmpty()) {
                if (key.contains("nelegislativní-zdroj") || key.contains("ustanovení-právního-předpisu")) {
                    log.warn("SOURCE PROPERTY DEBUG: Removing EMPTY STRING source property field: {}", key);
                }
                continue;
            }

            if (value instanceof Map) {
                Map<String, Object> mapValue = (Map<String, Object>) value;
                if (mapValue.isEmpty()) {
                    if (key.contains("nelegislativní-zdroj") || key.contains("ustanovení-právního-předpisu")) {
                        log.warn("SOURCE PROPERTY DEBUG: Removing EMPTY MAP source property field: {}", key);
                    }
                    continue;
                }

                if (isEmptyMultilingualField(mapValue)) {
                    if (key.contains("nelegislativní-zdroj") || key.contains("ustanovení-právního-předpisu")) {
                        log.warn("SOURCE PROPERTY DEBUG: Removing EMPTY MULTILINGUAL source property field: {}", key);
                    }
                    continue;
                }

                Map<String, Object> filteredMap = filterEmptyValues(mapValue);
                if (!filteredMap.isEmpty()) {
                    if (key.contains("nelegislativní-zdroj") || key.contains("ustanovení-právního-předpisu")) {
                        log.info("SOURCE PROPERTY DEBUG: Keeping MAP source property field '{}' with filtered content", key);
                    }
                    filtered.put(entry.getKey(), filteredMap);
                } else {
                    if (key.contains("nelegislativní-zdroj") || key.contains("ustanovení-právního-předpisu")) {
                        log.warn("SOURCE PROPERTY DEBUG: Removing source property field '{}' after filtering resulted in empty map", key);
                    }
                }
            } else if (value instanceof List<?> listValue) {
                if (listValue.isEmpty()) {
                    if (key.contains("nelegislativní-zdroj") || key.contains("ustanovení-právního-předpisu")) {
                        log.warn("SOURCE PROPERTY DEBUG: Removing EMPTY LIST source property field: {}", key);
                    }
                    continue;
                }

                List<Object> filteredList = filterEmptyListItems(listValue);
                if (!filteredList.isEmpty()) {
                    if (key.contains("nelegislativní-zdroj") || key.contains("ustanovení-právního-předpisu")) {
                        log.info("SOURCE PROPERTY DEBUG: Keeping LIST source property field '{}' with {} items", key, filteredList.size());
                    }
                    filtered.put(entry.getKey(), filteredList);
                } else {
                    if (key.contains("nelegislativní-zdroj") || key.contains("ustanovení-právního-předpisu")) {
                        log.warn("SOURCE PROPERTY DEBUG: Removing source property field '{}' after filtering resulted in empty list", key);
                    }
                }
            } else {
                if (key.contains("nelegislativní-zdroj") || key.contains("ustanovení-právního-předpisu")) {
                    log.info("SOURCE PROPERTY DEBUG: Keeping source property field '{}' with value: {}", key, value);
                }
                filtered.put(entry.getKey(), value);
            }
        }

        return filtered;
    }

    @SuppressWarnings("unchecked")
    private boolean isEmptyMultilingualField(Map<String, Object> map) {
        for (Object value : map.values()) {
            if (value instanceof String && !((String) value).isEmpty()) {
                return false;
            }
            if (value instanceof List && !((List<?>) value).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private List<Object> filterEmptyListItems(List<?> list) {
        List<Object> filtered = new ArrayList<>();

        for (Object item : list) {
            if (item == null) {
                continue;
            }

            if (item instanceof String && ((String) item).isEmpty()) {
                continue;
            }

            if (item instanceof Map) {
                Map<String, Object> mapItem = (Map<String, Object>) item;
                if (mapItem.isEmpty()) {
                    continue;
                }

                Map<String, Object> filteredMap = filterEmptyValues(mapItem);
                if (!filteredMap.isEmpty()) {
                    filtered.add(filteredMap);
                }
            } else {
                filtered.add(item);
            }
        }

        return filtered;
    }
}
