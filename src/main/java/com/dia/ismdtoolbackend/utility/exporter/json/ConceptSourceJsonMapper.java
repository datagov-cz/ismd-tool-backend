package com.dia.ismdtoolbackend.utility.exporter.json;

import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.util.*;

import static com.dia.ismdtoolbackend.constants.VocabularyConstants.*;

/** Maps legal and non-legal sources, including their digital documents. */
@Slf4j
final class ConceptSourceJsonMapper {

    void addSourceProperties(Resource concept, Map<String, Object> conceptObj,
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

    void addSourceProperty(Resource concept, Map<String, Object> conceptObj, OntModel ontModel, String propertyName, String jsonFieldName) {
        String fullPropertyUri = OFN_NAMESPACE + propertyName;
        Property ofnProperty = ontModel.getProperty(fullPropertyUri);

        if (concept.hasProperty(ofnProperty)) {
            log.debug("Adding source property {} to concept {}", jsonFieldName, concept.getURI());
            RdfJsonValues.addResourceArrayProperty(concept, ofnProperty, jsonFieldName, conceptObj);
        }
    }

    void addNonLegislativeSourceProperty(Resource concept, Map<String, Object> conceptObj,
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

    Property findSourceProperty(Resource concept, OntModel ontModel, String namespace, String propertyName) {
        Property effectiveProperty = ontModel.getProperty(namespace + propertyName);

        if (concept.hasProperty(effectiveProperty)) {
            return effectiveProperty;
        }
        return null;
    }

    List<Map<String, Object>> extractDigitalDocuments(StmtIterator propIter, OntModel ontModel) {
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

    Map<String, Object> createDigitalDocumentObject(Resource digitalDoc, OntModel ontModel) {
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

    List<Statement> findAllPropertiesByLocalName(Resource concept, String localName) {
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

    String extractLocalName(String uri) {
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
}
