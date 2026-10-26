package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.models.concept.*;
import com.dia.utility.URIGenerator;
import com.dia.utility.UtilityMethods;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.ontology.OntProperty;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;

import static com.dia.constants.VocabularyConstants.*;

/** Builds a relationship concept. */
@Slf4j
final class RelationshipConceptWriter {

    private final OntModel ontModel;
    private final URIGenerator uriGenerator;
    private final ConceptCharacteristicWriters writers;

    RelationshipConceptWriter(ConceptCharacteristicWriters writers) {
        this.writers = writers;
        this.ontModel = writers.context().ontModel();
        this.uriGenerator = writers.context().uriGenerator();
    }

    Resource createRelationshipResource(RelationshipConceptModel relModel) {
        String relationshipURI;
        if (relModel.getIdentifier() != null && UtilityMethods.isValidIRI(relModel.getIdentifier())) {
            relationshipURI = relModel.getIdentifier();
            log.info("Using custom IRI from identifier: {}", relationshipURI);
        } else {
            String name = writers.getNameForUriGeneration(relModel.getNameModel());
            relationshipURI = uriGenerator.generateConceptURI(name, relModel.getIdentifier());
        }

        OntProperty relationshipResource = ontModel.createObjectProperty(relationshipURI);

        relationshipResource.addProperty(RDF.type, SKOS.Concept);
        relationshipResource.addProperty(RDF.type, OWL2.ObjectProperty);
        relationshipResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + POJEM));
        relationshipResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + VZTAH));

        writers.addCommonMetadata(relationshipResource, relModel);

        addRelationshipSpecificMetadata(relationshipResource, relModel);

        log.info("Created relationship resource: {}", relationshipURI);
        return relationshipResource;
    }

    void addRelationshipSpecificMetadata(Resource relationshipResource, RelationshipConceptModel relModel) {
       writers.relations().addDomain(relationshipResource, relModel);
       writers.relations().addRange(relationshipResource, relModel);
       writers.relations().addSuperRelation(relationshipResource, relModel);

       writers.governance().addIsInPPDF(relationshipResource, relModel.getIsInPPDF());
       addRelationshipDataClassification(relationshipResource, relModel);
       addRelationshipGovernanceMetadata(relationshipResource, relModel);
    }

    void addRelationshipDataClassification(Resource relationshipResource, RelationshipConceptModel relModel) {
        writers.classification().addDataClassification(relationshipResource, relModel.getIsPublic(), relModel.getPrivacyProvisions());
    }

    void addRelationshipGovernanceMetadata(Resource relationshipResource, RelationshipConceptModel relModel) {
        writers.governance().addSharedGovernanceMetadata(relationshipResource, relModel.getAgendaCode(), relModel.getAgendaSystemCode(),
                relModel.getSharingMethod(), relModel.getAcquisitionMethod(), relModel.getContentType(),
                relModel.getPrivacyProvisions());
    }
}
