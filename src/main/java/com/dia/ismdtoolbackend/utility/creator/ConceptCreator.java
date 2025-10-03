package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptModel;
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
import java.util.Set;

import static com.dia.constants.ArchiConstants.DEFINUJICI_USTANOVENI;
import static com.dia.constants.ArchiConstants.SOUVISEJICI_USTANOVENI;
import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.*;

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
        };

        String conceptURI = concept.getURI();

        Model cleanModel = ModelFactory.createDefaultModel();

        ontModel.listStatements(concept, null, (RDFNode) null).forEachRemaining(stmt -> {
            Resource subj = cleanModel.createResource(conceptURI);
            Property pred = cleanModel.createProperty(stmt.getPredicate().getURI());

            RDFNode obj;
            if (stmt.getObject().isResource()) {
                obj = cleanModel.createResource(stmt.getObject().asResource().getURI());
            } else {
                obj = stmt.getObject();
            }

            cleanModel.add(subj, pred, obj);
        });

        log.info("Created clean model with {} statements for: {}", cleanModel.size(), conceptURI);

        return cleanModel.getResource(conceptURI);
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
        } else if (createModel instanceof PropertyConceptModel) {
            classes.add(VLASTNOST);
        } else if (createModel instanceof RelationshipConceptModel) {
            classes.add(VZTAH);
        }

        return classes;
    }

    private void addClassBaseClasses(Set<String> classes, ClassConceptModel classModel) {
        classes.add(TRIDA);
        addTypeSpecificClass(classes, classModel.getType());
        addDataClassificationClasses(classes, classModel);
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
        if (hasPublicData(classModel)) {
            classes.add(VEREJNY_UDAJ);
        }
        if (hasPrivateData(classModel)) {
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
        if (createModel.getAltNameModel() != null && createModel.getAltNameModel().getAltName() != null && !createModel.getAltNameModel().getAltName().trim().isEmpty()) {
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
        }
    }

    private void addConceptTypeSpecificProperties(Set<String> properties, ConceptCreateModel createModel) {
        if (createModel instanceof ClassConceptModel classModel) {
            addClassSpecificProperties(properties, classModel);
        } else if (createModel instanceof PropertyConceptModel propModel) {
            addPropertySpecificProperties(properties, propModel);
        }
    }

    private void addClassSpecificProperties(Set<String> properties, ClassConceptModel classModel) {
        if (classModel.getAgendaCode() != null && !classModel.getAgendaCode().trim().isEmpty()) {
            properties.add(AGENDA);
        }
        if (classModel.getAgendaSystemCode() != null && !classModel.getAgendaSystemCode().trim().isEmpty()) {
            properties.add(AIS);
        }
        if (hasPrivateData(classModel)) {
            properties.add(USTANOVENI_NEVEREJNOST);
        }
        if (hasGovernanceProperties(classModel)) {
            properties.add(ZPUSOB_SDILENI);
            properties.add(ZPUSOB_ZISKANI);
            properties.add(TYP_OBSAHU);
        }
    }

    private void addPropertySpecificProperties(Set<String> properties, PropertyConceptModel propModel) {
        if (propModel.getIsInPPDF() != null) {
            properties.add(JE_PPDF);
        }
    }

    private Resource createClassResource(ClassConceptModel classModel) {
        String classURI = uriGenerator.generateConceptURI(classModel.getNameModel().getName(), classModel.getIdentifier());
        Resource classResource = ontModel.createResource(classURI);

        classResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + POJEM));
        classResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + TRIDA));

        addSpecificClassType(classResource, classModel.getType());


        addCommonMetadata(classResource, classModel);

        addClassSpecificMetadata(classResource, classModel);

        log.info("Created class resource: {}", classURI);
        return classResource;
    }

    private Resource createPropertyResource(PropertyConceptModel propModel) {
        String propertyURI = uriGenerator.generateConceptURI(propModel.getNameModel().getName(), propModel.getIdentifier());

        OntProperty propertyResource;
        if (isObjectProperty(propModel)) {
            propertyResource = ontModel.createObjectProperty(propertyURI);
        } else {
            propertyResource = ontModel.createDatatypeProperty(propertyURI);
        }

        propertyResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + POJEM));
        propertyResource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE + VLASTNOST));

        addCommonMetadata(propertyResource, propModel);

        addPropertySpecificMetadata(propertyResource, propModel);

        log.info("Created property resource: {}", propertyURI);
        return propertyResource;
    }

    private Resource createRelationshipResource(RelationshipConceptModel relModel) {
        String relationshipURI = uriGenerator.generateConceptURI(relModel.getNameModel().getName(), relModel.getIdentifier());

        OntProperty relationshipResource = ontModel.createObjectProperty(relationshipURI);

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
    }

    private void addBasicMetadata(Resource resource, ConceptCreateModel model) {
        if (model.getNameModel() != null && model.getNameModel().getName() != null && !model.getNameModel().getName().trim().isEmpty()) {
            String nameLanguageTag = model.getNameModel().getLanguageTag() != null
                ? model.getNameModel().getLanguageTag()
                : DEFAULT_LANG;
            DataTypeConverter.addTypedProperty(resource, SKOS.prefLabel,
                    model.getNameModel().getName(), nameLanguageTag, ontModel);
        }

        if (model.getDescriptionModel() != null && model.getDescriptionModel().getDescription() != null && !model.getDescriptionModel().getDescription().trim().isEmpty()) {
            Property descProperty = ontModel.createProperty("http://purl.org/dc/terms/description");
            String descLanguageTag = model.getDescriptionModel().getLanguageTag() != null
                ? model.getDescriptionModel().getLanguageTag()
                : DEFAULT_LANG;
            DataTypeConverter.addTypedProperty(resource, descProperty,
                    model.getDescriptionModel().getDescription(), descLanguageTag, ontModel);
        }

        if (model.getDefinitionModel() != null && model.getDefinitionModel().getDefinition() != null && !model.getDefinitionModel().getDefinition().trim().isEmpty()) {
            String defLanguageTag = model.getDefinitionModel().getLanguageTag() != null
                ? model.getDefinitionModel().getLanguageTag()
                : DEFAULT_LANG;
            DataTypeConverter.addTypedProperty(resource, SKOS.definition,
                    model.getDefinitionModel().getDefinition(), defLanguageTag, ontModel);
        }

        if (model.getAltNameModel() != null && model.getAltNameModel().getAltName() != null && !model.getAltNameModel().getAltName().trim().isEmpty()) {
            addAlternativeNames(resource, model.getAltNameModel());
        }
    }

    private void addSourceMetadata(Resource resource, ConceptCreateModel model) {
        addLegalSourceMetadata(resource, model);
        addNonLegalSourceMetadata(resource, model);
    }

    private void addLegalSourceMetadata(Resource resource, ConceptCreateModel model) {
        if (model.getDefiningLegalSource() != null && !model.getDefiningLegalSource().trim().isEmpty()) {
            processLegalSource(resource, model.getDefiningLegalSource(), true);
        }
        if (model.getRelatedLegalSource() != null && !model.getRelatedLegalSource().trim().isEmpty()) {
            processLegalSource(resource, model.getRelatedLegalSource(), false);
        }
    }

    private void addNonLegalSourceMetadata(Resource resource, ConceptCreateModel model) {
        if (model.getDefiningNonLegalSource() != null && !model.getDefiningNonLegalSource().trim().isEmpty()) {
            processNonLegalSource(resource, model.getDefiningNonLegalSource(), true);
        }
        if (model.getRelatedNonLegalSource() != null && !model.getRelatedNonLegalSource().trim().isEmpty()) {
            processNonLegalSource(resource, model.getRelatedNonLegalSource(), false);
        }
    }

    private void addMatchMetadata(Resource resource, ConceptCreateModel model) {
        if (model.getExactMatch() != null && !model.getExactMatch().trim().isEmpty()) {
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
        if (classModel.getAgendaCode() != null && !classModel.getAgendaCode().trim().isEmpty()) {
            addAgenda(classResource, classModel.getAgendaCode());
        }
        if (classModel.getAgendaSystemCode() != null && !classModel.getAgendaSystemCode().trim().isEmpty()) {
            addAIS(classResource, classModel.getAgendaSystemCode());
        }
        if (classModel.getSharingMethod() != null && !classModel.getSharingMethod().trim().isEmpty()) {
            addGovernanceProperty(classResource, classModel.getSharingMethod(), ZPUSOB_SDILENI);
        }
        if (classModel.getAcquisitionMethod() != null && !classModel.getAcquisitionMethod().trim().isEmpty()) {
            addGovernanceProperty(classResource, classModel.getAcquisitionMethod(), ZPUSOB_ZISKANI);
        }
        if (classModel.getContentType() != null && !classModel.getContentType().trim().isEmpty()) {
            addGovernanceProperty(classResource, classModel.getContentType(), TYP_OBSAHU);
        }

        addDataClassification(classResource, classModel);

        if (classModel.getBroaderConcept() != null && !classModel.getBroaderConcept().trim().isEmpty()) {
            addBroaderConcept(classResource, classModel.getBroaderConcept());
        }
    }


    private void addPropertySpecificMetadata(Resource propertyResource, PropertyConceptModel propModel) {
        if (propModel.getDomain() != null && !propModel.getDomain().trim().isEmpty()) {
            addResourceReference(propertyResource, RDFS.domain, propModel.getDomain());
        }

        addRangeInformation(propertyResource, propModel.getDataType());

        if (propModel.getSuperProperty() != null && !propModel.getSuperProperty().trim().isEmpty()) {
            addSuperProperty(propertyResource, propModel.getSuperProperty());
        }

        if (propModel.getIsInPPDF() != null) {
            Property ppdfProperty = ontModel.createProperty(uriGenerator.getEffectiveNamespace() + JE_PPDF);
            DataTypeConverter.addTypedProperty(propertyResource, ppdfProperty,
                    propModel.getIsInPPDF().toString(), null, ontModel);
        }
    }

    private void addRelationshipSpecificMetadata(Resource relationshipResource, RelationshipConceptModel relModel) {
        if (relModel.getDomain() != null && !relModel.getDomain().trim().isEmpty()) {
            addResourceReference(relationshipResource, RDFS.domain, relModel.getDomain());
        }

        if (relModel.getRange() != null && !relModel.getRange().trim().isEmpty()) {
            addResourceReference(relationshipResource, RDFS.range, relModel.getRange());
        }

        if (relModel.getSuperRelation() != null && !relModel.getSuperRelation().trim().isEmpty()) {
            addSuperProperty(relationshipResource, relModel.getSuperRelation());
        }
    }

    private void addAlternativeNames(Resource resource, com.dia.ismdtoolbackend.models.concept.AltNameModel altNameModel) {
        Property altNameProperty = ontModel.createProperty(uriGenerator.getEffectiveNamespace() + ALTERNATIVNI_NAZEV);
        String altNames = altNameModel.getAltName();
        String languageTag = altNameModel.getLanguageTag() != null ? altNameModel.getLanguageTag() : DEFAULT_LANG;

        if (altNames.contains(";")) {
            String[] names = altNames.split(";");
            for (String name : names) {
                String trimmedName = name.trim();
                if (!trimmedName.isEmpty()) {
                    resource.addProperty(altNameProperty, trimmedName, languageTag);
                }
            }
        } else {
            resource.addProperty(altNameProperty, altNames.trim(), languageTag);
        }
    }

    private void processLegalSource(Resource resource, String source, boolean isDefining) {
        String propertyName = isDefining ? DEFINUJICI_USTANOVENI : SOUVISEJICI_USTANOVENI;
        Property property = ontModel.createProperty(uriGenerator.getEffectiveNamespace() + propertyName);

        if (UtilityMethods.containsEliPattern(source)) {
            String eliPart = UtilityMethods.extractEliPart(source);
            if (eliPart != null) {
                String transformedUrl = "https://opendata.eselpoint.cz/esel-esb/" + eliPart;
                resource.addProperty(property, ontModel.createResource(transformedUrl));
            }
        }
    }

    private void processNonLegalSource(Resource resource, String source, boolean isDefining) {
        String propertyName = isDefining ? DEFINUJICI_NELEGISLATIVNI_ZDROJ : SOUVISEJICI_NELEGISLATIVNI_ZDROJ;
        Property property = ontModel.createProperty(uriGenerator.getEffectiveNamespace() + propertyName);

        String documentUri = uriGenerator.getEffectiveNamespace() + "digitální-dokument-" + System.currentTimeMillis();
        Resource digitalDocument = ontModel.createResource(documentUri);

        Property schemaUrlProperty = ontModel.createProperty("http://schema.org/url");
        if (UtilityMethods.isValidUrl(source)) {
            digitalDocument.addProperty(schemaUrlProperty, ontModel.createResource(source));
        }

        resource.addProperty(property, digitalDocument);
    }

    private void addExactMatch(Resource resource, String exactMatch) {
        Property exactMatchProperty = ontModel.createProperty("http://www.w3.org/2004/02/skos/core#exactMatch");

        if (exactMatch.contains(";")) {
            String[] iris = exactMatch.split(";");
            for (String iri : iris) {
                String trimmedIri = iri.trim();
                if (!trimmedIri.isEmpty() && UtilityMethods.isValidIRI(trimmedIri)) {
                    resource.addProperty(exactMatchProperty, ontModel.createResource(trimmedIri));
                }
            }
        } else {
            if (UtilityMethods.isValidIRI(exactMatch)) {
                resource.addProperty(exactMatchProperty, ontModel.createResource(exactMatch));
            }
        }
    }

    private void addAgenda(Resource resource, String agendaCode) {
        if (UtilityMethods.isValidAgendaValue(agendaCode)) {
            String transformedAgenda = UtilityMethods.transformAgendaValue(agendaCode);
            Property agendaProperty = ontModel.createProperty(uriGenerator.getEffectiveNamespace() + AGENDA);

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
            Property aisProperty = ontModel.createProperty(uriGenerator.getEffectiveNamespace() + AIS);

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
            Property property = ontModel.createProperty(uriGenerator.getEffectiveNamespace() + propertyName);
            resource.addProperty(property, ontModel.createResource(governanceIRI));
        }
    }

    private void addDataClassification(Resource classResource, ClassConceptModel classModel) {
        String privacyProvision = classModel.getPrivacyProvision();

        if (privacyProvision != null && !privacyProvision.trim().isEmpty() && UtilityMethods.containsEliPattern(privacyProvision)) {
            String eliPart = UtilityMethods.extractEliPart(privacyProvision);
            if (eliPart != null) {
                String transformedProvision = "https://opendata.eselpoint.cz/esel-esb/" + eliPart;
                Property provisionProperty = ontModel.createProperty(
                        uriGenerator.getEffectiveNamespace() + USTANOVENI_NEVEREJNOST);
                classResource.addProperty(provisionProperty, ontModel.createResource(transformedProvision));
            }
        }
    }

    private void addBroaderConcept(Resource resource, String broaderConcept) {
        Property hierarchyProperty = ontModel.createProperty(
                uriGenerator.getEffectiveNamespace() + "nadřazená-třída");

        if (broaderConcept.contains(";")) {
            String[] concepts = broaderConcept.split(";");
            for (String concept : concepts) {
                String trimmedConcept = concept.trim();
                if (!trimmedConcept.isEmpty()) {
                    String broaderURI;
                    if (DataTypeConverter.isUri(trimmedConcept)) {
                        broaderURI = trimmedConcept;
                    } else {
                        broaderURI = uriGenerator.generateConceptURI(trimmedConcept, null);
                    }
                    resource.addProperty(RDFS.subClassOf, ontModel.createResource(broaderURI));
                    resource.addProperty(hierarchyProperty, ontModel.createResource(broaderURI));
                }
            }
        } else {
            String broaderURI;
            if (DataTypeConverter.isUri(broaderConcept)) {
                broaderURI = broaderConcept;
            } else {
                broaderURI = uriGenerator.generateConceptURI(broaderConcept, null);
            }
            resource.addProperty(RDFS.subClassOf, ontModel.createResource(broaderURI));
            resource.addProperty(hierarchyProperty, ontModel.createResource(broaderURI));
        }
    }

    private void addSuperProperty(Resource resource, String superProperty) {
        String superURI;
        if (DataTypeConverter.isUri(superProperty)) {
            superURI = superProperty;
        } else {
            superURI = uriGenerator.generateConceptURI(superProperty, null);
        }
        resource.addProperty(RDFS.subPropertyOf, ontModel.createResource(superURI));
    }

    private void addResourceReference(Resource subject, Property property, String referenceName) {
        if (DataTypeConverter.isUri(referenceName)) {
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

    private boolean hasPublicData(ClassConceptModel model) {
        String isPublic = model.getIsPublic();
        return isPublic != null && (
                isPublic.toLowerCase().contains("ano") ||
                        isPublic.toLowerCase().contains("true") ||
                        isPublic.equalsIgnoreCase("yes")
        );
    }

    private boolean hasPrivateData(ClassConceptModel model) {
        return (model.getIsPublic() != null && (
                model.getIsPublic().toLowerCase().contains("ne") ||
                        model.getIsPublic().toLowerCase().contains("false") ||
                        model.getIsPublic().equalsIgnoreCase("no")
        )) || (model.getPrivacyProvision() != null && !model.getPrivacyProvision().trim().isEmpty());
    }

    private boolean hasLegalSources(ConceptCreateModel model) {
        return (model.getDefiningLegalSource() != null && !model.getDefiningLegalSource().trim().isEmpty()) ||
                (model.getRelatedLegalSource() != null && !model.getRelatedLegalSource().trim().isEmpty());
    }

    private boolean hasNonLegalSources(ConceptCreateModel model) {
        return (model.getDefiningNonLegalSource() != null && !model.getDefiningNonLegalSource().trim().isEmpty()) ||
                (model.getRelatedNonLegalSource() != null && !model.getRelatedNonLegalSource().trim().isEmpty());
    }

    private boolean hasGovernanceProperties(ClassConceptModel model) {
        return (model.getSharingMethod() != null && !model.getSharingMethod().trim().isEmpty()) ||
                (model.getAcquisitionMethod() != null && !model.getAcquisitionMethod().trim().isEmpty()) ||
                (model.getContentType() != null && !model.getContentType().trim().isEmpty());
    }
}
