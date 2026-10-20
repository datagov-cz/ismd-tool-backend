package com.dia.ismdtoolbackend.utility.exporter.json;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;

import java.util.*;

import static com.dia.constants.VocabularyConstants.*;

/** Maps exact matches, domain and range, and the class, property and SKOS hierarchies. */
final class ConceptRelationJsonMapper {

    void addExactMatchProperty(Resource concept, Map<String, Object> conceptObj, OntModel ontModel) {
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

    void addDomainAndRange(Resource concept, Map<String, Object> conceptObj) {
        Statement domainStmt = concept.getProperty(RDFS.domain);
        if (domainStmt != null && domainStmt.getObject().isResource()) {
            String domainUri = domainStmt.getObject().asResource().getURI();
            if (domainUri != null) {
                conceptObj.put(DEFINICNI_OBOR, domainUri);
            }
        }

        Statement rangeStmt = concept.getProperty(RDFS.range);
        if (rangeStmt != null && rangeStmt.getObject().isResource()) {
            String rangeUri = rangeStmt.getObject().asResource().getURI();
            if (rangeUri == null) {
                return;
            }

            if (rangeUri.startsWith(XSD)) {
                conceptObj.put(OBOR_HODNOT, "xsd:" + rangeUri.substring(XSD.length()));
            } else {
                conceptObj.put(OBOR_HODNOT, rangeUri);
            }
        }
    }

    void addHierarchicalRelationships(Resource concept, Map<String, Object> conceptObj, OntModel ontModel) {
        addSubClassRelationships(concept, conceptObj);
        addSubPropertyRelationships(concept, conceptObj, ontModel);
        addSkosBroaderRelationships(concept, conceptObj);
    }

    void addSkosBroaderRelationships(Resource concept, Map<String, Object> conceptObj) {
        StmtIterator broaderIter = concept.listProperties(SKOS.broader);
        if (!broaderIter.hasNext()) {
            return;
        }

        List<String> broaderArray = RdfJsonValues.extractResourceURIs(broaderIter);

        if (!broaderArray.isEmpty()) {
            conceptObj.put("nadřazený-pojem", broaderArray);
        }
    }

    void addSubClassRelationships(Resource concept, Map<String, Object> conceptObj) {
        StmtIterator subClassIter = concept.listProperties(RDFS.subClassOf);
        if (!subClassIter.hasNext()) {
            return;
        }

        List<String> hierarchyArray = RdfJsonValues.extractResourceURIs(subClassIter);

        if (!hierarchyArray.isEmpty()) {
            conceptObj.put(NADRAZENA_TRIDA, hierarchyArray);
        }
    }

    void addSubPropertyRelationships(Resource concept, Map<String, Object> conceptObj, OntModel ontModel) {
        StmtIterator subPropertyIter = concept.listProperties(RDFS.subPropertyOf);
        if (!subPropertyIter.hasNext()) {
            return;
        }

        List<String> superPropertyArray = RdfJsonValues.extractResourceURIs(subPropertyIter);

        if (!superPropertyArray.isEmpty()) {
            String propertyKey = determinePropertyKey(concept, ontModel);
            if (propertyKey != null) {
                conceptObj.put(propertyKey, superPropertyArray);
            }
        }
    }

    String determinePropertyKey(Resource concept, OntModel ontModel) {
        if (concept.hasProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + VZTAH))) {
            return NADRAZENY_VZTAH;
        } else if (concept.hasProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + VLASTNOST))) {
            return NADRAZENA_VLASTNOST;
        }
        return null;
    }
}
