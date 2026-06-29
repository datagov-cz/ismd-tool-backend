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

    public Map<String, Object> processConceptByIri(OntModel ontModel, ModelStructure structure, String conceptIri) {
        if (ontModel == null) {
            throw new ModelProcessingException("OntModel cannot be null");
        }
        if (structure == null) {
            throw new ModelProcessingException("ModelStructure cannot be null");
        }
        if (conceptIri == null || conceptIri.isEmpty()) {
            throw new ModelProcessingException("Concept IRI cannot be null or empty");
        }

        log.debug("Processing single concept with IRI: {}", conceptIri);

        try {
            Resource concept = ontModel.getResource(conceptIri);
            if (concept == null) {
                throw new ModelProcessingException("Concept not found: " + conceptIri);
            }

            Set<Resource> conceptTypes = getConceptTypes(ontModel);
            if (!isConceptResource(concept, conceptTypes)) {
                throw new ModelProcessingException("Resource is not a valid concept: " + conceptIri);
            }

            return createConceptObject(concept, ontModel, structure);

        } catch (Exception e) {
            log.error("Failed to process concept {}: {}", conceptIri, e.getMessage(), e);
            throw new ModelProcessingException("Failed to process concept: " + e.getMessage(), e);
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
                ontModel.getResource(OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ),
                ontModel.getResource(OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ),
                ontModel.createResource(SKOS_NS + "Concept"),
                ontModel.createResource("http://www.w3.org/2002/07/owl#Class"),
                ontModel.createResource("http://www.w3.org/2002/07/owl#ObjectProperty"),
                ontModel.createResource("http://www.w3.org/2002/07/owl#DatatypeProperty")
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
        addAlternativeNames(concept, conceptObj);

        Property definitionProperty = ontModel.createProperty(SKOS_NS + "definition");
        addMultilingualProperty(concept, definitionProperty, DEFINICE, conceptObj);

        addDescriptionProperty(concept, conceptObj, ontModel);

        Property identifierProperty = ontModel.createProperty(DCT_NS + "identifier");
        addResourceArrayProperty(concept, identifierProperty, IDENTIFIKATOR, conceptObj);

        addExactMatchProperty(concept, conceptObj, ontModel);

        addSourceProperties(concept, conceptObj, ontModel, structure.getEffectiveNamespace());

        addDomainAndRange(concept, conceptObj);

        addHierarchicalRelationships(concept, conceptObj, ontModel);

        addGovernanceProperties(concept, conceptObj, ontModel);

        addMetadataProperties(concept, conceptObj, ontModel);

        addCodeListDatasetProperty(concept, conceptObj, ontModel);

        return conceptObj;
    }

    private List<String> getConceptTypes(Resource concept, OntModel ontModel) {
        List<String> types = new ArrayList<>();
        types.add(POJEM_JSON_LD);
        types.add("Koncept");

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
            String namespace = (mapping[0].equals(VEREJNY_UDAJ) || mapping[0].equals(NEVEREJNY_UDAJ))
                    ? OFN_NAMESPACE_LEGAL : OFN_NAMESPACE;
            if (concept.hasProperty(RDF.type, ontModel.getResource(namespace + mapping[0]))) {
                if (mapping[0].equals(VLASTNOST) && isVztah) {
                    continue;
                }
                types.add(mapping[1]);
            }
        }

        addOwlTypeIfPresent(concept, ontModel, types, isVztah);

        return types;
    }

    private void addOwlTypeIfPresent(Resource concept, OntModel ontModel, List<String> types, boolean isVztah) {
        if (concept.hasProperty(RDF.type, ontModel.getResource("http://www.w3.org/2002/07/owl#Class")) && !types.contains(TRIDA_JSON_LD)) {
            types.add(TRIDA_JSON_LD);
        }

        if (concept.hasProperty(RDF.type, ontModel.getResource("http://www.w3.org/2002/07/owl#ObjectProperty")) && !types.contains(VZTAH_JSON_LD)) {
            types.add(VZTAH_JSON_LD);
        }

        if (concept.hasProperty(RDF.type, ontModel.getResource("http://www.w3.org/2002/07/owl#DatatypeProperty")) && !isVztah && !types.contains(VLASTNOST_JSON_LD)) {
            types.add(VLASTNOST_JSON_LD);
        }
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

    private void addValueToLanguageMapAsArray(Map<String, Object> languageMap, String lang, String value) {
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
    private List<Object> convertToList(Object existingValue) {
        if (existingValue instanceof List<?>) {
            return (List<Object>) existingValue;
        }
        List<Object> langArray = new ArrayList<>();
        langArray.add(existingValue);
        return langArray;
    }

    private void addAlternativeNames(Resource concept, Map<String, Object> conceptObj) {
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

    private boolean processAlternativeNameStatements(StmtIterator stmtIter, Map<String, Object> altNamesObj) {
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

        List<String> exactMatchArray = new ArrayList<>();

        while (exactMatchIter.hasNext()) {
            Statement exactMatchStmt = exactMatchIter.next();

            if (exactMatchStmt.getObject().isResource()) {
                exactMatchArray.add(exactMatchStmt.getObject().asResource().getURI());
            } else if (exactMatchStmt.getObject().isLiteral()) {
                String literalValue = exactMatchStmt.getString();
                if (literalValue != null && !literalValue.trim().isEmpty()) {
                    exactMatchArray.add(literalValue);
                }
            }
        }

        if (!exactMatchArray.isEmpty()) {
            conceptObj.put(EKVIVALENTNI_POJEM, exactMatchArray);
        }
    }

    private void addSourceProperties(Resource concept, Map<String, Object> conceptObj,
                                     OntModel ontModel, String effectiveNamespace) {
        addSourceProperty(concept, conceptObj, ontModel,
                DEFINUJICI_USTANOVENI, DEFINUJICI_USTANOVENI_PRAVNIHO_PREDPISU);
        addSourceProperty(concept, conceptObj, ontModel,
                SOUVISEJICI_USTANOVENI, SOUVISEJICI_USTANOVENI_PRAVNIHO_PREDPISU);
        addNonLegislativeSourceProperty(concept, conceptObj, ontModel, effectiveNamespace,
                DEFINUJICI_NELEGISLATIVNI_ZDROJ, DEFINUJICI_NELEGISLATIVNI_ZDROJ);
        addNonLegislativeSourceProperty(concept, conceptObj, ontModel, effectiveNamespace,
                SOUVISEJICI_NELEGISLATIVNI_ZDROJ, SOUVISEJICI_NELEGISLATIVNI_ZDROJ);
    }

    private void addSourceProperty(Resource concept, Map<String, Object> conceptObj, OntModel ontModel, String propertyName, String jsonFieldName) {
        String fullPropertyUri = OFN_NAMESPACE + propertyName;
        Property ofnProperty = ontModel.getProperty(fullPropertyUri);

        if (concept.hasProperty(ofnProperty)) {
            log.debug("Adding source property {} to concept {}", jsonFieldName, concept.getURI());
            addResourceArrayProperty(concept, ofnProperty, jsonFieldName, conceptObj);
        }
    }

    private void addNonLegislativeSourceProperty(Resource concept, Map<String, Object> conceptObj,
                                                 OntModel ontModel, String namespace,
                                                 String propertyName, String jsonFieldName) {
        Property sourceProperty = findSourceProperty(concept, ontModel, namespace, propertyName);

        StmtIterator propIter = null;
        if (sourceProperty != null) {
            propIter = concept.listProperties(sourceProperty);
        }

        if (propIter == null || !propIter.hasNext()) {
            List<Statement> fallbackStmts = findAllPropertiesByLocalName(concept, propertyName);
            if (!fallbackStmts.isEmpty()) {
                List<Map<String, Object>> sourceArray = new ArrayList<>();
                for (Statement fallbackStmt : fallbackStmts) {
                    if (!fallbackStmt.getObject().isResource()) continue;
                    Map<String, Object> docObj = createDigitalDocumentObject(
                            fallbackStmt.getObject().asResource(), ontModel);
                    if (!docObj.isEmpty()) {
                        sourceArray.add(docObj);
                    }
                }
                if (!sourceArray.isEmpty()) {
                    conceptObj.put(jsonFieldName, sourceArray);
                }
                return;
            }
        }

        if (propIter != null && propIter.hasNext()) {
            List<Map<String, Object>> sourceArray = extractDigitalDocuments(propIter, ontModel);
            if (!sourceArray.isEmpty()) {
                conceptObj.put(jsonFieldName, sourceArray);
            }
        }
    }

    private Property findSourceProperty(Resource concept, OntModel ontModel, String namespace, String propertyName) {
        Property effectiveProperty = ontModel.getProperty(namespace + propertyName);

        if (concept.hasProperty(effectiveProperty)) {
            return effectiveProperty;
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

        if (digitalDoc.getURI() != null) {
            docObj.put("iri", digitalDoc.getURI());
        }

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

        Property descriptionProperty = ontModel.createProperty(DCT_NS + "description");
        if (digitalDoc.hasProperty(descriptionProperty)) {
            Map<String, Object> descObj = new LinkedHashMap<>();
            StmtIterator descIter = digitalDoc.listProperties(descriptionProperty);

            while (descIter.hasNext()) {
                Statement descStmt = descIter.next();
                if (descStmt.getObject().isLiteral()) {
                    String lang = descStmt.getLanguage();
                    String value = descStmt.getString();

                    if (value != null && !value.trim().isEmpty()) {
                        descObj.put(lang != null && !lang.isEmpty() ? lang : "cs", value);
                    }
                }
            }

            if (!descObj.isEmpty()) {
                docObj.put(POPIS, descObj);
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
        addSkosBroaderRelationships(concept, conceptObj);
    }

    private void addSkosBroaderRelationships(Resource concept, Map<String, Object> conceptObj) {
        StmtIterator broaderIter = concept.listProperties(SKOS.broader);
        if (!broaderIter.hasNext()) {
            return;
        }

        List<String> broaderArray = extractResourceURIs(broaderIter);

        if (!broaderArray.isEmpty()) {
            conceptObj.put("nadřazený-pojem", broaderArray);
        }
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
                                         OntModel ontModel) {
        addGovernancePropertyArray(concept, conceptObj, ontModel);
        addSingleGovernanceProperty(concept, conceptObj, ontModel, ZPUSOB_ZISKANI, ZPUSOB_ZISKANI_ALT);
        addSingleGovernanceProperty(concept, conceptObj, ontModel, TYP_OBSAHU, TYP_OBSAHU_ALT);
    }

    private void addGovernancePropertyArray(Resource concept, Map<String, Object> conceptObj,
                                            OntModel ontModel) {
        Property property = ontModel.getProperty(OFN_NAMESPACE + ZPUSOB_SDILENI);

        if (concept.hasProperty(property)) {
            StmtIterator propIter = concept.listProperties(property);
            List<String> allValues = extractGovernanceValues(propIter);

            if (!allValues.isEmpty()) {
                conceptObj.put(ZPUSOBY_SDILENI_ALT, allValues);
            }
        }
    }

    private void addSingleGovernanceProperty(Resource concept, Map<String, Object> conceptObj,
                                             OntModel ontModel,
                                             String propertyName, String jsonFieldName) {
        Property property = ontModel.getProperty(OFN_NAMESPACE + propertyName);

        if (concept.hasProperty(property)) {
            Statement stmt = concept.getProperty(property);
            if (stmt != null) {
                String value = extractStatementValue(stmt);
                if (value != null && !value.trim().isEmpty()) {
                    conceptObj.put(jsonFieldName, value.trim());
                }
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
                                       OntModel ontModel) {
        addPpdfProperty(concept, conceptObj, ontModel);

        addMetadataProperty(concept, conceptObj, ontModel, AIS, UDAJE_AIS);

        addMetadataProperty(concept, conceptObj, ontModel, AGENDA, AGENDA_LONG);

        addUstanoveniProperty(concept, conceptObj, ontModel);
    }

    private void addPpdfProperty(Resource concept, Map<String, Object> conceptObj,
                                 OntModel ontModel) {
        Property ppdfProperty = ontModel.getProperty(A104_NAMESPACE + JE_PPDF_LONG);

        Statement stmt = concept.getProperty(ppdfProperty);
        if (stmt != null && stmt.getObject().isLiteral()) {
            boolean value = stmt.getBoolean();
            conceptObj.put(JE_PPDF, value);
        }
    }

    private void addMetadataProperty(Resource concept, Map<String, Object> conceptObj,
                                     OntModel ontModel,
                                     String primaryProperty, String longProperty) {
        Property a104Property = ontModel.getProperty(A104_NAMESPACE + longProperty);

        Statement stmt = null;
        if (concept.hasProperty(a104Property)) {
            stmt = concept.getProperty(a104Property);
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
                                       OntModel ontModel) {
        Property suppLegal = ontModel.getProperty(L111_2009_NAMESPACE + USTANOVENI_LONG);

        if (concept.hasProperty(suppLegal)) {
            addResourceArrayProperty(concept, suppLegal, USTANOVENI_NEVEREJNOST, conceptObj);
        }
    }

    private void addCodeListDatasetProperty(Resource concept, Map<String, Object> conceptObj,
                                              OntModel ontModel) {
        Property instanceDefinedByCodeList = ontModel.getProperty(
                OFN_NAMESPACE + MA_INSTANCE_DEFINOVANE_CISELNIKEM);

        if (!concept.hasProperty(instanceDefinedByCodeList)) {
            return;
        }

        StmtIterator stmtIter = concept.listProperties(instanceDefinedByCodeList);
        while (stmtIter.hasNext()) {
            Statement stmt = stmtIter.next();
            if (!stmt.getObject().isResource()) {
                continue;
            }

            Resource codeListNode = stmt.getObject().asResource();

            Resource codeListType = ontModel.getResource(L111_2009_NAMESPACE + CISELNIK);
            if (!codeListNode.hasProperty(RDF.type, codeListType)) {
                continue;
            }

            Property datasetProperty = ontModel.getProperty(
                    L111_2009_NAMESPACE + MA_V_NKOD_ZASTRESUJICI_DATOVOU_SADU);
            Statement datasetStmt = codeListNode.getProperty(datasetProperty);
            if (datasetStmt != null && datasetStmt.getObject().isResource()) {
                Map<String, Object> codeListObj = new LinkedHashMap<>();
                codeListObj.put("typ", CISELNIK_JSON_LD);
                codeListObj.put(DATOVA_SADA_V_NKOD, datasetStmt.getObject().asResource().getURI());
                conceptObj.put(INSTANCE_DEFINOVANY_CISELNIKEM, codeListObj);
            }
        }
    }

    private Statement findPropertyByLocalName(Resource concept, String localName) {
        StmtIterator propIter = concept.listProperties();

        while (propIter.hasNext()) {
            Statement stmt = propIter.next();
            Property predicate = stmt.getPredicate();
            String propertyUri = predicate.getURI();

            if (propertyUri != null) {
                String propertyLocalName = extractLocalName(propertyUri);
                if (localName.equals(propertyLocalName)) {
                    return stmt;
                }
            }
        }

        return null;
    }

    private List<Statement> findAllPropertiesByLocalName(Resource concept, String localName) {
        List<Statement> matches = new ArrayList<>();
        StmtIterator propIter = concept.listProperties();

        while (propIter.hasNext()) {
            Statement stmt = propIter.next();
            String propertyUri = stmt.getPredicate().getURI();
            if (propertyUri != null && localName.equals(extractLocalName(propertyUri))) {
                matches.add(stmt);
            }
        }

        return matches;
    }

    private String extractLocalName(String uri) {
        if (uri == null) {
            return null;
        }

        int lastSlash = uri.lastIndexOf('/');
        int lastHash = uri.lastIndexOf('#');
        int lastSeparator = Math.max(lastSlash, lastHash);

        if (lastSeparator >= 0 && lastSeparator < uri.length() - 1) {
            return uri.substring(lastSeparator + 1);
        }

        return uri;
    }

    private void addDescriptionProperty(Resource concept, Map<String, Object> conceptObj,
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
