package com.dia.ismdtoolbackend.utility.exporter.json;

import com.dia.ismdtoolbackend.exception.JsonExportException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

import static com.dia.constants.VocabularyConstants.*;


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
        root.put(JSON_TYP, structure.getVocabularyTypes());

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

    private Map<String, Object> filterEmptyValues(Map<String, Object> map) {
        Map<String, Object> filtered = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();

            if (shouldSkipValue(value)) {
                continue;
            }

            Object processedValue = processValue(value);
            if (processedValue != null) {
                filtered.put(entry.getKey(), processedValue);
            }
        }

        return filtered;
    }

    private boolean shouldSkipValue(Object value) {
        if (value == null) {
            return true;
        }

        return value instanceof String string && string.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Object processValue(Object value) {
        if (value instanceof Map) {
            return processMapValue((Map<String, Object>) value);
        }

        if (value instanceof List<?>) {
            return processListValue((List<?>) value);
        }

        return value;
    }

    private Object processMapValue(Map<String, Object> mapValue) {
        if (mapValue.isEmpty() || isEmptyMultilingualField(mapValue)) {
            return null;
        }

        Map<String, Object> filteredMap = filterEmptyValues(mapValue);
        return filteredMap.isEmpty() ? null : filteredMap;
    }

    private Object processListValue(List<?> listValue) {
        if (listValue.isEmpty()) {
            return null;
        }

        List<Object> filteredList = filterEmptyListItems(listValue);
        return filteredList.isEmpty() ? null : filteredList;
    }

    private boolean isEmptyMultilingualField(Map<String, Object> map) {
        for (Object value : map.values()) {
            if (value instanceof String string && !string.isEmpty()) {
                return false;
            }
            if (value instanceof List && !((List<?>) value).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private List<Object> filterEmptyListItems(List<?> list) {
        List<Object> filtered = new ArrayList<>();

        for (Object item : list) {
            Object processedItem = processListItem(item);
            if (processedItem != null) {
                filtered.add(processedItem);
            }
        }

        return filtered;
    }

    @SuppressWarnings("unchecked")
    private Object processListItem(Object item) {
        if (item == null) {
            return null;
        }

        if (item instanceof String string && string.isEmpty()) {
            return null;
        }

        if (item instanceof Map) {
            Map<String, Object> mapItem = (Map<String, Object>) item;
            if (mapItem.isEmpty()) {
                return null;
            }

            Map<String, Object> filteredMap = filterEmptyValues(mapItem);
            return filteredMap.isEmpty() ? null : filteredMap;
        }

        return item;
    }
}
