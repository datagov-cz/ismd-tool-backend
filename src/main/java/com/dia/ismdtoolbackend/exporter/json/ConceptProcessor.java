package com.dia.ismdtoolbackend.exporter.json;

import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;

import java.util.*;

import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.*;

@Slf4j
public class ConceptProcessor {

    public ConceptData processAllConcepts(OntModel ontModel, ModelStructure structure) {
        log.debug("Processing concepts for JSON export");

        Set<Resource> conceptTypes = getConceptTypes(ontModel);
        List<Map<String, Object>> concepts = new ArrayList<>();

        for (Resource resource : structure.getResourceMap().values()) {
            if (isConceptResource(resource, conceptTypes)) {
                try {
                    Map<String, Object> conceptObject = createConceptObject(resource, ontModel, structure);
                    concepts.add(conceptObject);
                } catch (Exception e) {
                    log.warn("Could not process concept: {}", resource.getURI(), e);
                }
            }
        }

        log.debug("Processed {} concepts", concepts.size());
        return ConceptData.builder()
                .concepts(concepts)
                .totalConceptCount(concepts.size())
                .build();
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

        addMultilingualProperty(concept, SKOS.prefLabel, NAZEV, conceptObj, ontModel);
        addAlternativeNames(concept, conceptObj, ontModel, structure.getEffectiveNamespace());

        Property definitionProperty = ontModel.createProperty(SKOS_NS + "definition");
        addMultilingualProperty(concept, definitionProperty, DEFINICE, conceptObj, ontModel);

        Property descriptionProperty = ontModel.createProperty(DCT_NS + "description");
        addMultilingualProperty(concept, descriptionProperty, POPIS, conceptObj, ontModel);

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

        for (String[] mapping : typeMapping) {
            if (concept.hasProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + mapping[0]))) {
                types.add(mapping[1]);
            }
        }

        return types;
    }

    private void addMultilingualProperty(Resource concept, Property property, String jsonProperty,
                                         Map<String, Object> conceptObj, OntModel ontModel) {
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

            String lang = propStmt.getLanguage();
            if (lang == null || lang.isEmpty()) {
                lang = "cs";
            }

            if (propObj.containsKey(lang)) {
                Object existingValue = propObj.get(lang);
                List<Object> langArray;
                if (existingValue instanceof List) {
                    langArray = (List<Object>) existingValue;
                } else {
                    langArray = new ArrayList<>();
                    langArray.add(existingValue);
                }
                langArray.add(value);
                propObj.put(lang, langArray);
            } else {
                propObj.put(lang, value);
            }
            hasNonEmptyValue = true;
        }

        if (hasNonEmptyValue && !propObj.isEmpty()) {
            conceptObj.put(jsonProperty, propObj);
        }
    }

    private void addAlternativeNames(Resource concept, Map<String, Object> conceptObj,
                                     OntModel ontModel, String effectiveNamespace) {
        Property anPropDefault = ontModel.getProperty(DEFAULT_NS + ALTERNATIVNI_NAZEV);
        Property anPropCustom = ontModel.getProperty(effectiveNamespace + ALTERNATIVNI_NAZEV);

        StmtIterator stmtIter = concept.listProperties(anPropDefault);
        if (!stmtIter.hasNext()) {
            stmtIter = concept.listProperties(anPropCustom);
        }

        if (!stmtIter.hasNext()) {
            return;
        }

        Map<String, Object> altNamesObj = new LinkedHashMap<>();
        boolean hasNonEmptyValue = false;

        while (stmtIter.hasNext()) {
            Statement stmt = stmtIter.next();
            String value = stmt.getString();
            if (value == null || value.isEmpty()) {
                continue;
            }

            String lang = stmt.getLanguage();
            if (lang == null || lang.isEmpty()) {
                lang = "cs";
            }

            if (altNamesObj.containsKey(lang)) {
                Object existingValue = altNamesObj.get(lang);
                List<Object> langArray;
                if (existingValue instanceof List) {
                    langArray = (List<Object>) existingValue;
                } else {
                    langArray = new ArrayList<>();
                    langArray.add(existingValue);
                }
                langArray.add(value);
                altNamesObj.put(lang, langArray);
            } else {
                altNamesObj.put(lang, value);
            }
            hasNonEmptyValue = true;
        }

        if (hasNonEmptyValue && !altNamesObj.isEmpty()) {
            conceptObj.put(ALTERNATIVNI_NAZEV, altNamesObj);
        }
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
                DEFINUJICI_USTANOVENI, DEFINUJICI_USTANOVENI);
        addSourceProperty(concept, conceptObj, ontModel, effectiveNamespace,
                SOUVISEJICI_USTANOVENI, SOUVISEJICI_USTANOVENI);
        addNonLegislativeSourceProperty(concept, conceptObj, ontModel, effectiveNamespace,
                DEFINUJICI_NELEGISLATIVNI_ZDROJ, DEFINUJICI_NELEGISLATIVNI_ZDROJ);
        addNonLegislativeSourceProperty(concept, conceptObj, ontModel, effectiveNamespace,
                SOUVISEJICI_NELEGISLATIVNI_ZDROJ, SOUVISEJICI_NELEGISLATIVNI_ZDROJ);
    }

    private void addSourceProperty(Resource concept, Map<String, Object> conceptObj, OntModel ontModel,
                                   String namespace, String propertyName, String jsonFieldName) {
        Property customProperty = ontModel.getProperty(namespace + propertyName);
        Property defaultProperty = ontModel.getProperty(DEFAULT_NS + propertyName);

        Property sourceProperty = null;
        if (concept.hasProperty(customProperty)) {
            sourceProperty = customProperty;
        } else if (concept.hasProperty(defaultProperty)) {
            sourceProperty = defaultProperty;
        }

        if (sourceProperty != null) {
            addResourceArrayProperty(concept, sourceProperty, jsonFieldName, conceptObj);
        }
    }

    private void addNonLegislativeSourceProperty(Resource concept, Map<String, Object> conceptObj,
                                                 OntModel ontModel, String namespace,
                                                 String propertyName, String jsonFieldName) {
        Property customProperty = ontModel.getProperty(namespace + propertyName);
        Property defaultProperty = ontModel.getProperty(DEFAULT_NS + propertyName);

        Property sourceProperty = null;
        if (concept.hasProperty(customProperty)) {
            sourceProperty = customProperty;
        } else if (concept.hasProperty(defaultProperty)) {
            sourceProperty = defaultProperty;
        }

        if (sourceProperty == null) {
            return;
        }

        StmtIterator propIter = concept.listProperties(sourceProperty);
        if (!propIter.hasNext()) {
            return;
        }

        List<Map<String, Object>> sourceArray = new ArrayList<>();

        while (propIter.hasNext()) {
            Statement propStmt = propIter.next();
            if (propStmt.getObject().isResource()) {
                Resource digitalDoc = propStmt.getObject().asResource();
                Property schemaUrlProperty = ontModel.createProperty("http://schema.org/url");
                Statement urlStmt = digitalDoc.getProperty(schemaUrlProperty);

                if (urlStmt != null) {
                    Map<String, Object> docObj = new LinkedHashMap<>();
                    if (urlStmt.getObject().isResource()) {
                        docObj.put("url", urlStmt.getObject().asResource().getURI());
                    } else if (urlStmt.getObject().isLiteral()) {
                        docObj.put("url", urlStmt.getString());
                    }

                    if (docObj.containsKey("url")) {
                        sourceArray.add(docObj);
                    }
                }
            }
        }

        if (!sourceArray.isEmpty()) {
            conceptObj.put(jsonFieldName, sourceArray);
        }
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
        StmtIterator subClassIter = concept.listProperties(RDFS.subClassOf);
        if (subClassIter.hasNext()) {
            List<String> hierarchyArray = new ArrayList<>();

            while (subClassIter.hasNext()) {
                Statement stmt = subClassIter.next();
                if (stmt.getObject().isResource()) {
                    hierarchyArray.add(stmt.getObject().asResource().getURI());
                }
            }

            if (!hierarchyArray.isEmpty()) {
                conceptObj.put(NADRAZENA_TRIDA, hierarchyArray);
            }
        }

        StmtIterator subPropertyIter = concept.listProperties(RDFS.subPropertyOf);
        if (subPropertyIter.hasNext()) {
            List<String> superPropertyArray = new ArrayList<>();

            while (subPropertyIter.hasNext()) {
                Statement stmt = subPropertyIter.next();
                if (stmt.getObject().isResource()) {
                    superPropertyArray.add(stmt.getObject().asResource().getURI());
                }
            }

            if (!superPropertyArray.isEmpty()) {
                if (concept.hasProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + VZTAH))) {
                    conceptObj.put("nadřazený-vztah", superPropertyArray);
                } else if (concept.hasProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + VLASTNOST))) {
                    conceptObj.put("nadřazená-vlastnost", superPropertyArray);
                }
            }
        }
    }

    private void addGovernanceProperties(Resource concept, Map<String, Object> conceptObj,
                                         OntModel ontModel, String namespace) {
        addGovernanceProperty(concept, conceptObj, ontModel, namespace, ZPUSOB_SDILENI, "způsob-sdílení-údajů");
        addGovernanceProperty(concept, conceptObj, ontModel, namespace, ZPUSOB_ZISKANI, "způsob-získání-údajů");
        addGovernanceProperty(concept, conceptObj, ontModel, namespace, TYP_OBSAHU, "typ-obsahu-údajů");
    }

    private void addGovernanceProperty(Resource concept, Map<String, Object> conceptObj,
                                       OntModel ontModel, String namespace,
                                       String propertyName, String jsonFieldName) {
        Property customProperty = ontModel.getProperty(namespace + propertyName);
        Property defaultProperty = ontModel.getProperty(DEFAULT_NS + propertyName);

        Property property = null;
        if (concept.hasProperty(customProperty)) {
            property = customProperty;
        } else if (concept.hasProperty(defaultProperty)) {
            property = defaultProperty;
        }

        if (property == null) {
            return;
        }

        List<String> allValues = new ArrayList<>();
        StmtIterator propIter = concept.listProperties(property);

        while (propIter.hasNext()) {
            Statement propStmt = propIter.next();
            String value;

            if (propStmt.getObject().isLiteral()) {
                value = propStmt.getString();
            } else if (propStmt.getObject().isResource()) {
                value = propStmt.getObject().asResource().getURI();
            } else {
                continue;
            }

            if (value != null && !value.trim().isEmpty()) {
                if (value.contains(";")) {
                    String[] splitValues = value.split(";");
                    for (String singleValue : splitValues) {
                        String trimmedValue = singleValue.trim();
                        if (!trimmedValue.isEmpty()) {
                            allValues.add(trimmedValue);
                        }
                    }
                } else {
                    allValues.add(value.trim());
                }
            }
        }

        if (!allValues.isEmpty()) {
            conceptObj.put(jsonFieldName, allValues);
        }
    }

    private void addMetadataProperties(Resource concept, Map<String, Object> conceptObj,
                                       OntModel ontModel, String namespace) {
        Property ppdfDefault = ontModel.getProperty(DEFAULT_NS + JE_PPDF);
        Property ppdfCustom = ontModel.getProperty(namespace + JE_PPDF);

        Statement stmt = concept.getProperty(ppdfDefault);
        if (stmt == null) {
            stmt = concept.getProperty(ppdfCustom);
        }

        if (stmt != null && stmt.getObject().isLiteral()) {
            boolean value = stmt.getBoolean();
            conceptObj.put(JE_PPDF, value);
        }

        addSingleResourceProperty(concept, conceptObj, ontModel, namespace, AIS);

        addSingleResourceProperty(concept, conceptObj, ontModel, namespace, AGENDA);

        Property suppDefault = ontModel.getProperty(DEFAULT_NS + USTANOVENI_NEVEREJNOST);
        Property suppCustom = ontModel.getProperty(namespace + USTANOVENI_NEVEREJNOST);

        if (concept.hasProperty(suppDefault)) {
            addResourceArrayProperty(concept, suppDefault, USTANOVENI_NEVEREJNOST, conceptObj);
        } else if (concept.hasProperty(suppCustom)) {
            addResourceArrayProperty(concept, suppCustom, USTANOVENI_NEVEREJNOST, conceptObj);
        }
    }

    private void addSingleResourceProperty(Resource concept, Map<String, Object> conceptObj,
                                           OntModel ontModel, String namespace, String propertyName) {
        Property defaultProperty = ontModel.getProperty(DEFAULT_NS + propertyName);
        Property customProperty = ontModel.getProperty(namespace + propertyName);

        Statement stmt = null;
        if (concept.hasProperty(defaultProperty)) {
            stmt = concept.getProperty(defaultProperty);
        } else if (concept.hasProperty(customProperty)) {
            stmt = concept.getProperty(customProperty);
        }

        if (stmt != null && stmt.getObject().isResource()) {
            conceptObj.put(propertyName, stmt.getObject().asResource().getURI());
        }
    }
}
