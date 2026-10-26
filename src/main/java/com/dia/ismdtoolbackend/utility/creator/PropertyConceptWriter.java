package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.models.concept.*;
import com.dia.ismdtoolbackend.utility.URIGenerator;
import com.dia.ismdtoolbackend.utility.UtilityMethods;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.ontology.OntProperty;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;

import static com.dia.ismdtoolbackend.constants.VocabularyConstants.*;

/** Builds a property concept. */
@Slf4j
final class PropertyConceptWriter {

    private final OntModel ontModel;
    private final URIGenerator uriGenerator;
    private final ConceptCharacteristicWriters writers;

    PropertyConceptWriter(ConceptCharacteristicWriters writers) {
        this.writers = writers;
        this.ontModel = writers.context().ontModel();
        this.uriGenerator = writers.context().uriGenerator();
    }

    Resource createPropertyResource(PropertyConceptModel propModel) {
        String propertyURI;
        if (propModel.getIdentifier() != null && UtilityMethods.isValidIRI(propModel.getIdentifier())) {
            propertyURI = propModel.getIdentifier();
            log.info("Using custom IRI from identifier: {}", propertyURI);
        } else {
            String name = writers.getNameForUriGeneration(propModel.getNameModel());
            propertyURI = uriGenerator.generateConceptURI(name, propModel.getIdentifier());
        }

        OntProperty propertyResource = ontModel.createDatatypeProperty(propertyURI);

        propertyResource.addProperty(RDF.type, SKOS.Concept);
        propertyResource.addProperty(RDF.type, OWL2.DatatypeProperty);
        propertyResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + POJEM));
        propertyResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + VLASTNOST));

        writers.addCommonMetadata(propertyResource, propModel);

        addPropertySpecificMetadata(propertyResource, propModel);

        log.info("Created property resource: {}", propertyURI);
        return propertyResource;
    }

    void addPropertySpecificMetadata(Resource propertyResource, PropertyConceptModel propModel) {
        if (propModel.getDomain() != null && !propModel.getDomain().trim().isEmpty()) {
            writers.relations().addResourceReference(propertyResource, RDFS.domain, propModel.getDomain());
        }

        writers.relations().addRangeInformation(propertyResource, propModel.getDataType());

        if (propModel.getSuperProperty() != null && !propModel.getSuperProperty().isEmpty()) {
            for (String superProperty : propModel.getSuperProperty()) {
                if (superProperty != null && !superProperty.trim().isEmpty()) {
                    writers.relations().addSuperProperty(propertyResource, superProperty);
                }
            }
        }

        writers.governance().addIsInPPDF(propertyResource, propModel.getIsInPPDF());
        addPropertyDataClassification(propertyResource, propModel);
        addPropertyGovernanceMetadata(propertyResource, propModel);
    }

    void addPropertyDataClassification(Resource propertyResource, PropertyConceptModel propModel) {
        writers.classification().addDataClassification(propertyResource, propModel.getIsPublic(), propModel.getPrivacyProvisions());
    }

    void addPropertyGovernanceMetadata(Resource propertyResource, PropertyConceptModel propModel) {
        writers.governance().addSharedGovernanceMetadata(propertyResource, propModel.getAgendaCode(), propModel.getAgendaSystemCode(),
                propModel.getSharingMethod(), propModel.getAcquisitionMethod(), propModel.getContentType(),
                propModel.getPrivacyProvisions());
    }
}
