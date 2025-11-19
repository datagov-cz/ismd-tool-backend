package com.dia.ismdtoolbackend.utility.exporter.json;

import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;

import java.util.*;

import com.dia.ismdtoolbackend.exception.ModelProcessingException;

import static com.dia.constants.VocabularyConstants.*;

@Slf4j
public class ConceptProcessor {

    private static final String A104_NAMESPACE = "https://slovník.gov.cz/agendový/104/pojem/";
    private static final String L111_2009_NAMESPACE = "https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/";

    public ConceptData processAllConcepts(OntModel ontModel, ModelStructure structure) {
        if (ontModel == null) {
            throw new ModelProcessingException("OntModel cannot be null");
        }
        if (structure == null) {
            throw new ModelProcessingException("ModelStructure cannot be null");
        }

        log.debug("Processing concepts for JSON export");

        try {
            Set<Resource> conceptTypes = getConceptTypes(ontModel);
            List<Map<String, Object>> concepts = new ArrayList<>();
            int failedConceptCount = 0;

            for (Resource resource : structure.getResourceMap().values()) {
                if (isConceptResource(resource, conceptTypes)) {
                    failedConceptCount += processSingleConcept(resource, ontModel, structure, concepts);
                }
            }

            if (failedConceptCount > 0) {
                log.warn("Failed to process {} concepts out of {} total resources",
                        failedConceptCount, structure.getResourceMap().size());
            }

            log.debug("Successfully processed {} concepts", concepts.size());
            return ConceptData.builder()
                    .concepts(concepts)
                    .totalConceptCount(concepts.size())
                    .build();

        } catch (Exception e) {
            log.error("Critical error during concept processing: {}", e.getMessage(), e);
            throw new ModelProcessingException("Failed to process concepts: " + e.getMessage(), e);
        }
    }

    private int processSingleConcept(Resource resource, OntModel ontModel, ModelStructure structure, List<Map<String, Object>> concepts) {
        try {
            Map<String, Object> conceptObject = createConceptObject(resource, ontModel, structure);
            concepts.add(conceptObject);
            return 0;
        } catch (Exception e) {
            log.warn("Failed to process concept: {} - {}", resource.getURI(), e.getMessage(), e);
            return 1;
        }
    }

    private Set<Resource> getConceptTypes(OntModel ontModel) {
        return Set.of(
                ontModel.getResource(OFN_NAMESPACE + POJEM),
                ontModel.getResource(OFN_NAMESPACE + VZTAH),
                ontModel.getResource(OFN_NAMESPACE + VLASTNOST),
                ontModel.getResource(OFN_NAMESPACE + TRIDA),
                ontModel.getResource(OFN_NAMESPACE + TSP),
                ontModel.getResource(OFN_NAMESPACE + TOP),
                ontModel.getResource(OFN_NAMESPACE + VEREJNY_UDAJ),
                ontModel.getResource(OFN_NAMESPACE + NEVEREJNY_UDAJ),
                ontModel.createResource(SKOS_NS + "Concept")
        );
    }

    private boolean isConceptResource(Resource resource, Set<Resource> conceptTypes) {
        return conceptTypes.stream().anyMatch(type -> resource.hasProperty(RDF.type, type));
    }

    private Map<String, Object> createConceptObject(Resource concept, OntModel ontModel, ModelStructure structure) {
        Map<String, Object> conceptObj = new LinkedHashMap<>();

        conceptObj.put("iri", concept.getURI());
        conceptObj.put("typ", getConceptTypes(concept, ontModel));

        addMultilingualProperty(concept, SKOS.prefLabel, NAZEV, conceptObj);
        addAlternativeNames(concept, conceptObj, ontModel, structure.getEffectiveNamespace());

        Property definitionProperty = ontModel.createProperty(SKOS_NS + "definition");
        addMultilingualProperty(concept, definitionProperty, DEFINICE, conceptObj);

        Property descriptionProperty = ontModel.createProperty(DCT_NS + "description");
        addMultilingualProperty(concept, descriptionProperty, POPIS, conceptObj);

        Property identifierProperty = ontModel.createProperty(DCT_NS + "identifier");
        addResourceArrayProperty(concept, identifierProperty, IDENTIFIKATOR, conceptObj);

        addExactMatchProperty(concept, conceptObj, ontModel);

        addSourceProperties(concept, conceptObj, ontModel, structure.getEffectiveNamespace());

        addDomainAndRange(concept, conceptObj);

        addHierarchicalRelationships(concept, conceptObj, ontModel);

        addGovernanceProperties(concept, conceptObj, ontModel, structure.getEffectiveNamespace());

        addMetadataProperties(concept, conceptObj, ontModel, structure.getEffectiveNamespace());

        return conceptObj;
    }

