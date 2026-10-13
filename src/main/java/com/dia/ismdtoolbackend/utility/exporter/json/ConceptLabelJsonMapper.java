package com.dia.ismdtoolbackend.utility.exporter.json;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.SKOS;

import java.util.*;

import static com.dia.constants.VocabularyConstants.*;

/** Maps multilingual labels, alternative names and the description. */
final class ConceptLabelJsonMapper {

    void addMultilingualProperty(Resource concept, Property property, String jsonProperty,
                                         Map<String, Object> conceptObj) {
        StmtIterator propIter = concept.listProperties(property);
        if (!propIter.hasNext()) {
            return;
        }

        Map<String, Object> propObj = new LinkedHashMap<>();
        boolean hasNonEmptyValue = false;

        while (propIter.hasNext()) {
            Statement propStmt = propIter.next();
            String value = propStmt.getString();
            if (value == null || value.isEmpty()) {
                continue;
            }

            String lang = getLanguageOrDefault(propStmt);
            addValueToLanguageMap(propObj, lang, value);
            hasNonEmptyValue = true;
        }

        if (hasNonEmptyValue && !propObj.isEmpty()) {
            conceptObj.put(jsonProperty, propObj);
        }
    }

    String getLanguageOrDefault(Statement stmt) {
        String lang = stmt.getLanguage();
        return (lang == null || lang.isEmpty()) ? "cs" : lang;
    }

    void addValueToLanguageMap(Map<String, Object> languageMap, String lang, String value) {
        if (languageMap.containsKey(lang)) {
            Object existingValue = languageMap.get(lang);
            List<Object> langArray = convertToList(existingValue);
            langArray.add(value);
            languageMap.put(lang, langArray);
        } else {
            languageMap.put(lang, value);
        }
    }

    void addValueToLanguageMapAsArray(Map<String, Object> languageMap, String lang, String value) {
        if (languageMap.containsKey(lang)) {
            @SuppressWarnings("unchecked")
            List<Object> langArray = (List<Object>) languageMap.get(lang);
            langArray.add(value);
        } else {
            List<Object> langArray = new ArrayList<>();
            langArray.add(value);
            languageMap.put(lang, langArray);
        }
    }

    @SuppressWarnings("unchecked")
    List<Object> convertToList(Object existingValue) {
        if (existingValue instanceof List<?>) {
            return (List<Object>) existingValue;
        }
        List<Object> langArray = new ArrayList<>();
        langArray.add(existingValue);
        return langArray;
    }

    void addAlternativeNames(Resource concept, Map<String, Object> conceptObj) {
        StmtIterator stmtIter = concept.listProperties(SKOS.altLabel);

        if (!stmtIter.hasNext()) {
            return;
        }

        Map<String, Object> altNamesObj = new LinkedHashMap<>();
        boolean hasNonEmptyValue = processAlternativeNameStatements(stmtIter, altNamesObj);

        if (hasNonEmptyValue && !altNamesObj.isEmpty()) {
            conceptObj.put(ALTERNATIVNI_NAZEV, altNamesObj);
        }
    }

    boolean processAlternativeNameStatements(StmtIterator stmtIter, Map<String, Object> altNamesObj) {
        boolean hasNonEmptyValue = false;

        while (stmtIter.hasNext()) {
            Statement stmt = stmtIter.next();
            String value = stmt.getString();
            if (value == null || value.isEmpty()) {
                continue;
            }

            String lang = getLanguageOrDefault(stmt);
            addValueToLanguageMapAsArray(altNamesObj, lang, value);
            hasNonEmptyValue = true;
        }

        return hasNonEmptyValue;
    }

    void addDescriptionProperty(Resource concept, Map<String, Object> conceptObj,
                                       OntModel ontModel) {
        Property descriptionProperty = ontModel.createProperty(DCT_NS + "description");
        StmtIterator descIter = concept.listProperties(descriptionProperty);

        if (descIter.hasNext()) {
            Map<String, Object> descObj = new LinkedHashMap<>();
            boolean hasNonEmptyValue = false;

            while (descIter.hasNext()) {
                Statement descStmt = descIter.next();
                String value = descStmt.getString();
                if (value == null || value.isEmpty()) {
                    continue;
                }

                String lang = getLanguageOrDefault(descStmt);
                addValueToLanguageMap(descObj, lang, value);
                hasNonEmptyValue = true;
            }

            if (hasNonEmptyValue && !descObj.isEmpty()) {
                conceptObj.put(POPIS, descObj);
            }
        }
    }
}
