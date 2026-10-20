package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.models.concept.*;
import com.dia.utility.URIGenerator;
import com.dia.utility.UtilityMethods;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;

import static com.dia.constants.VocabularyConstants.*;

/** Builds a class concept. */
@Slf4j
final class ClassConceptWriter {

    private final OntModel ontModel;
    private final URIGenerator uriGenerator;
    private final ConceptCharacteristicWriters writers;

    ClassConceptWriter(ConceptCharacteristicWriters writers) {
        this.writers = writers;
        this.ontModel = writers.context().ontModel();
        this.uriGenerator = writers.context().uriGenerator();
    }

    Resource createClassResource(ClassConceptModel classModel) {
        String classURI;
        if (classModel.getIdentifier() != null && UtilityMethods.isValidIRI(classModel.getIdentifier())) {
            classURI = classModel.getIdentifier();
            log.info("Using custom IRI from identifier: {}", classURI);
        } else {
            String name = writers.getNameForUriGeneration(classModel.getNameModel());
            classURI = uriGenerator.generateConceptURI(name, classModel.getIdentifier());
        }

        Resource classResource = ontModel.createResource(classURI);

        classResource.addProperty(RDF.type, SKOS.Concept);
        classResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + POJEM));
        classResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + TRIDA));

        addSpecificClassType(classResource, classModel.getType());


        writers.addCommonMetadata(classResource, classModel);

        addClassSpecificMetadata(classResource, classModel);

        log.info("Created class resource: {}", classURI);
        return classResource;
    }

    void addSpecificClassType(Resource classResource, String type) {
        if (type.contains("subjekt")) {
            classResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + TSP));
        } else if (type.contains("objekt")) {
            classResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + TOP));
        }
    }

    void addClassSpecificMetadata(Resource classResource, ClassConceptModel classModel) {
        writers.governance().addIsInPPDF(classResource, classModel.getIsInPPDF());
        writers.governance().addSharedGovernanceMetadata(classResource, classModel.getAgendaCode(), classModel.getAgendaSystemCode(),
                classModel.getSharingMethod(), classModel.getAcquisitionMethod(), classModel.getContentType(),
                classModel.getPrivacyProvisions());
        writers.classification().addDataClassification(classResource, classModel.getIsPublic(), classModel.getPrivacyProvisions());
        writers.relations().addBroaderConcept(classResource, classModel);
        writers.codeLists().addCodeListDataset(classResource, classModel.getCodeListIri(), classModel.getCodeListDataset());
    }
}