    private List<String> getConceptTypes(Resource concept, OntModel ontModel) {
        List<String> types = new ArrayList<>();
        types.add(POJEM_JSON_LD);

        String[][] typeMapping = {
                {TRIDA, TRIDA_JSON_LD},
                {VZTAH, VZTAH_JSON_LD},
                {VLASTNOST, VLASTNOST_JSON_LD},
                {TSP, TSP_JSON_LD},
                {TOP, TOP_JSON_LD},
                {VEREJNY_UDAJ, VEREJNY_UDAJ_JSON_LD},
                {NEVEREJNY_UDAJ, NEVEREJNY_UDAJ_JSON_LD}
        };

        boolean isVztah = concept.hasProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + VZTAH));

        for (String[] mapping : typeMapping) {
            if (concept.hasProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + mapping[0]))) {
                if (mapping[0].equals(VLASTNOST) && isVztah) {
                    continue;
                }
                types.add(mapping[1]);
            }
        }

        return types;
    }

    private void addMultilingualProperty(Resource concept, Property property, String jsonProperty,
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

    private String getLanguageOrDefault(Statement stmt) {
        String lang = stmt.getLanguage();
        return (lang == null || lang.isEmpty()) ? "cs" : lang;
    }

    private void addValueToLanguageMap(Map<String, Object> languageMap, String lang, String value) {
        if (languageMap.containsKey(lang)) {
            Object existingValue = languageMap.get(lang);
            List<Object> langArray = convertToList(existingValue);
            langArray.add(value);
            languageMap.put(lang, langArray);
        } else {
            languageMap.put(lang, value);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> convertToList(Object existingValue) {
        if (existingValue instanceof List<?>) {
            return (List<Object>) existingValue;
        }
        List<Object> langArray = new ArrayList<>();
        langArray.add(existingValue);
        return langArray;
    }

    private void addAlternativeNames(Resource concept, Map<String, Object> conceptObj,
                                     OntModel ontModel, String effectiveNamespace) {
        StmtIterator stmtIter = getAlternativeNameIterator(concept, ontModel, effectiveNamespace);

        if (!stmtIter.hasNext()) {
            return;
        }

        Map<String, Object> altNamesObj = new LinkedHashMap<>();
        boolean hasNonEmptyValue = processAlternativeNameStatements(stmtIter, altNamesObj);

        if (hasNonEmptyValue && !altNamesObj.isEmpty()) {
            conceptObj.put(ALTERNATIVNI_NAZEV, altNamesObj);
        }
    }

    private StmtIterator getAlternativeNameIterator(Resource concept, OntModel ontModel, String effectiveNamespace) {
        StmtIterator stmtIter = concept.listProperties(SKOS.altLabel);

        if (!stmtIter.hasNext()) {
            Property anPropDefault = ontModel.getProperty(DEFAULT_NS + ALTERNATIVNI_NAZEV);
            Property anPropCustom = ontModel.getProperty(effectiveNamespace + ALTERNATIVNI_NAZEV);

            stmtIter = concept.listProperties(anPropDefault);
            if (!stmtIter.hasNext()) {
                stmtIter = concept.listProperties(anPropCustom);
            }
        }

        return stmtIter;
    }

    private boolean processAlternativeNameStatements(StmtIterator stmtIter, Map<String, Object> altNamesObj) {
        boolean hasNonEmptyValue = false;

        while (stmtIter.hasNext()) {
            Statement stmt = stmtIter.next();
            String value = stmt.getString();
            if (value == null || value.isEmpty()) {
                continue;
            }

            String lang = getLanguageOrDefault(stmt);
            addValueToLanguageMap(altNamesObj, lang, value);
            hasNonEmptyValue = true;
        }

        return hasNonEmptyValue;
    }

    private void addResourceArrayProperty(Resource concept, Property property, String jsonProperty,
                                          Map<String, Object> conceptObj) {
        StmtIterator propIter = concept.listProperties(property);
        if (!propIter.hasNext()) {
            return;
        }

        List<String> propArray = new ArrayList<>();
        while (propIter.hasNext()) {
            Statement propStmt = propIter.next();
            if (propStmt.getObject().isResource()) {
                propArray.add(propStmt.getObject().asResource().getURI());
            } else if (propStmt.getObject().isLiteral()) {
                String literalValue = propStmt.getString();
                if (literalValue != null && !literalValue.trim().isEmpty()) {
                    propArray.add(literalValue);
                }
            }
        }

        if (!propArray.isEmpty()) {
            conceptObj.put(jsonProperty, propArray);
        }
    }

    private void addExactMatchProperty(Resource concept, Map<String, Object> conceptObj, OntModel ontModel) {
        Property exactMatchProperty = ontModel.createProperty(SKOS_NS + "exactMatch");
        StmtIterator exactMatchIter = concept.listProperties(exactMatchProperty);

        if (!exactMatchIter.hasNext()) {
            return;
        }

        List<Map<String, Object>> exactMatchArray = new ArrayList<>();

        while (exactMatchIter.hasNext()) {
            Statement exactMatchStmt = exactMatchIter.next();
            Map<String, Object> exactMatchObj = new LinkedHashMap<>();

            if (exactMatchStmt.getObject().isResource()) {
                exactMatchObj.put("id", exactMatchStmt.getObject().asResource().getURI());
            } else if (exactMatchStmt.getObject().isLiteral()) {
                String literalValue = exactMatchStmt.getString();
                if (literalValue != null && !literalValue.trim().isEmpty()) {
                    exactMatchObj.put("id", literalValue);
                }
            }

            if (!exactMatchObj.isEmpty()) {
                exactMatchArray.add(exactMatchObj);
            }
        }

        if (!exactMatchArray.isEmpty()) {
            conceptObj.put(EKVIVALENTNI_POJEM, exactMatchArray);
        }
    }

    private void addSourceProperties(Resource concept, Map<String, Object> conceptObj,
                                     OntModel ontModel, String effectiveNamespace) {
        addSourceProperty(concept, conceptObj, ontModel, effectiveNamespace,
                DEFINUJICI_USTANOVENI_PRAVNIHO_PREDPISU, DEFINUJICI_USTANOVENI_PRAVNIHO_PREDPISU);
        addSourceProperty(concept, conceptObj, ontModel, effectiveNamespace,
                SOUVISEJICI_USTANOVENI_PRAVNIHO_PREDPISU, SOUVISEJICI_USTANOVENI_PRAVNIHO_PREDPISU);
        addNonLegislativeSourceProperty(concept, conceptObj, ontModel, effectiveNamespace,
                DEFINUJICI_NELEGISLATIVNI_ZDROJ, DEFINUJICI_NELEGISLATIVNI_ZDROJ);
        addNonLegislativeSourceProperty(concept, conceptObj, ontModel, effectiveNamespace,
                SOUVISEJICI_NELEGISLATIVNI_ZDROJ, SOUVISEJICI_NELEGISLATIVNI_ZDROJ);
    }

    private void addSourceProperty(Resource concept, Map<String, Object> conceptObj, OntModel ontModel,
                                   String namespace, String propertyName, String jsonFieldName) {
        Property customProperty = ontModel.getProperty(namespace + propertyName);
        Property defaultProperty = ontModel.getProperty(DEFAULT_NS + propertyName);
        Property ofnProperty = ontModel.getProperty(OFN_NAMESPACE + propertyName);

        Property sourceProperty = null;
        if (concept.hasProperty(customProperty)) {
            sourceProperty = customProperty;
        } else if (concept.hasProperty(defaultProperty)) {
            sourceProperty = defaultProperty;
        } else if (concept.hasProperty(ofnProperty)) {
            sourceProperty = ofnProperty;
        }

        if (sourceProperty != null) {
            addResourceArrayProperty(concept, sourceProperty, jsonFieldName, conceptObj);
        }
    }

    private void addNonLegislativeSourceProperty(Resource concept, Map<String, Object> conceptObj,
                                                 OntModel ontModel, String namespace,
                                                 String propertyName, String jsonFieldName) {
        Property sourceProperty = findSourceProperty(concept, ontModel, namespace, propertyName);

        if (sourceProperty == null) {
            return;
        }

        StmtIterator propIter = concept.listProperties(sourceProperty);
        if (!propIter.hasNext()) {
            return;
        }

        List<Map<String, Object>> sourceArray = extractDigitalDocuments(propIter, ontModel);

        if (!sourceArray.isEmpty()) {
            conceptObj.put(jsonFieldName, sourceArray);
        }
    }

    private Property findSourceProperty(Resource concept, OntModel ontModel, String namespace, String propertyName) {
        Property customProperty = ontModel.getProperty(namespace + propertyName);
        Property defaultProperty = ontModel.getProperty(DEFAULT_NS + propertyName);
        Property ofnProperty = ontModel.getProperty(OFN_NAMESPACE + propertyName);

        if (concept.hasProperty(customProperty)) {
            return customProperty;
        } else if (concept.hasProperty(defaultProperty)) {
            return defaultProperty;
        } else if (concept.hasProperty(ofnProperty)) {
            return ofnProperty;
        }
        return null;
    }

    private List<Map<String, Object>> extractDigitalDocuments(StmtIterator propIter, OntModel ontModel) {
        List<Map<String, Object>> documents = new ArrayList<>();

        while (propIter.hasNext()) {
            Statement propStmt = propIter.next();
            if (!propStmt.getObject().isResource()) {
                continue;
            }

            Resource digitalDoc = propStmt.getObject().asResource();
            Map<String, Object> docObj = createDigitalDocumentObject(digitalDoc, ontModel);

            if (!docObj.isEmpty()) {
                documents.add(docObj);
            }
        }

        return documents;
    }

    private Map<String, Object> createDigitalDocumentObject(Resource digitalDoc, OntModel ontModel) {
        Map<String, Object> docObj = new LinkedHashMap<>();

        Resource digitalObjectType = ontModel.createResource("https://slovník.gov.cz/generický/digitální-objekty/pojem/digitální-objekt");
        if (digitalDoc.hasProperty(RDF.type, digitalObjectType)) {
            docObj.put("typ", "Digitální objekt");
        }

        Property titleProperty = ontModel.createProperty(DCT_NS + "title");
        if (digitalDoc.hasProperty(titleProperty)) {
            Map<String, Object> titleObj = new LinkedHashMap<>();
            StmtIterator titleIter = digitalDoc.listProperties(titleProperty);

            while (titleIter.hasNext()) {
                Statement titleStmt = titleIter.next();
                if (titleStmt.getObject().isLiteral()) {
                    String lang = titleStmt.getLanguage();
                    String value = titleStmt.getString();

                    if (value != null && !value.trim().isEmpty()) {
                        titleObj.put(lang != null && !lang.isEmpty() ? lang : "cs", value);
                    }
                }
            }

            if (!titleObj.isEmpty()) {
                docObj.put(NAZEV, titleObj);
            }
        }

        Property urlProperty = ontModel.createProperty(SCHEMA_URL);
        if (digitalDoc.hasProperty(urlProperty)) {
            Statement urlStmt = digitalDoc.getProperty(urlProperty);

            if (urlStmt.getObject().isResource()) {
                docObj.put("url", urlStmt.getObject().asResource().getURI());
            } else if (urlStmt.getObject().isLiteral()) {
                String urlValue = urlStmt.getString();
                if (urlValue != null && !urlValue.trim().isEmpty()) {
                    docObj.put("url", urlValue);
                }
            }
        }

        return docObj;
    }

    private void addDomainAndRange(Resource concept, Map<String, Object> conceptObj) {
        Statement domainStmt = concept.getProperty(RDFS.domain);
        if (domainStmt != null && domainStmt.getObject().isResource()) {
            conceptObj.put(DEFINICNI_OBOR, domainStmt.getObject().asResource().getURI());
        }

        Statement rangeStmt = concept.getProperty(RDFS.range);
        if (rangeStmt != null && rangeStmt.getObject().isResource()) {
            String rangeUri = rangeStmt.getObject().asResource().getURI();

            if (rangeUri.startsWith(XSD)) {
                conceptObj.put(OBOR_HODNOT, "xsd:" + rangeUri.substring(XSD.length()));
            } else {
                conceptObj.put(OBOR_HODNOT, rangeUri);
            }
        }
    }

    private void addHierarchicalRelationships(Resource concept, Map<String, Object> conceptObj, OntModel ontModel) {
        addSubClassRelationships(concept, conceptObj);
        addSubPropertyRelationships(concept, conceptObj, ontModel);
    }

    private void addSubClassRelationships(Resource concept, Map<String, Object> conceptObj) {
        StmtIterator subClassIter = concept.listProperties(RDFS.subClassOf);
        if (!subClassIter.hasNext()) {
            return;
        }

        List<String> hierarchyArray = extractResourceURIs(subClassIter);

        if (!hierarchyArray.isEmpty()) {
            conceptObj.put(NADRAZENA_TRIDA, hierarchyArray);
        }
    }

    private void addSubPropertyRelationships(Resource concept, Map<String, Object> conceptObj, OntModel ontModel) {
        StmtIterator subPropertyIter = concept.listProperties(RDFS.subPropertyOf);
        if (!subPropertyIter.hasNext()) {
            return;
        }

        List<String> superPropertyArray = extractResourceURIs(subPropertyIter);

        if (!superPropertyArray.isEmpty()) {
            String propertyKey = determinePropertyKey(concept, ontModel);
            if (propertyKey != null) {
                conceptObj.put(propertyKey, superPropertyArray);
            }
        }
    }

    private List<String> extractResourceURIs(StmtIterator stmtIter) {
        List<String> uriArray = new ArrayList<>();

        while (stmtIter.hasNext()) {
            Statement stmt = stmtIter.next();
            if (stmt.getObject().isResource()) {
                uriArray.add(stmt.getObject().asResource().getURI());
            }
        }

        return uriArray;
    }

    private String determinePropertyKey(Resource concept, OntModel ontModel) {
        if (concept.hasProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + VZTAH))) {
            return NADRAZENY_VZTAH;
        } else if (concept.hasProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + VLASTNOST))) {
            return NADRAZENA_VLASTNOST;
        }
        return null;
    }

    private void addGovernanceProperties(Resource concept, Map<String, Object> conceptObj,
                                         OntModel ontModel, String namespace) {
        addGovernancePropertyArray(concept, conceptObj, ontModel, namespace);
        addSingleGovernanceProperty(concept, conceptObj, ontModel, namespace, ZPUSOB_ZISKANI, ZPUSOB_ZISKANI_ALT);
        addSingleGovernanceProperty(concept, conceptObj, ontModel, namespace, TYP_OBSAHU, TYP_OBSAHU_ALT);
    }

    private void addGovernancePropertyArray(Resource concept, Map<String, Object> conceptObj,
                                            OntModel ontModel, String namespace) {
        Property property = findGovernancePropertyWithFallbacks(concept, ontModel, namespace,
                ZPUSOB_SDILENI_UDAJE, ZPUSOB_SDILENI, ZPUSOB_SDILENI_ALT);

        if (property == null) {
            return;
        }

        StmtIterator propIter = concept.listProperties(property);
        List<String> allValues = extractGovernanceValues(propIter);

        if (!allValues.isEmpty()) {
            conceptObj.put(ZPUSOB_SDILENI_ALT, allValues);
        }
    }

    private Property findGovernancePropertyWithFallbacks(Resource concept, OntModel ontModel, String namespace,
                                                         String... propertyNames) {
        for (String propertyName : propertyNames) {
            Property customProperty = ontModel.getProperty(namespace + propertyName);
            if (concept.hasProperty(customProperty)) {
                return customProperty;
            }

            Property defaultProperty = ontModel.getProperty(DEFAULT_NS + propertyName);
            if (concept.hasProperty(defaultProperty)) {
                return defaultProperty;
            }
        }
        return null;
    }

    private void addSingleGovernanceProperty(Resource concept, Map<String, Object> conceptObj,
                                             OntModel ontModel, String namespace,
                                             String propertyName, String jsonFieldName) {
        Property property = findGovernancePropertyWithFallbacks(concept, ontModel, namespace,
                propertyName + "-údaje", propertyName, jsonFieldName);

        if (property == null) {
            return;
        }

        Statement stmt = concept.getProperty(property);
        if (stmt != null) {
            String value = extractStatementValue(stmt);
            if (value != null && !value.trim().isEmpty()) {
                conceptObj.put(jsonFieldName, value.trim());
            }
        }
    }

    private List<String> extractGovernanceValues(StmtIterator propIter) {
        List<String> allValues = new ArrayList<>();

        while (propIter.hasNext()) {
            Statement propStmt = propIter.next();
            String value = extractStatementValue(propStmt);

            if (value != null && !value.trim().isEmpty()) {
                addSplitValues(value, allValues);
            }
        }

        return allValues;
    }

    private String extractStatementValue(Statement propStmt) {
        if (propStmt.getObject().isLiteral()) {
            return propStmt.getString();
        } else if (propStmt.getObject().isResource()) {
            return propStmt.getObject().asResource().getURI();
        }
        return null;
    }

    private void addSplitValues(String value, List<String> targetList) {
        if (value.contains(";")) {
            String[] splitValues = value.split(";");
            for (String singleValue : splitValues) {
                String trimmedValue = singleValue.trim();
                if (!trimmedValue.isEmpty()) {
                    targetList.add(trimmedValue);
                }
            }
        } else {
            targetList.add(value.trim());
        }
    }

    private void addMetadataProperties(Resource concept, Map<String, Object> conceptObj,
                                       OntModel ontModel, String namespace) {
        addPpdfProperty(concept, conceptObj, ontModel, namespace);

        addMetadataProperty(concept, conceptObj, ontModel, namespace, AIS, UDAJE_AIS);

        addMetadataProperty(concept, conceptObj, ontModel, namespace, AGENDA, AGENDA_LONG);

        addUstanoveniProperty(concept, conceptObj, ontModel, namespace);
    }

    private void addPpdfProperty(Resource concept, Map<String, Object> conceptObj,
                                 OntModel ontModel, String namespace) {
        Property ppdfDefault = ontModel.getProperty(DEFAULT_NS + JE_PPDF);
        Property ppdfCustom = ontModel.getProperty(namespace + JE_PPDF);

        Statement stmt = concept.getProperty(ppdfDefault);
        if (stmt == null) {
            stmt = concept.getProperty(ppdfCustom);
        }

        if (stmt == null) {
            Property ppdfLong = ontModel.getProperty(A104_NAMESPACE + JE_PPDF_LONG);
            stmt = concept.getProperty(ppdfLong);
        }

        if (stmt != null && stmt.getObject().isLiteral()) {
            boolean value = stmt.getBoolean();
            conceptObj.put(JE_PPDF, value);
        }
    }

    private void addMetadataProperty(Resource concept, Map<String, Object> conceptObj,
                                     OntModel ontModel, String namespace,
                                     String primaryProperty, String longProperty) {
        Property defaultProperty = ontModel.getProperty(DEFAULT_NS + primaryProperty);
        Property customProperty = ontModel.getProperty(namespace + primaryProperty);

        Statement stmt = null;
        if (concept.hasProperty(defaultProperty)) {
            stmt = concept.getProperty(defaultProperty);
        } else if (concept.hasProperty(customProperty)) {
            stmt = concept.getProperty(customProperty);
        } else {
            Property longPropertyInFallback = ontModel.getProperty(A104_NAMESPACE + longProperty);
            if (concept.hasProperty(longPropertyInFallback)) {
                stmt = concept.getProperty(longPropertyInFallback);
            }
        }

        if (stmt != null) {
            if (stmt.getObject().isResource()) {
                conceptObj.put(primaryProperty, stmt.getObject().asResource().getURI());
            } else if (stmt.getObject().isLiteral()) {
                String literalValue = stmt.getString();
                if (literalValue != null && !literalValue.trim().isEmpty()) {
                    conceptObj.put(primaryProperty, literalValue);
                }
            }
        }
    }

    private void addUstanoveniProperty(Resource concept, Map<String, Object> conceptObj,
                                       OntModel ontModel, String namespace) {
        Property suppDefault = ontModel.getProperty(DEFAULT_NS + USTANOVENI_NEVEREJNOST);
        Property suppCustom = ontModel.getProperty(namespace + USTANOVENI_NEVEREJNOST);

        if (concept.hasProperty(suppDefault)) {
            addResourceArrayProperty(concept, suppDefault, USTANOVENI_NEVEREJNOST, conceptObj);
        } else if (concept.hasProperty(suppCustom)) {
            addResourceArrayProperty(concept, suppCustom, USTANOVENI_NEVEREJNOST, conceptObj);
        } else {
            Property suppLong = ontModel.getProperty(L111_2009_NAMESPACE + USTANOVENI_LONG);
            if (concept.hasProperty(suppLong)) {
                addResourceArrayProperty(concept, suppLong, USTANOVENI_NEVEREJNOST, conceptObj);
            }
        }
    }
}
