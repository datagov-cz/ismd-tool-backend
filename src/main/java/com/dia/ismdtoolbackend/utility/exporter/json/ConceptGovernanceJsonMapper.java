package com.dia.ismdtoolbackend.utility.exporter.json;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;

import java.util.*;

import static com.dia.constants.VocabularyConstants.*;

/** Maps sharing, acquisition and content type, plus the PPDF flag, AIS and agenda. */
final class ConceptGovernanceJsonMapper {

    private static final String A104_NAMESPACE = "https://slovník.gov.cz/agendový/104/pojem/";

    void addGovernanceProperties(Resource concept, Map<String, Object> conceptObj,
                                         OntModel ontModel) {
        addGovernancePropertyArray(concept, conceptObj, ontModel);
        addSingleGovernanceProperty(concept, conceptObj, ontModel, ZPUSOB_ZISKANI, ZPUSOB_ZISKANI_ALT);
        addSingleGovernanceProperty(concept, conceptObj, ontModel, TYP_OBSAHU, TYP_OBSAHU_ALT);
    }

    void addGovernancePropertyArray(Resource concept, Map<String, Object> conceptObj,
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

    void addSingleGovernanceProperty(Resource concept, Map<String, Object> conceptObj,
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

    List<String> extractGovernanceValues(StmtIterator propIter) {
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

    String extractStatementValue(Statement propStmt) {
        if (propStmt.getObject().isLiteral()) {
            return propStmt.getString();
        } else if (propStmt.getObject().isResource()) {
            return propStmt.getObject().asResource().getURI();
        }
        return null;
    }

    void addSplitValues(String value, List<String> targetList) {
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

    void addMetadataProperties(Resource concept, Map<String, Object> conceptObj,
                                       OntModel ontModel) {
        addPpdfProperty(concept, conceptObj, ontModel);

        addMetadataProperty(concept, conceptObj, ontModel, AIS, UDAJE_AIS);

        addMetadataProperty(concept, conceptObj, ontModel, AGENDA, AGENDA_LONG);
    }

    void addPpdfProperty(Resource concept, Map<String, Object> conceptObj,
                                 OntModel ontModel) {
        Property ppdfProperty = ontModel.getProperty(A104_NAMESPACE + JE_PPDF_LONG);

        Statement stmt = concept.getProperty(ppdfProperty);
        if (stmt != null && stmt.getObject().isLiteral()) {
            boolean value = stmt.getBoolean();
            conceptObj.put(JE_PPDF, value);
        }
    }

    void addMetadataProperty(Resource concept, Map<String, Object> conceptObj,
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
}
