package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.models.concept.*;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaEliParser;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import com.dia.models.OFNBaseModel;
import com.dia.utility.DataTypeConverter;
import com.dia.utility.URIGenerator;
import com.dia.utility.UtilityMethods;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntProperty;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;
import static com.dia.constants.VocabularyConstants.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class ConceptCreator {

    private final URIGenerator uriGenerator = new URIGenerator();
    @Getter
    private OntModel ontModel;

    public Resource createSingleConcept(ConceptCreateModel createModel) {
        initializeModel(createModel);
        String effectiveNamespace = determineEffectiveNamespace(createModel.getOntologyGraphName());
        uriGenerator.setEffectiveNamespace(effectiveNamespace);

        Resource concept = switch (createModel.getConceptTypeEnum()) {
            case TRIDA -> createClassResource((ClassConceptModel) createModel);
            case VLASTNOST -> createPropertyResource((PropertyConceptModel) createModel);
            case VZTAH -> createRelationshipResource((RelationshipConceptModel) createModel);
            case KONCEPT -> throw new IllegalArgumentException(
                    "Nelze vytvořit pojem bez konkrétního typu (TRIDA/VLASTNOST/VZTAH).");
        };

        String conceptURI = concept.getURI();

        Model cleanModel = ModelFactory.createDefaultModel();

        ontModel.listStatements(concept, null, (RDFNode) null).forEachRemaining(stmt -> {
            Resource subj = cleanModel.createResource(conceptURI);
            Property pred = cleanModel.createProperty(stmt.getPredicate().getURI());

            RDFNode obj;
            if (stmt.getObject().isResource()) {
                Resource objRes = stmt.getObject().asResource();
                if (objRes.isAnon()) {
                    // Copy blank node and all its statements
                    Resource blankNode = cleanModel.createResource();
                    objRes.listProperties().forEachRemaining(bnStmt -> {
                        Property bnPred = cleanModel.createProperty(bnStmt.getPredicate().getURI());
                        RDFNode bnObj;
                        if (bnStmt.getObject().isResource()) {
                            bnObj = cleanModel.createResource(bnStmt.getObject().asResource().getURI());
                        } else {
                            bnObj = bnStmt.getObject();
                        }
                        cleanModel.add(blankNode, bnPred, bnObj);
                    });
                    obj = blankNode;
                } else {
                    Resource named = cleanModel.createResource(objRes.getURI());
                    copyCodeListSubject(objRes, named, cleanModel);
                    obj = named;
                }
            } else {
                obj = stmt.getObject();
            }

            cleanModel.add(subj, pred, obj);
        });

        log.info("Created clean model with {} statements for: {}", cleanModel.size(), conceptURI);

        return cleanModel.getResource(conceptURI);
    }

    /**
     * Copies a named číselník's own statements into the clean model. Only subjects typed
     * {@code l111-2009:číselník} are carried over, so ordinary IRI references stay bare references.
     */
    private void copyCodeListSubject(Resource source, Resource target, Model cleanModel) {
        Resource codeListType = source.getModel().getResource(OFN_NAMESPACE_LEGAL + CISELNIK);
        if (!source.hasProperty(RDF.type, codeListType)) {
            return;
        }
        source.listProperties().forEachRemaining(stmt -> {
            Property pred = cleanModel.createProperty(stmt.getPredicate().getURI());
            RDFNode obj = stmt.getObject().isResource()
                    ? cleanModel.createResource(stmt.getObject().asResource().getURI())
                    : stmt.getObject();
            cleanModel.add(target, pred, obj);
        });
    }

    private void initializeModel(ConceptCreateModel createModel) {
        Set<String> requiredBaseClasses = determineRequiredBaseClasses(createModel);
        Set<String> requiredProperties = determineRequiredProperties(createModel);

        OFNBaseModel baseModel = new OFNBaseModel(requiredBaseClasses, requiredProperties);
        this.ontModel = baseModel.getOntModel();

        log.debug("Initialized OntModel with base classes: {} and properties: {}",
                requiredBaseClasses, requiredProperties);
    }

    private Set<String> determineRequiredBaseClasses(ConceptCreateModel createModel) {
        Set<String> classes = new HashSet<>();
        classes.add(POJEM);

        if (createModel instanceof ClassConceptModel classModel) {
            addClassBaseClasses(classes, classModel);
        } else if (createModel instanceof PropertyConceptModel propModel) {
            classes.add(VLASTNOST);
            addPropertyBaseClasses(classes, propModel);
        } else if (createModel instanceof RelationshipConceptModel relModel) {
            classes.add(VZTAH);
            addRelationshipBaseClasses(classes, relModel);
        }

        return classes;
    }

    private void addClassBaseClasses(Set<String> classes, ClassConceptModel classModel) {
        classes.add(TRIDA);
        addTypeSpecificClass(classes, classModel.getType());
        addDataClassificationClasses(classes, classModel);
    }

    private void addPropertyBaseClasses(Set<String> classes, PropertyConceptModel propModel) {
        if (hasPublicDataFromModel(propModel)) {
            classes.add(VEREJNY_UDAJ);
        }
        if (hasPrivateDataFromModel(propModel)) {
            classes.add(NEVEREJNY_UDAJ);
        }
    }

    private void addRelationshipBaseClasses(Set<String> classes, RelationshipConceptModel relModel) {
        if (hasPublicDataFromRelationship(relModel)) {
            classes.add(VEREJNY_UDAJ);
        }
        if (hasPrivateDataFromRelationship(relModel)) {
            classes.add(NEVEREJNY_UDAJ);
        }
    }

    private boolean hasPublicDataFromModel(PropertyConceptModel model) {
        Boolean isPublic = model.getIsPublic();
        return isPublic != null && isPublic &&
                (model.getPrivacyProvisions() == null || model.getPrivacyProvisions().isEmpty());
    }

    private boolean hasPrivateDataFromModel(PropertyConceptModel model) {
        return (model.getPrivacyProvisions() != null && !model.getPrivacyProvisions().isEmpty()) ||
                (model.getIsPublic() != null && !model.getIsPublic());
    }

    private boolean hasPublicDataFromRelationship(RelationshipConceptModel model) {
        Boolean isPublic = model.getIsPublic();
        return isPublic != null && isPublic &&
                (model.getPrivacyProvisions() == null || model.getPrivacyProvisions().isEmpty());
    }

    private boolean hasPrivateDataFromRelationship(RelationshipConceptModel model) {
        return (model.getPrivacyProvisions() != null && !model.getPrivacyProvisions().isEmpty()) ||
                (model.getIsPublic() != null && !model.getIsPublic());
    }

    private void addTypeSpecificClass(Set<String> classes, String type) {
        if (type == null) {
            return;
        }
        if (type.contains("subjekt")) {
            classes.add(TSP);
        } else if (type.contains("objekt")) {
            classes.add(TOP);
        }
    }

    private void addDataClassificationClasses(Set<String> classes, ClassConceptModel classModel) {
        if (Boolean.TRUE.equals(classModel.getIsPublic())) {
            classes.add(VEREJNY_UDAJ);
        }
        if (Boolean.FALSE.equals(classModel.getIsPublic())) {
            classes.add(NEVEREJNY_UDAJ);
        }
    }

    private Set<String> determineRequiredProperties(ConceptCreateModel createModel) {
        Set<String> properties = new HashSet<>();

        addCommonProperties(properties);
        addConditionalCommonProperties(properties, createModel);
        addConceptTypeSpecificProperties(properties, createModel);

        return properties;
    }

    private void addCommonProperties(Set<String> properties) {
        properties.add(NAZEV);
        properties.add(POPIS);
        properties.add(DEFINICE);
    }

    private void addConditionalCommonProperties(Set<String> properties, ConceptCreateModel createModel) {
        if (createModel.getAltNameModel() != null) {
            properties.add(ALTERNATIVNI_NAZEV);
        }

        if (hasLegalSources(createModel)) {
            properties.add(DEFINUJICI_USTANOVENI);
            properties.add(SOUVISEJICI_USTANOVENI);
        }

        if (hasNonLegalSources(createModel)) {
            properties.add(DEFINUJICI_NELEGISLATIVNI_ZDROJ);
            properties.add(SOUVISEJICI_NELEGISLATIVNI_ZDROJ);
            properties.add("schema:url");
            properties.add("dcterms:title");
            properties.add("dcterms:description");
        }
    }

    private void addConceptTypeSpecificProperties(Set<String> properties, ConceptCreateModel createModel) {
        if (createModel instanceof ClassConceptModel classModel) {
            addClassSpecificProperties(properties, classModel);
        } else if (createModel instanceof PropertyConceptModel propModel) {
            addPropertySpecificProperties(properties, propModel);
        } else if (createModel instanceof RelationshipConceptModel relModel) {
            addRelationshipSpecificProperties(properties, relModel);
        }
    }

    private void addClassSpecificProperties(Set<String> properties, ClassConceptModel classModel) {
        if (classModel.getAgendaCode() != null && !classModel.getAgendaCode().trim().isEmpty()) {
            properties.add(AGENDA_LONG);
        }
        if (classModel.getAgendaSystemCode() != null && !classModel.getAgendaSystemCode().trim().isEmpty()) {
            properties.add(UDAJE_AIS);
        }
        if (Boolean.FALSE.equals(classModel.getIsPublic())) {
            properties.add(USTANOVENI_NEVEREJNOST);
        }
        if (hasGovernanceProperties(classModel)) {
            properties.add(ZPUSOB_SDILENI);
            properties.add(ZPUSOB_ZISKANI);
            properties.add(TYP_OBSAHU);
        }
    }

    private void addPropertySpecificProperties(Set<String> properties, PropertyConceptModel propModel) {
        addCommonGovernanceProperties(properties, propModel.getIsInPPDF(), propModel.getAgendaCode(),
                propModel.getAgendaSystemCode(), propModel.getPrivacyProvisions(),
                propModel.getSharingMethod(), propModel.getAcquisitionMethod(), propModel.getContentType());
    }

    private void addRelationshipSpecificProperties(Set<String> properties, RelationshipConceptModel relModel) {
        addCommonGovernanceProperties(properties, relModel.getIsInPPDF(), relModel.getAgendaCode(),
                relModel.getAgendaSystemCode(), relModel.getPrivacyProvisions(),
                relModel.getSharingMethod(), relModel.getAcquisitionMethod(), relModel.getContentType());
    }

    private void addCommonGovernanceProperties(Set<String> properties, Boolean isInPPDF, String agendaCode,
                                                 String agendaSystemCode, List<String> privacyProvisions,
                                                 List<String> sharingMethod, String acquisitionMethod, String contentType) {
        if (Boolean.TRUE.equals(isInPPDF)) {
            properties.add(JE_PPDF_LONG);
        }
        if (agendaCode != null && !agendaCode.trim().isEmpty()) {
            properties.add(AGENDA_LONG);
        }
        if (agendaSystemCode != null && !agendaSystemCode.trim().isEmpty()) {
            properties.add(UDAJE_AIS);
        }
        if (hasPrivacyProvisions(privacyProvisions)) {
            properties.add(USTANOVENI_NEVEREJNOST);
        }
        if (hasGovernancePropertiesValues(sharingMethod, acquisitionMethod, contentType)) {
            properties.add(ZPUSOB_SDILENI);
            properties.add(ZPUSOB_ZISKANI);
            properties.add(TYP_OBSAHU);
        }
    }

    private Resource createClassResource(ClassConceptModel classModel) {
        String classURI;
        if (classModel.getIdentifier() != null && UtilityMethods.isValidIRI(classModel.getIdentifier())) {
            classURI = classModel.getIdentifier();
            log.info("Using custom IRI from identifier: {}", classURI);
        } else {
            String name = getNameForUriGeneration(classModel.getNameModel());
            classURI = uriGenerator.generateConceptURI(name, classModel.getIdentifier());
        }

        Resource classResource = ontModel.createResource(classURI);

        classResource.addProperty(RDF.type, SKOS.Concept);
        classResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + POJEM));
        classResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + TRIDA));

        addSpecificClassType(classResource, classModel.getType());


        addCommonMetadata(classResource, classModel);

        addClassSpecificMetadata(classResource, classModel);

        log.info("Created class resource: {}", classURI);
        return classResource;
    }

    private Resource createPropertyResource(PropertyConceptModel propModel) {
        String propertyURI;
        if (propModel.getIdentifier() != null && UtilityMethods.isValidIRI(propModel.getIdentifier())) {
            propertyURI = propModel.getIdentifier();
            log.info("Using custom IRI from identifier: {}", propertyURI);
        } else {
            String name = getNameForUriGeneration(propModel.getNameModel());
            propertyURI = uriGenerator.generateConceptURI(name, propModel.getIdentifier());
        }

        OntProperty propertyResource;
        if (isObjectProperty(propModel)) {
            propertyResource = ontModel.createObjectProperty(propertyURI);
        } else {
            propertyResource = ontModel.createDatatypeProperty(propertyURI);
        }

        propertyResource.addProperty(RDF.type, SKOS.Concept);
        propertyResource.addProperty(RDF.type, OWL2.DatatypeProperty);
        propertyResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + POJEM));
        propertyResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + VLASTNOST));

        addCommonMetadata(propertyResource, propModel);

        addPropertySpecificMetadata(propertyResource, propModel);

        log.info("Created property resource: {}", propertyURI);
        return propertyResource;
    }

    private Resource createRelationshipResource(RelationshipConceptModel relModel) {
        String relationshipURI;
        if (relModel.getIdentifier() != null && UtilityMethods.isValidIRI(relModel.getIdentifier())) {
            relationshipURI = relModel.getIdentifier();
            log.info("Using custom IRI from identifier: {}", relationshipURI);
        } else {
            String name = getNameForUriGeneration(relModel.getNameModel());
            relationshipURI = uriGenerator.generateConceptURI(name, relModel.getIdentifier());
        }

        OntProperty relationshipResource = ontModel.createObjectProperty(relationshipURI);

        relationshipResource.addProperty(RDF.type, SKOS.Concept);
        relationshipResource.addProperty(RDF.type, OWL2.ObjectProperty);
        relationshipResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + POJEM));
        relationshipResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + VZTAH));

        addCommonMetadata(relationshipResource, relModel);

        addRelationshipSpecificMetadata(relationshipResource, relModel);

        log.info("Created relationship resource: {}", relationshipURI);
        return relationshipResource;
    }

    private void addCommonMetadata(Resource resource, ConceptCreateModel model) {
        addBasicMetadata(resource, model);
        addSourceMetadata(resource, model);
        addMatchMetadata(resource, model);
        addInScheme(resource, model);
    }

    private void addInScheme(Resource resource, ConceptCreateModel model) {
        String graphName = model.getOntologyGraphName();
        if (graphName == null || graphName.isBlank()) {
            return;
        }
        resource.addProperty(SKOS.inScheme, ontModel.createResource(graphName));
    }

    private void addBasicMetadata(Resource resource, ConceptCreateModel model) {
        addPrefLabel(resource, model);
        addDescription(resource, model);
        addDefinition(resource, model);

        if (model.getAltNameModel() != null) {
            addAlternativeNames(resource, model.getAltNameModel());
        }
    }

    private void addPrefLabel(Resource resource, ConceptCreateModel model) {
        if (model.getNameModel() != null && model.getNameModel().getName() != null && !model.getNameModel().getName().isEmpty()) {
            for (Map.Entry<String, String> entry : model.getNameModel().getName().entrySet()) {
                if (entry.getValue() != null && !entry.getValue().trim().isEmpty()) {
                    String languageTag = entry.getKey() != null && !entry.getKey().trim().isEmpty()
                            ? entry.getKey()
                            : DEFAULT_LANG;
                    DataTypeConverter.addTypedProperty(resource, SKOS.prefLabel,
                            entry.getValue().trim(), languageTag, ontModel);
                }
            }
        }
    }

    private void addDescription(Resource resource, ConceptCreateModel model) {
        if (model.getDescriptionModel() != null && model.getDescriptionModel().getDescription() != null && !model.getDescriptionModel().getDescription().isEmpty()) {
            Property descProperty = ontModel.createProperty("http://purl.org/dc/terms/description");
            for (Map.Entry<String, String> entry : model.getDescriptionModel().getDescription().entrySet()) {
                if (entry.getValue() != null && !entry.getValue().trim().isEmpty()) {
                    String languageTag = entry.getKey() != null && !entry.getKey().trim().isEmpty()
                            ? entry.getKey()
                            : DEFAULT_LANG;
                    DataTypeConverter.addTypedProperty(resource, descProperty,
                            entry.getValue().trim(), languageTag, ontModel);
                }
            }
        }
    }

    private void addDefinition(Resource resource, ConceptCreateModel model) {
        if (model.getDefinitionModel() != null && model.getDefinitionModel().getDefinition() != null && !model.getDefinitionModel().getDefinition().isEmpty()) {
            for (Map.Entry<String, String> entry : model.getDefinitionModel().getDefinition().entrySet()) {
                if (entry.getValue() != null && !entry.getValue().trim().isEmpty()) {
                    String languageTag = entry.getKey() != null && !entry.getKey().trim().isEmpty()
                            ? entry.getKey()
                            : DEFAULT_LANG;
                    DataTypeConverter.addTypedProperty(resource, SKOS.definition,
                            entry.getValue().trim(), languageTag, ontModel);
                }
            }
        }
    }

    private void addSourceMetadata(Resource resource, ConceptCreateModel model) {
        addLegalSourceMetadata(resource, model);
        addNonLegalSourceMetadata(resource, model);
    }

    private void addLegalSourceMetadata(Resource resource, ConceptCreateModel model) {
        processLegalSources(resource, model.getDefiningLegalSource(), true);
        processLegalSources(resource, model.getRelatedLegalSource(), false);
    }

    private void addNonLegalSourceMetadata(Resource resource, ConceptCreateModel model) {
        processNonLegalSources(resource, model.getDefiningNonLegalSource(), true);
        processNonLegalSources(resource, model.getRelatedNonLegalSource(), false);
    }

    private void processLegalSources(Resource resource, List<String> sources, boolean isDefining) {
        if (sources == null || sources.isEmpty()) {
            return;
        }

        for (String source : sources) {
            if (isValidSource(source)) {
                processLegalSource(resource, source, isDefining);
            }
        }
    }

    private void processNonLegalSources(Resource resource, List<DigitalObjectModel> sources, boolean isDefining) {
        if (sources == null || sources.isEmpty()) {
            return;
        }

        for (DigitalObjectModel source : sources) {
            if (isValidDigitalObject(source)) {
                processNonLegalSource(resource, source, isDefining);
            } else if (source != null) {
                log.warn("Skipping non-legal source — url is blank or invalid: {}", source.getUrl());
            }
        }
    }

    private boolean isValidSource(String source) {
        return source != null && !source.trim().isEmpty();
    }

    private boolean isValidDigitalObject(DigitalObjectModel source) {
        if (source == null) return false;
        String url = source.getUrl();
        if (url == null || url.trim().isEmpty()) return false;
        return UtilityMethods.isValidUrl(url.trim());
    }

    private void addMatchMetadata(Resource resource, ConceptCreateModel model) {
        if (model.getExactMatch() != null && !model.getExactMatch().isEmpty()) {
            addExactMatch(resource, model.getExactMatch());
        }
    }

    private void addSpecificClassType(Resource classResource, String type) {
        if (type.contains("subjekt")) {
            classResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + TSP));
        } else if (type.contains("objekt")) {
            classResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + TOP));
        }
    }

    private void addClassSpecificMetadata(Resource classResource, ClassConceptModel classModel) {
        addIsInPPDF(classResource, classModel.getIsInPPDF());
        addSharedGovernanceMetadata(classResource, classModel.getAgendaCode(), classModel.getAgendaSystemCode(),
                classModel.getSharingMethod(), classModel.getAcquisitionMethod(), classModel.getContentType(),
                classModel.getPrivacyProvisions());
        addDataClassification(classResource, classModel.getIsPublic(), classModel.getPrivacyProvisions());
        addBroaderConcept(classResource, classModel);
        addCodeListDataset(classResource, classModel.getCodeListIri(), classModel.getCodeListDataset());
    }

    private void addBroaderConcept(Resource classResource, ClassConceptModel classModel) {
        if (classModel.getBroaderConcept() != null && !classModel.getBroaderConcept().isEmpty()) {
            for (String broaderConcept : classModel.getBroaderConcept()) {
                if (broaderConcept != null && !broaderConcept.trim().isEmpty()) {
                    addBroaderConcept(classResource, broaderConcept);
                }
            }
        }
    }

    private void addPropertySpecificMetadata(Resource propertyResource, PropertyConceptModel propModel) {
        if (propModel.getDomain() != null && !propModel.getDomain().trim().isEmpty()) {
            addResourceReference(propertyResource, RDFS.domain, propModel.getDomain());
        }

        addRangeInformation(propertyResource, propModel.getDataType());

        if (propModel.getSuperProperty() != null && !propModel.getSuperProperty().isEmpty()) {
            for (String superProperty : propModel.getSuperProperty()) {
                if (superProperty != null && !superProperty.trim().isEmpty()) {
                    addSuperProperty(propertyResource, superProperty);
                }
            }
        }

        addIsInPPDF(propertyResource, propModel.getIsInPPDF());
        addPropertyDataClassification(propertyResource, propModel);
        addPropertyGovernanceMetadata(propertyResource, propModel);
    }

    private void addPropertyDataClassification(Resource propertyResource, PropertyConceptModel propModel) {
        addDataClassification(propertyResource, propModel.getIsPublic(), propModel.getPrivacyProvisions());
    }

    private void addPropertyGovernanceMetadata(Resource propertyResource, PropertyConceptModel propModel) {
        addSharedGovernanceMetadata(propertyResource, propModel.getAgendaCode(), propModel.getAgendaSystemCode(),
                propModel.getSharingMethod(), propModel.getAcquisitionMethod(), propModel.getContentType(),
                propModel.getPrivacyProvisions());
    }

    private void addRelationshipSpecificMetadata(Resource relationshipResource, RelationshipConceptModel relModel) {
       addDomain(relationshipResource, relModel);
       addRange(relationshipResource, relModel);
       addSuperRelation(relationshipResource, relModel);

       addIsInPPDF(relationshipResource, relModel.getIsInPPDF());
       addRelationshipDataClassification(relationshipResource, relModel);
       addRelationshipGovernanceMetadata(relationshipResource, relModel);
    }

    private void addDomain(Resource relationshipResource, RelationshipConceptModel relModel) {
        if (relModel.getDomain() != null && !relModel.getDomain().trim().isEmpty()) {
            addResourceReference(relationshipResource, RDFS.domain, relModel.getDomain());
        }
    }

    private void addRange(Resource relationshipResource, RelationshipConceptModel relModel) {
        if (relModel.getRange() != null && !relModel.getRange().trim().isEmpty()) {
            addResourceReference(relationshipResource, RDFS.range, relModel.getRange());
        }
    }

    private void addSuperRelation(Resource relationshipResource, RelationshipConceptModel relModel) {
        if (relModel.getSuperRelation() != null && !relModel.getSuperRelation().isEmpty()) {
            for (String superRelation : relModel.getSuperRelation()) {
                if (superRelation != null && !superRelation.trim().isEmpty()) {
                    addSuperProperty(relationshipResource, superRelation);
                }
            }
        }
    }

    private void addRelationshipDataClassification(Resource relationshipResource, RelationshipConceptModel relModel) {
        addDataClassification(relationshipResource, relModel.getIsPublic(), relModel.getPrivacyProvisions());
    }

    private void addRelationshipGovernanceMetadata(Resource relationshipResource, RelationshipConceptModel relModel) {
        addSharedGovernanceMetadata(relationshipResource, relModel.getAgendaCode(), relModel.getAgendaSystemCode(),
                relModel.getSharingMethod(), relModel.getAcquisitionMethod(), relModel.getContentType(),
                relModel.getPrivacyProvisions());
    }

    private void addSharedGovernanceMetadata(Resource resource, String agendaCode, String agendaSystemCode,
                                              List<String> sharingMethod, String acquisitionMethod, String contentType,
                                              List<String> privacyProvisions) {
        if (agendaCode != null && !agendaCode.trim().isEmpty()) {
            addAgenda(resource, agendaCode);
        }
        if (agendaSystemCode != null && !agendaSystemCode.trim().isEmpty()) {
            addAIS(resource, agendaSystemCode);
        }

        addSharingMethodMetadata(sharingMethod, resource);

        if (acquisitionMethod != null && !acquisitionMethod.trim().isEmpty()) {
            addGovernanceProperty(resource, acquisitionMethod, ZPUSOB_ZISKANI);
        }
        if (contentType != null && !contentType.trim().isEmpty()) {
            addGovernanceProperty(resource, contentType, TYP_OBSAHU);
        }

        addPrivacyProvisionsMetadata(resource, privacyProvisions);
    }

    private void addSharingMethodMetadata(List<String> sharingMethod, Resource resource) {
        if (sharingMethod != null && !sharingMethod.isEmpty()) {
            for (String method : sharingMethod) {
                if (method != null && !method.trim().isEmpty()) {
                    addGovernanceProperty(resource, method, ZPUSOB_SDILENI);
                }
            }
        }
    }
    private void addPrivacyProvisionsMetadata(Resource resource, List<String> privacyProvisions) {
        if (privacyProvisions != null && !privacyProvisions.isEmpty()) {
            for (String provision : privacyProvisions) {
                if (provision == null || provision.trim().isEmpty()) continue;
                String canonical = EsbirkaEliParser.canonicalizeHost(provision.trim());
                if (!SparqlIriValidator.isEsbirkaEliIri(canonical)) {
                    log.warn("Skipping privacy provision — not a canonical e-Sbírka ELI IRI: {}", provision.trim());
                    continue;
                }
                Property provisionProperty = ontModel.createProperty(
                        uriGenerator.getEffectiveNamespace() + USTANOVENI_NEVEREJNOST);
                resource.addProperty(provisionProperty, ontModel.createResource(canonical));
            }
        }
    }

    private void addAlternativeNames(Resource resource, AltNameModel altNameModel) {
        if (altNameModel != null && altNameModel.getAltName() != null && !altNameModel.getAltName().isEmpty()) {
            for (Map.Entry<String, List<String>> entry : altNameModel.getAltName().entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                String languageTag = entry.getKey() != null && !entry.getKey().trim().isEmpty()
                    ? entry.getKey()
                    : DEFAULT_LANG;
                for (String value : entry.getValue()) {
                    if (value != null && !value.trim().isEmpty()) {
                        DataTypeConverter.addTypedProperty(resource, SKOS.altLabel,
                            value.trim(), languageTag, ontModel);
                    }
                }
            }
        }
    }

    private void processLegalSource(Resource resource, String source, boolean isDefining) {
        String propertyName = isDefining ? DEFINUJICI_USTANOVENI : SOUVISEJICI_USTANOVENI;
        Property property = ontModel.createProperty(OFN_NAMESPACE + propertyName);

        if (source == null || source.trim().isEmpty()) return;
        String canonical = EsbirkaEliParser.canonicalizeHost(source.trim());
        if (!SparqlIriValidator.isEsbirkaEliIri(canonical)) {
            log.warn("Skipping legal source — not a canonical e-Sbírka ELI IRI: {}", source.trim());
            return;
        }
        resource.addProperty(property, ontModel.createResource(canonical));
    }

    private void processNonLegalSource(Resource resource, DigitalObjectModel source, boolean isDefining) {
        String propertyName = isDefining ? DEFINUJICI_NELEGISLATIVNI_ZDROJ : SOUVISEJICI_NELEGISLATIVNI_ZDROJ;
        Property property = ontModel.createProperty(uriGenerator.getEffectiveNamespace() + propertyName);

        Resource digitalDocument = ontModel.createResource();
        digitalDocument.addProperty(RDF.type, ontModel.createResource(DIGITALNI_OBJEKT));

        Property schemaUrlProperty = ontModel.createProperty(SCHEMA_URL);
        digitalDocument.addProperty(schemaUrlProperty, ontModel.createResource(source.getUrl().trim()));

        if (source.getName() != null && !source.getName().trim().isEmpty()) {
            Property titleProperty = ontModel.createProperty(DCT_NS + "title");
            DataTypeConverter.addTypedProperty(digitalDocument, titleProperty,
                    source.getName().trim(), DEFAULT_LANG, ontModel);
        }

        if (source.getDescription() != null && !source.getDescription().trim().isEmpty()) {
            Property descriptionProperty = ontModel.createProperty(DCT_NS + "description");
            DataTypeConverter.addTypedProperty(digitalDocument, descriptionProperty,
                    source.getDescription().trim(), DEFAULT_LANG, ontModel);
        }

        resource.addProperty(property, digitalDocument);
    }

    private void addExactMatch(Resource resource, List<String> exactMatchList) {
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

    private void addAgenda(Resource resource, String agendaCode) {
        if (UtilityMethods.isValidAgendaValue(agendaCode)) {
            String transformedAgenda = UtilityMethods.transformAgendaValue(agendaCode);
            Property agendaProperty = ontModel.createProperty(DEFAULT_NS + AGENDOVY_104 + AGENDA_LONG);

            if (DataTypeConverter.isUri(transformedAgenda)) {
                resource.addProperty(agendaProperty, ontModel.createResource(transformedAgenda));
            } else {
                DataTypeConverter.addTypedProperty(resource, agendaProperty, transformedAgenda, null, ontModel);
            }
        }
    }

    private void addAIS(Resource resource, String aisCode) {
        if (UtilityMethods.isValidAISValue(aisCode)) {
            String transformedAIS = UtilityMethods.transformAISValue(aisCode);
            Property aisProperty = ontModel.createProperty(DEFAULT_NS + AGENDOVY_104 + UDAJE_AIS);

            if (DataTypeConverter.isUri(transformedAIS)) {
                resource.addProperty(aisProperty, ontModel.createResource(transformedAIS));
            } else {
                DataTypeConverter.addTypedProperty(resource, aisProperty, transformedAIS, null, ontModel);
            }
        }
    }

    private void addGovernanceProperty(Resource resource, String value, String propertyName) {
        String sanitizedValue = UtilityMethods.sanitizeForIRI(value);
        String governanceIRI = switch (propertyName) {
            case TYP_OBSAHU -> "https://data.dia.gov.cz/zdroj/číselníky/typy-obsahu-údajů/položky/" + sanitizedValue;
            case ZPUSOB_SDILENI ->
                    "https://data.dia.gov.cz/zdroj/číselníky/způsoby-sdílení-údajů/položky/" + sanitizedValue;
            case ZPUSOB_ZISKANI ->
                    "https://data.dia.gov.cz/zdroj/číselníky/způsoby-získání-údajů/položky/" + sanitizedValue;
            default -> null;
        };

        if (governanceIRI != null) {
            Property property = ontModel.createProperty(OFN_NAMESPACE + propertyName);
            resource.addProperty(property, ontModel.createResource(governanceIRI));
        }
    }

    /**
     * Writes the veřejný/neveřejný-údaj classification. Malformed provisions are rejected
     * upstream by {@code ConceptInputValidator}, so {@code validProvisions} mirrors the
     * non-blank input; the filtering here is a defensive backstop for non-service callers.
     */
    private void addDataClassification(Resource resource, Boolean isPublic, List<String> privacyProvisions) {
        boolean hasNonEmptyProvisions = privacyProvisions != null &&
                privacyProvisions.stream().anyMatch(p -> p != null && !p.trim().isEmpty());

        List<String> validProvisions = new java.util.ArrayList<>();
        if (privacyProvisions != null) {
            for (String provision : privacyProvisions) {
                if (provision == null || provision.trim().isEmpty()) continue;
                String canonical = EsbirkaEliParser.canonicalizeHost(provision.trim());
                if (SparqlIriValidator.isEsbirkaEliIri(canonical)) {
                    validProvisions.add(canonical);
                } else {
                    log.warn("Skipping privacy provision — not a canonical e-Sbírka ELI IRI: {}", provision.trim());
                }
            }
        }

        if (Boolean.TRUE.equals(isPublic)) {
            if (!hasNonEmptyProvisions) {
                resource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ));
            }
        } else if (Boolean.FALSE.equals(isPublic) && !validProvisions.isEmpty()) {
                resource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ));
                Property provisionProperty = ontModel.createProperty(OFN_NAMESPACE_LEGAL + USTANOVENI_NEVEREJNOST);
                for (String transformedProvision : validProvisions) {
                    resource.addProperty(provisionProperty, ontModel.createResource(transformedProvision));
                }
            }
    }

    private void addIsInPPDF(Resource resource, Boolean isInPPDF) {
        if (isInPPDF != null) {
            Property ppdfProperty = ontModel.createProperty(DEFAULT_NS + AGENDOVY_104 + JE_PPDF_LONG);
            DataTypeConverter.addTypedProperty(resource, ppdfProperty,
                    isInPPDF.toString(), null, ontModel);
        }
    }

    private void addBroaderConcept(Resource resource, String broaderConcept) {
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

    private void addSuperProperty(Resource resource, String superProperty) {
        String superURI;
        if (UtilityMethods.isValidIRI(superProperty)) {
            superURI = superProperty;
        } else {
            superURI = uriGenerator.generateConceptURI(superProperty, null);
        }
        resource.addProperty(RDFS.subPropertyOf, ontModel.createResource(superURI));
    }

    private void addResourceReference(Resource subject, Property property, String referenceName) {
        if (UtilityMethods.isValidIRI(referenceName)) {
            subject.addProperty(property, ontModel.createResource(referenceName));
        } else {
            String conceptUri = uriGenerator.generateConceptURI(referenceName, null);
            subject.addProperty(property, ontModel.createResource(conceptUri));
        }
    }

    private void addRangeInformation(Resource propertyResource, String dataType) {
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

    private boolean isObjectProperty(PropertyConceptModel propModel) {
        String dataType = propModel.getDataType();
        if (dataType != null && !dataType.trim().isEmpty()) {
            String trimmed = dataType.trim();
            return !trimmed.startsWith("xsd:") &&
                    !trimmed.startsWith("http://www.w3.org/2001/XMLSchema#") &&
                    !isValidDataType(trimmed);
        }
        return false;
    }

    private boolean isValidDataType(String type) {
        String[] validTypes = {
                "Ano či ne", "Datum", "Čas", "Datum a čas",
                "Celé číslo", "Desetinné číslo", "URI, IRI, URL",
                "Řetězec", "Text"
        };
        for (String validType : validTypes) {
            if (validType.equalsIgnoreCase(type)) {
                return true;
            }
        }
        return false;
    }

    private String determineEffectiveNamespace(String namespace) {
        if (namespace != null && !namespace.trim().isEmpty() && UtilityMethods.isValidIRI(namespace)) {
            return UtilityMethods.ensureNamespaceEndsWithDelimiter(namespace);
        }
        return DEFAULT_NS;
    }

    private boolean hasLegalSources(ConceptCreateModel model) {
        return (model.getDefiningLegalSource() != null && !model.getDefiningLegalSource().isEmpty()) ||
                (model.getRelatedLegalSource() != null && !model.getRelatedLegalSource().isEmpty());
    }

    private boolean hasNonLegalSources(ConceptCreateModel model) {
        return (model.getDefiningNonLegalSource() != null && !model.getDefiningNonLegalSource().isEmpty()) ||
                (model.getRelatedNonLegalSource() != null && !model.getRelatedNonLegalSource().isEmpty());
    }

    private boolean hasGovernanceProperties(ClassConceptModel model) {
        return (model.getSharingMethod() != null && !model.getSharingMethod().isEmpty()) ||
                (model.getAcquisitionMethod() != null && !model.getAcquisitionMethod().isEmpty()) ||
                (model.getContentType() != null && !model.getContentType().isEmpty());
    }

    private boolean hasPrivacyProvisions(List<String> privacyProvisions) {
        return privacyProvisions != null && !privacyProvisions.isEmpty();
    }

    private boolean hasGovernancePropertiesValues(List<String> sharingMethod, String acquisitionMethod, String contentType) {
        return (sharingMethod != null && !sharingMethod.isEmpty()) ||
                (acquisitionMethod != null && !acquisitionMethod.trim().isEmpty()) ||
                (contentType != null && !contentType.trim().isEmpty());
    }

    private String getNameForUriGeneration(com.dia.ismdtoolbackend.models.NameModel nameModel) {
        if (nameModel == null || nameModel.getName() == null || nameModel.getName().isEmpty()) {
            return "";
        }
        Map<String, String> names = nameModel.getName();
        if (names.containsKey(DEFAULT_LANG)) {
            return names.get(DEFAULT_LANG);
        }
        return names.values().iterator().next();
    }

    /**
     * Writes the code-list structure: the concept links to the číselník, which is a named
     * subject carrying its type and its NKOD dataset. Both IRIs are mandatory together.
     */
    private void addCodeListDataset(Resource resource, String codeListIri, String datasetUrl) {
        boolean hasIri = codeListIri != null && !codeListIri.trim().isEmpty();
        boolean hasDataset = datasetUrl != null && !datasetUrl.trim().isEmpty();

        if (!hasIri && !hasDataset) return;

        ConceptValidationUtil.validateCodeListIri(codeListIri);
        ConceptValidationUtil.validateCodeListDataset(datasetUrl);
        ConceptValidationUtil.validateCodeListCompleteness(codeListIri, datasetUrl);

        if (!hasIri || !hasDataset) return;

        String iri = codeListIri.trim();
        String dataset = datasetUrl.trim();

        Property instanceDefinedByCodeList = ontModel.createProperty(
                OFN_NAMESPACE + MA_INSTANCE_DEFINOVANE_CISELNIKEM);
        Resource codeListType = ontModel.createResource(
                OFN_NAMESPACE_LEGAL + CISELNIK);
        Property datasetProperty = ontModel.createProperty(
                OFN_NAMESPACE_LEGAL + MA_V_NKOD_ZASTRESUJICI_DATOVOU_SADU);

        Resource codeListNode = ontModel.createResource(iri);
        codeListNode.addProperty(RDF.type, codeListType);
        codeListNode.addProperty(datasetProperty, ontModel.createResource(dataset));

        resource.addProperty(instanceDefinedByCodeList, codeListNode);
    }
}
