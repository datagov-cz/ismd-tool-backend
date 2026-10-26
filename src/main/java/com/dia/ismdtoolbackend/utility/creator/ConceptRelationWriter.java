package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.models.concept.*;
import com.dia.ismdtoolbackend.utility.DataTypeConverter;
import com.dia.ismdtoolbackend.utility.URIGenerator;
import com.dia.ismdtoolbackend.utility.UtilityMethods;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;

import java.util.List;

/** Writes the scheme link, exact matches, hierarchy links, and domain and range. */
@Slf4j
final class ConceptRelationWriter {

    private final OntModel ontModel;
    private final URIGenerator uriGenerator;

    ConceptRelationWriter(ConceptBuildContext context) {
        this.ontModel = context.ontModel();
        this.uriGenerator = context.uriGenerator();
    }

    void addInScheme(Resource resource, ConceptCreateModel model) {
        String graphName = model.getOntologyGraphName();
        if (graphName == null || graphName.isBlank()) {
            return;
        }
        resource.addProperty(SKOS.inScheme, ontModel.createResource(graphName));
    }

    void addMatchMetadata(Resource resource, ConceptCreateModel model) {
        if (model.getExactMatch() != null && !model.getExactMatch().isEmpty()) {
            addExactMatch(resource, model.getExactMatch());
        }
    }

    void addExactMatch(Resource resource, List<String> exactMatchList) {
        Property exactMatchProperty = ontModel.createProperty("http://www.w3.org/2004/02/skos/core#exactMatch");

        for (String exactMatch : exactMatchList) {
            if (exactMatch != null && !exactMatch.trim().isEmpty()) {
                String trimmedIri = exactMatch.trim();
                if (UtilityMethods.isValidIRI(trimmedIri)) {
                    resource.addProperty(exactMatchProperty, ontModel.createResource(trimmedIri));
                    log.debug("Added exactMatch IRI: {}", trimmedIri);
                } else {
                    log.warn("Invalid IRI for exactMatch, skipping: {}", trimmedIri);
                }
            }
        }

    }

    void addBroaderConcept(Resource classResource, ClassConceptModel classModel) {
        if (classModel.getBroaderConcept() != null && !classModel.getBroaderConcept().isEmpty()) {
            for (String broaderConcept : classModel.getBroaderConcept()) {
                if (broaderConcept != null && !broaderConcept.trim().isEmpty()) {
                    addBroaderConcept(classResource, broaderConcept);
                }
            }
        }
    }

    void addBroaderConcept(Resource resource, String broaderConcept) {
        Property hierarchyProperty = ontModel.createProperty(
                uriGenerator.getEffectiveNamespace() + "nadřazená-třída");


        String trimmedConcept = broaderConcept.trim();
        if (!trimmedConcept.isEmpty()) {
            String broaderURI;
            if (UtilityMethods.isValidIRI(trimmedConcept)) {
                broaderURI = trimmedConcept;
            } else {
                broaderURI = uriGenerator.generateConceptURI(trimmedConcept, null);
            }
            resource.addProperty(RDFS.subClassOf, ontModel.createResource(broaderURI));
            resource.addProperty(hierarchyProperty, ontModel.createResource(broaderURI));
        }
    }

    void addSuperProperty(Resource resource, String superProperty) {
        String superURI;
        if (UtilityMethods.isValidIRI(superProperty)) {
            superURI = superProperty;
        } else {
            superURI = uriGenerator.generateConceptURI(superProperty, null);
        }
        resource.addProperty(RDFS.subPropertyOf, ontModel.createResource(superURI));
    }

    void addDomain(Resource relationshipResource, RelationshipConceptModel relModel) {
        if (relModel.getDomain() != null && !relModel.getDomain().trim().isEmpty()) {
            addResourceReference(relationshipResource, RDFS.domain, relModel.getDomain());
        }
    }

    void addRange(Resource relationshipResource, RelationshipConceptModel relModel) {
        if (relModel.getRange() != null && !relModel.getRange().trim().isEmpty()) {
            addResourceReference(relationshipResource, RDFS.range, relModel.getRange());
        }
    }

    void addSuperRelation(Resource relationshipResource, RelationshipConceptModel relModel) {
        if (relModel.getSuperRelation() != null && !relModel.getSuperRelation().isEmpty()) {
            for (String superRelation : relModel.getSuperRelation()) {
                if (superRelation != null && !superRelation.trim().isEmpty()) {
                    addSuperProperty(relationshipResource, superRelation);
                }
            }
        }
    }

    void addResourceReference(Resource subject, Property property, String referenceName) {
        if (UtilityMethods.isValidIRI(referenceName)) {
            subject.addProperty(property, ontModel.createResource(referenceName));
        } else {
            String conceptUri = uriGenerator.generateConceptURI(referenceName, null);
            subject.addProperty(property, ontModel.createResource(conceptUri));
        }
    }

    void addRangeInformation(Resource propertyResource, String dataType) {
        if (dataType == null || dataType.trim().isEmpty()) {
            propertyResource.addProperty(RDFS.range, ontModel.createResource(RDFS.Literal.getURI()));
            return;
        }

        String trimmedDataType = dataType.trim();

        if (trimmedDataType.startsWith("xsd:")) {
            String localName = trimmedDataType.substring(4);
            if (DataTypeConverter.isValidXSDType(localName)) {
                propertyResource.addProperty(RDFS.range,
                        ontModel.createResource("http://www.w3.org/2001/XMLSchema#" + localName));
                return;
            }
        }

        if (DataTypeConverter.isUri(trimmedDataType)) {
            propertyResource.addProperty(RDFS.range, ontModel.createResource(trimmedDataType));
            return;
        }

        propertyResource.addProperty(RDFS.range, ontModel.createResource(RDFS.Literal.getURI()));
    }
}
