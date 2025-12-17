package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.DescriptionModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.concept.*;
import com.dia.utility.DataTypeConverter;
import com.dia.utility.URIGenerator;
import com.dia.utility.UtilityMethods;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;

@Component
@RequiredArgsConstructor
@Slf4j
public class ConceptEditor {

    private static final String TYPE = "type";
    private static final String AGENDA_CODE = "agendaCode";
    private static final String AIS = "agendaSystemCode";
    private static final String IS_PUBLIC = "isPublic";
    private static final String PRIVACY_PROVISIONS = "privacyProvisions";
    private static final String IN_TEZAURUS = "inTezaurus";
    private static final String NAMESPACE = "namespace";

    private final URIGenerator uriGenerator = new URIGenerator();

    public EditResult editConcept(String conceptIri, ConceptEditModel editModel, Model model, String graphName) {
        Resource existingConcept = model.getResource(conceptIri);

        if (existingConcept == null || !model.containsResource(existingConcept)) {
            throw new IllegalArgumentException("Concept with IRI " + conceptIri + " not found in the model");
        }

        String effectiveNamespace = determineEffectiveNamespace(graphName);
        uriGenerator.setEffectiveNamespace(effectiveNamespace);

        String newName = getNameForUriGeneration(editModel.getNameModel());
        String oldName = getNameForUriGeneration(existingConcept);
        boolean nameChanged = newName != null && !newName.isEmpty() && !newName.equals(oldName);

        String newConceptIRI = conceptIri;
        if (nameChanged) {
            newConceptIRI = uriGenerator.generateConceptURI(newName, editModel.getIdentifier());
            log.info("Name changed from '{}' to '{}', updating IRI from {} to {}",
                    oldName, newName, conceptIri, newConceptIRI);
        }

        EditContext context = new EditContext(model, conceptIri, newConceptIRI);

        Set<Statement> statementsToRemove = new HashSet<>();
        Set<Statement> statementsToAdd = new HashSet<>();

        boolean supportsTransactions = model.supportsTransactions();
        if (supportsTransactions) {
            model.begin();
        }

        try {
            if (nameChanged && !conceptIri.equals(newConceptIRI)) {
                Set<Property> predicatesToExclude = buildPredicatesToExclude(editModel, model);
                renameConceptIRI(model, conceptIri, newConceptIRI, statementsToRemove, statementsToAdd, predicatesToExclude);
            }

            switch (editModel.getConceptTypeEnum()) {
                case TRIDA -> editClassConcept((ClassConceptEditModel) editModel, context,
                        model, statementsToRemove, statementsToAdd);
                case VLASTNOST -> editPropertyConcept((PropertyConceptEditModel) editModel, context,
                        model, statementsToRemove, statementsToAdd);
                case VZTAH -> editRelationshipConcept((RelationshipConceptEditModel) editModel, context,
                        model, statementsToRemove, statementsToAdd);
            }

            model.remove(statementsToRemove.toArray(new Statement[0]));
            model.add(statementsToAdd.toArray(new Statement[0]));

            log.info("Applied {} removals and {} additions", statementsToRemove.size(), statementsToAdd.size());

            if (supportsTransactions) {
                model.commit();
            }

            return new EditResult(newConceptIRI, nameChanged, statementsToRemove.size() + statementsToAdd.size());

        } catch (Exception e) {
            if (supportsTransactions) {
                try {
                    model.abort();
                    log.error("Transaction rolled back due to error during concept edit", e);
                } catch (Exception rollbackException) {
                    log.error("Failed to rollback transaction", rollbackException);
                }
            }
            throw new OntologyException("Failed to edit concept: " + conceptIri);
        }
    }

    private void editClassConcept(ClassConceptEditModel editModel, EditContext context,
                                   Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        editCommonFields(editModel, context, model, toRemove, toAdd);

        updateStringProperty(context.newConcept, TYPE, editModel.getType(), context.oldConcept, model, toRemove, toAdd);
        updatePrivacyProvisionsList(context.newConcept, editModel.getPrivacyProvisions(), context.oldConcept, model, toRemove, toAdd);
        updateBroaderConceptList(context.newConcept, editModel.getBroaderConcept(), context.oldConcept, model, toRemove, toAdd);

        updateSharedGovernanceMetadata(context.newConcept, editModel.getIsInPPDF(), editModel.getAgendaCode(),
                editModel.getAgendaSystemCode(), editModel.getSharingMethod(), editModel.getAcquisitionMethod(),
                editModel.getContentType(), context.oldConcept, model, toRemove, toAdd);

        updateDataClassification(context.newConcept, editModel.getIsPublic(),
                                context.oldConcept, model, toRemove, toAdd);
    }

    private void editPropertyConcept(PropertyConceptEditModel editModel, EditContext context,
                                      Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        editCommonFields(editModel, context, model, toRemove, toAdd);

        updateDomainRange(context.newConcept, RDFS.domain, editModel.getDomain(), context.oldConcept, model, toRemove, toAdd);
        updateDataTypeRange(context.newConcept, editModel.getDataType(), context.oldConcept, model, toRemove, toAdd);
        updateSuperPropertyList(context.newConcept, editModel.getSuperProperty(), context.oldConcept, model, toRemove, toAdd);

        updateSharedGovernanceMetadata(context.newConcept, editModel.getIsInPPDF(), editModel.getAgendaCode(),
                editModel.getAgendaSystemCode(), editModel.getSharingMethod(), editModel.getAcquisitionMethod(),
                editModel.getContentType(), context.oldConcept, model, toRemove, toAdd);

        updateDataClassification(context.newConcept, editModel.getIsPublic(),
                                context.oldConcept, model, toRemove, toAdd);
    }

    private void editRelationshipConcept(RelationshipConceptEditModel editModel, EditContext context,
                                          Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        editCommonFields(editModel, context, model, toRemove, toAdd);

        updateDomainRange(context.newConcept, RDFS.domain, editModel.getDomain(), context.oldConcept, model, toRemove, toAdd);
        updateDomainRange(context.newConcept, RDFS.range, editModel.getRange(), context.oldConcept, model, toRemove, toAdd);
        updateSuperPropertyList(context.newConcept, editModel.getSuperRelation(), context.oldConcept, model, toRemove, toAdd);

        updateSharedGovernanceMetadata(context.newConcept, editModel.getIsInPPDF(), editModel.getAgendaCode(),
                editModel.getAgendaSystemCode(), editModel.getSharingMethod(), editModel.getAcquisitionMethod(),
                editModel.getContentType(), context.oldConcept, model, toRemove, toAdd);

        updateDataClassification(context.newConcept, editModel.getIsPublic(),
                                context.oldConcept, model, toRemove, toAdd);
    }

    private void editCommonFields(ConceptEditModel editModel, EditContext context,
                                   Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        updateNameModel(context.newConcept, editModel.getNameModel(), context.oldConcept, model, toRemove, toAdd);
        updateDescriptionModel(context.newConcept, editModel.getDescriptionModel(), context.oldConcept, model, toRemove, toAdd);
        updateDefinitionModel(context.newConcept, editModel.getDefinitionModel(), context.oldConcept, model, toRemove, toAdd);
        updateAltNameModel(context.newConcept, editModel.getAltNameModel(), context.oldConcept, model, toRemove, toAdd);
        updateLegalSources(context.newConcept, editModel, context.oldConcept, model, toRemove, toAdd);
        updateNonLegalSources(context.newConcept, editModel, context.oldConcept, model, toRemove, toAdd);
        updateExactMatch(context.newConcept, editModel.getExactMatch(), context.oldConcept, model, toRemove, toAdd);
        updateBooleanProperty(context.newConcept, IN_TEZAURUS, editModel.getInTezaurus(), context.oldConcept, model, toRemove, toAdd);
        updateStringProperty(context.newConcept, NAMESPACE, editModel.getNamespace(), context.oldConcept, model, toRemove, toAdd);
    }

    private void updateNameModel(Resource newConcept, NameModel nameModel, Resource oldConcept,
                                 Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (nameModel == null || nameModel.getName() == null) return;

        Map<String, String> existingNames = getAllPropertyValuesWithLanguage(oldConcept, SKOS.prefLabel);

        Map<String, String> newNames = nameModel.getName();

        Map<String, String> mergedNames = new HashMap<>(existingNames);
        for (Map.Entry<String, String> entry : newNames.entrySet()) {
            if (entry.getValue() == null || entry.getValue().trim().isEmpty()) {
                mergedNames.remove(entry.getKey());
            } else {
                mergedNames.put(entry.getKey(), entry.getValue().trim());
            }
        }

        if (!existingNames.equals(mergedNames)) {
            removeAllByPredicate(newConcept, SKOS.prefLabel, toRemove, toAdd);
            for (Map.Entry<String, String> entry : mergedNames.entrySet()) {
                String languageTag = entry.getKey() != null && !entry.getKey().trim().isEmpty()
                        ? entry.getKey()
                        : DEFAULT_LANG;
                toAdd.add(model.createStatement(newConcept, SKOS.prefLabel,
                        model.createLiteral(entry.getValue(), languageTag)));
            }
        }
    }

    private void updateDescriptionModel(Resource newConcept, DescriptionModel descModel, Resource oldConcept,
                                        Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (descModel == null) return;

        Property descProperty = model.createProperty("http://purl.org/dc/terms/description");

        Map<String, String> existingDescriptions = getAllPropertyValuesWithLanguage(oldConcept, descProperty);

        Map<String, String> newDescriptions = descModel.getDescription();

        if (newDescriptions == null || newDescriptions.isEmpty()) {
            if (!existingDescriptions.isEmpty()) {
                removeAllByPredicate(newConcept, descProperty, toRemove, toAdd);
            }
            return;
        }

        Map<String, String> mergedDescriptions = new HashMap<>(existingDescriptions);
        for (Map.Entry<String, String> entry : newDescriptions.entrySet()) {
            if (entry.getValue() == null || entry.getValue().trim().isEmpty()) {
                mergedDescriptions.remove(entry.getKey());
            } else {
                mergedDescriptions.put(entry.getKey(), entry.getValue().trim());
            }
        }

        if (!existingDescriptions.equals(mergedDescriptions)) {
            removeAllByPredicate(newConcept, descProperty, toRemove, toAdd);
            for (Map.Entry<String, String> entry : mergedDescriptions.entrySet()) {
                String languageTag = entry.getKey() != null && !entry.getKey().trim().isEmpty()
                        ? entry.getKey()
                        : DEFAULT_LANG;
                toAdd.add(model.createStatement(newConcept, descProperty,
                        model.createLiteral(entry.getValue(), languageTag)));
            }
        }
    }

    private void updateDefinitionModel(Resource newConcept, DefinitionModel defModel, Resource oldConcept,
                                        Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (defModel == null) return;

        Map<String, String> existingDefinitions = getAllPropertyValuesWithLanguage(oldConcept, SKOS.definition);

        Map<String, String> newDefinitions = defModel.getDefinition();

        if (newDefinitions == null || newDefinitions.isEmpty()) {
            if (!existingDefinitions.isEmpty()) {
                removeAllByPredicate(newConcept, SKOS.definition, toRemove, toAdd);
            }
            return;
        }

        Map<String, String> mergedDefinitions = new HashMap<>(existingDefinitions);
        for (Map.Entry<String, String> entry : newDefinitions.entrySet()) {
            if (entry.getValue() == null || entry.getValue().trim().isEmpty()) {
                mergedDefinitions.remove(entry.getKey());
            } else {
                mergedDefinitions.put(entry.getKey(), entry.getValue().trim());
            }
        }

        if (!existingDefinitions.equals(mergedDefinitions)) {
            removeAllByPredicate(newConcept, SKOS.definition, toRemove, toAdd);
            for (Map.Entry<String, String> entry : mergedDefinitions.entrySet()) {
                String languageTag = entry.getKey() != null && !entry.getKey().trim().isEmpty()
                        ? entry.getKey()
                        : DEFAULT_LANG;
                toAdd.add(model.createStatement(newConcept, SKOS.definition,
                        model.createLiteral(entry.getValue(), languageTag)));
            }
        }
    }

    private void updateAltNameModel(Resource newConcept, AltNameModel altNameModel, Resource oldConcept,
                                     Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (altNameModel == null) return;

        Map<String, String> oldAltNamesWithLang = getPropertyValuesWithLanguage(oldConcept);

        Map<String, String> newAltNamesWithLang = new HashMap<>();
        if (altNameModel.getAltName() != null && !altNameModel.getAltName().isEmpty()) {
            for (Map.Entry<String, String> entry : altNameModel.getAltName().entrySet()) {
                if (entry.getValue() != null && !entry.getValue().trim().isEmpty()) {
                    String languageTag = entry.getKey() != null && !entry.getKey().trim().isEmpty()
                        ? entry.getKey()
                        : DEFAULT_LANG;
                    newAltNamesWithLang.put(entry.getValue().trim(), languageTag);
                }
            }
        }

        if (!oldAltNamesWithLang.equals(newAltNamesWithLang)) {
            removeAllByPredicate(newConcept, SKOS.altLabel, toRemove, toAdd);
            for (Map.Entry<String, String> entry : newAltNamesWithLang.entrySet()) {
                toAdd.add(model.createStatement(newConcept, SKOS.altLabel,
                        model.createLiteral(entry.getKey(), entry.getValue())));
            }
        }
    }

    private void updateLegalSources(Resource newConcept, ConceptEditModel editModel, Resource oldConcept,
                                     Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        Property definingProp = model.createProperty(OFN_NAMESPACE + DEFINUJICI_USTANOVENI);
        Property relatedProp = model.createProperty(OFN_NAMESPACE + SOUVISEJICI_USTANOVENI);

        updateLegalSourceList(newConcept, definingProp, editModel.getDefiningLegalSource(),
                oldConcept, model, toRemove, toAdd);
        updateLegalSourceList(newConcept, relatedProp, editModel.getRelatedLegalSource(),
                oldConcept, model, toRemove, toAdd);
    }

    private void updateNonLegalSources(Resource newConcept, ConceptEditModel editModel, Resource oldConcept,
                                        Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        Property definingProp = model.createProperty(uriGenerator.getEffectiveNamespace() + DEFINUJICI_NELEGISLATIVNI_ZDROJ);
        Property relatedProp = model.createProperty(uriGenerator.getEffectiveNamespace() + SOUVISEJICI_NELEGISLATIVNI_ZDROJ);

        updateNonLegalSourceList(newConcept, definingProp, editModel.getDefiningNonLegalSource(),
                oldConcept, model, toRemove, toAdd);
        updateNonLegalSourceList(newConcept, relatedProp, editModel.getRelatedNonLegalSource(),
                oldConcept, model, toRemove, toAdd);
    }

    private void updateExactMatch(Resource newConcept, List<String> exactMatchList, Resource oldConcept,
                                  Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (exactMatchList == null) return;

        Property exactMatchProp = model.createProperty("http://www.w3.org/2004/02/skos/core#exactMatch");
        Set<String> oldMatches = getResourceURIs(oldConcept, exactMatchProp);
        Set<String> newMatches = new HashSet<>();

        for (String iri : exactMatchList) {
            if (iri != null && !iri.trim().isEmpty()) {
                newMatches.add(iri.trim());
            }
        }

        if (exactMatchList.isEmpty() || newMatches.isEmpty()) {
            if (!oldMatches.isEmpty()) {
                removeAllByPredicate(newConcept, exactMatchProp, toRemove, toAdd);
            }
        } else if (!oldMatches.equals(newMatches)) {
            removeAllByPredicate(newConcept, exactMatchProp, toRemove, toAdd);
            for (String match : newMatches) {
                if (UtilityMethods.isValidIRI(match)) {
                    toAdd.add(model.createStatement(newConcept, exactMatchProp, model.createResource(match)));
                }
            }
        }
    }

    private void updateStringProperty(Resource newConcept, String propertyName, String newValue,
                                       Resource oldConcept, Model model, Set<Statement> toRemove,
                                       Set<Statement> toAdd) {
        switch (propertyName) {
            case TYPE -> updateClassType(newConcept, newValue, oldConcept, model, toRemove, toAdd);
            case AGENDA_CODE -> updateAgenda(newConcept, newValue, oldConcept, model, toRemove, toAdd);
            case AIS -> updateAIS(newConcept, newValue, oldConcept, model, toRemove, toAdd);
            default -> log.warn("Unknown string property: {}", propertyName);
        }
    }

    private void updateClassType(Resource newConcept, String newType, Resource oldConcept,
                                  Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (newType == null) return;

        Resource tspType = model.getResource(OFN_NAMESPACE + TSP);
        Resource topType = model.getResource(OFN_NAMESPACE + TOP);

        boolean oldHasTSP = newConcept.hasProperty(RDF.type, tspType);
        boolean oldHasTOP = newConcept.hasProperty(RDF.type, topType);

        boolean newHasTSP = newType.toLowerCase().contains("subjekt");
        boolean newHasTOP = newType.toLowerCase().contains("objekt");

        if (oldHasTSP && !newHasTSP) {
            toRemove.add(model.createStatement(oldConcept, RDF.type, tspType));
        }
        if (oldHasTOP && !newHasTOP) {
            toRemove.add(model.createStatement(oldConcept, RDF.type, topType));
        }
        if (!oldHasTSP && newHasTSP) {
            toAdd.add(model.createStatement(newConcept, RDF.type, tspType));
        }
        if (!oldHasTOP && newHasTOP) {
            toAdd.add(model.createStatement(newConcept, RDF.type, topType));
        }
    }

    private void updateAgenda(Resource newConcept, String agendaCode, Resource oldConcept,
                              Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (agendaCode == null) return;

        Property agendaProperty = model.createProperty(DEFAULT_NS + AGENDOVY_104 + AGENDA_LONG);

        if (agendaCode.trim().isEmpty()) {
            removeAllByPredicate(oldConcept, agendaProperty, toRemove, toAdd);
            return;
        }

        removeAllByPredicate(newConcept, agendaProperty, toRemove, toAdd);

        if (UtilityMethods.isValidAgendaValue(agendaCode)) {
            String transformed = UtilityMethods.transformAgendaValue(agendaCode);
            if (DataTypeConverter.isUri(transformed)) {
                toAdd.add(model.createStatement(newConcept, agendaProperty, model.createResource(transformed)));
            } else {
                Literal typedLiteral = DataTypeConverter.createTypedLiteral(transformed, model, null, AGENDA_LONG);
                toAdd.add(model.createStatement(newConcept, agendaProperty, typedLiteral));
            }
        }
    }

    private void updateAIS(Resource newConcept, String aisCode, Resource oldConcept,
                           Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (aisCode == null) return;

        Property aisProperty = model.createProperty(DEFAULT_NS + AGENDOVY_104 + UDAJE_AIS);

        if (aisCode.trim().isEmpty()) {
            removeAllByPredicate(oldConcept, aisProperty, toRemove, toAdd);
            return;
        }

        removeAllByPredicate(newConcept, aisProperty, toRemove, toAdd);

        if (UtilityMethods.isValidAISValue(aisCode)) {
            String transformed = UtilityMethods.transformAISValue(aisCode);
            if (DataTypeConverter.isUri(transformed)) {
                toAdd.add(model.createStatement(newConcept, aisProperty, model.createResource(transformed)));
            } else {
                Literal typedLiteral = DataTypeConverter.createTypedLiteral(transformed, model, null, UDAJE_AIS);
                toAdd.add(model.createStatement(newConcept, aisProperty, typedLiteral));
            }
        }
    }

    private void updatePrivacyProvisionsList(Resource newConcept, List<String> privacyProvisions,
                                             Resource oldConcept, Model model, Set<Statement> toRemove,
                                             Set<Statement> toAdd) {
        if (privacyProvisions == null) return;

        Property provisionProperty = model.createProperty(OFN_NAMESPACE_LEGAL + USTANOVENI_NEVEREJNOST);
        Set<String> oldProvisions = getResourceURIs(oldConcept, provisionProperty);
        Set<String> newProvisions = new HashSet<>();

        for (String provision : privacyProvisions) {
            if (provision != null && !provision.trim().isEmpty() && UtilityMethods.containsEliPattern(provision)) {
                String eliPart = UtilityMethods.extractEliPart(provision);
                if (eliPart != null) {
                    String transformedProvision = "https://opendata.eselpoint.cz/esel-esb/" + eliPart;
                    newProvisions.add(transformedProvision);
                }
            }
        }

        if (privacyProvisions.isEmpty() || newProvisions.isEmpty()) {
            if (!oldProvisions.isEmpty()) {
                removeAllByPredicate(newConcept, provisionProperty, toRemove, toAdd);
            }
        } else if (!oldProvisions.equals(newProvisions)) {
            removeAllByPredicate(newConcept, provisionProperty, toRemove, toAdd);
            for (String provisionURI : newProvisions) {
                toAdd.add(model.createStatement(newConcept, provisionProperty, model.createResource(provisionURI)));
            }
        }
    }

    private void updateGovernanceProperty(Resource newConcept, String newValue, String propertyName,
                                           Resource oldConcept, Model model, Set<Statement> toRemove,
                                           Set<Statement> toAdd) {
        if (newValue == null) return;

        Property property = model.createProperty(OFN_NAMESPACE + propertyName);
        String oldIRI = getResourceURI(oldConcept, property);

        if (newValue.trim().isEmpty()) {
            if (oldIRI != null) {
                removeAllByPredicate(newConcept, property, toRemove, toAdd);
            }
            return;
        }

        String newIRI = generateGovernanceIRI(newValue, propertyName);

        if (!Objects.equals(oldIRI, newIRI)) {
            removeAllByPredicate(newConcept, property, toRemove, toAdd);
            if (newIRI != null) {
                toAdd.add(model.createStatement(newConcept, property, model.createResource(newIRI)));
            }
        }
    }

    private void updateGovernancePropertyList(Resource newConcept, List<String> newValues,
                                              Resource oldConcept, Model model, Set<Statement> toRemove,
                                               Set<Statement> toAdd) {
        if (newValues == null) return;

        Property property = model.createProperty(OFN_NAMESPACE + ZPUSOB_SDILENI);
        Set<String> oldIRIs = getResourceURIs(oldConcept, property);
        Set<String> newIRIs = new HashSet<>();

        for (String value : newValues) {
            if (value != null && !value.trim().isEmpty()) {
                String newIRI = generateGovernanceIRI(value, ZPUSOB_SDILENI);
                if (newIRI != null) {
                    newIRIs.add(newIRI);
                }
            }
        }

        if (newValues.isEmpty() || newIRIs.isEmpty()) {
            if (!oldIRIs.isEmpty()) {
                removeAllByPredicate(newConcept, property, toRemove, toAdd);
            }
        } else if (!oldIRIs.equals(newIRIs)) {
            removeAllByPredicate(newConcept, property, toRemove, toAdd);
            for (String iri : newIRIs) {
                toAdd.add(model.createStatement(newConcept, property, model.createResource(iri)));
            }
        }
    }

    private void updateBroaderConceptList(Resource newConcept, List<String> broaderConcept, Resource oldConcept,
                                       Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (broaderConcept == null) return;

        Property hierarchyProp = model.createProperty(uriGenerator.getEffectiveNamespace() + "nadřazená-třída");
        Set<String> oldBroader = getResourceURIs(oldConcept, RDFS.subClassOf);
        Set<String> newBroader = parseBroaderConcepts(broaderConcept);

        if (broaderConcept.isEmpty() || newBroader.isEmpty()) {
            if (!oldBroader.isEmpty()) {
                removeAllByPredicate(newConcept, RDFS.subClassOf, toRemove, toAdd);
                removeAllByPredicate(newConcept, hierarchyProp, toRemove, toAdd);
            }
        } else if (!oldBroader.equals(newBroader)) {
            removeAllByPredicate(newConcept, RDFS.subClassOf, toRemove, toAdd);
            removeAllByPredicate(newConcept, hierarchyProp, toRemove, toAdd);
            for (String broader : newBroader) {
                toAdd.add(model.createStatement(newConcept, RDFS.subClassOf, model.createResource(broader)));
                toAdd.add(model.createStatement(newConcept, hierarchyProp, model.createResource(broader)));
            }
        }
    }

    private void updateDomainRange(Resource newConcept, Property property, String newValue,
                                    Resource oldConcept, Model model, Set<Statement> toRemove,
                                    Set<Statement> toAdd) {
        if (newValue == null) return;

        String oldURI = getResourceURI(oldConcept, property);

        if (newValue.trim().isEmpty()) {
            if (oldURI != null) {
                removeAllByPredicate(newConcept, property, toRemove, toAdd);
            }
            return;
        }

        String newURI = DataTypeConverter.isUri(newValue) ? newValue : uriGenerator.generateConceptURI(newValue, null);

        if (!Objects.equals(oldURI, newURI)) {
            removeAllByPredicate(newConcept, property, toRemove, toAdd);
            toAdd.add(model.createStatement(newConcept, property, model.createResource(newURI)));
        }
    }

    private void updateDataTypeRange(Resource newConcept, String dataType, Resource oldConcept,
                                      Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (dataType == null) return;

        String oldRangeURI = getResourceURI(oldConcept, RDFS.range);

        if (dataType.trim().isEmpty()) {
            if (oldRangeURI != null) {
                removeAllByPredicate(newConcept, RDFS.range, toRemove, toAdd);
            }
            return;
        }

        String newRangeURI = DataTypeConverter.getXSDTypeURI(dataType.trim());

        if (!Objects.equals(oldRangeURI, newRangeURI)) {
            removeAllByPredicate(newConcept, RDFS.range, toRemove, toAdd);
            toAdd.add(model.createStatement(newConcept, RDFS.range, model.createResource(newRangeURI)));
        }
    }

    private void updateSuperPropertyList(Resource newConcept, List<String> superProperties, Resource oldConcept,
                                          Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (superProperties == null) return;

        Set<String> oldSuperProps = getResourceURIs(oldConcept, RDFS.subPropertyOf);
        Set<String> newSuperProps = parseSuperProperties(superProperties);

        if (superProperties.isEmpty() || newSuperProps.isEmpty()) {
            if (!oldSuperProps.isEmpty()) {
                removeAllByPredicate(newConcept, RDFS.subPropertyOf, toRemove, toAdd);
            }
        } else if (!oldSuperProps.equals(newSuperProps)) {
            removeAllByPredicate(newConcept, RDFS.subPropertyOf, toRemove, toAdd);
            for (String superProp : newSuperProps) {
                toAdd.add(model.createStatement(newConcept, RDFS.subPropertyOf, model.createResource(superProp)));
            }
        }
    }

    private Set<String> parseSuperProperties(List<String> superProperties) {
        if (superProperties == null || superProperties.isEmpty()) return Collections.emptySet();
        Set<String> result = new HashSet<>();
        for (String prop : superProperties) {
            String trimmed = prop.trim();
            if (!trimmed.isEmpty()) {
                String uri = DataTypeConverter.isUri(trimmed) ? trimmed :
                        uriGenerator.generateConceptURI(trimmed, null);
                result.add(uri);
            }
        }
        return result;
    }

    private void updateBooleanProperty(Resource newConcept, String propertyName, Boolean newValue,
                                       Resource oldConcept, Model model, Set<Statement> toRemove,
                                       Set<Statement> toAdd) {
        if (newValue == null) return;

        Property property = switch (propertyName) {
            case JE_PPDF_LONG -> model.createProperty(DEFAULT_NS + AGENDOVY_104 + JE_PPDF_LONG);
            case IS_PUBLIC -> model.createProperty(OFN_NAMESPACE_VS + JE_VEREJNY);
            case IN_TEZAURUS -> model.createProperty(uriGenerator.getEffectiveNamespace() + IN_TEZAURUS);
            default -> {
                log.warn("Unknown boolean property: {}", propertyName);
                yield null;
            }
        };

        if (property == null) return;

        String oldValue = getPropertyValue(oldConcept, property);
        String newValueStr = newValue.toString();

        if (!Objects.equals(oldValue, newValueStr)) {
            removeAllByPredicate(newConcept, property, toRemove, toAdd);
            toAdd.add(model.createStatement(newConcept, property, model.createLiteral(newValueStr)));
        }
    }

    private void updateLegalSourceList(Resource newConcept, Property property, List<String> newSources,
                                        Resource oldConcept, Model model, Set<Statement> toRemove,
                                        Set<Statement> toAdd) {
        if (newSources == null) return;

        Set<String> oldSourceURIs = getResourceURIs(oldConcept, property);
        Set<String> newSourceURIs = new HashSet<>();

        for (String source : newSources) {
            if (source != null && !source.trim().isEmpty() && UtilityMethods.containsEliPattern(source)) {
                String eliPart = UtilityMethods.extractEliPart(source);
                if (eliPart != null) {
                    String transformedUrl = "https://opendata.eselpoint.cz/esel-esb/" + eliPart;
                    newSourceURIs.add(transformedUrl);
                }
            }
        }

        if (newSources.isEmpty() || newSourceURIs.isEmpty()) {
            if (!oldSourceURIs.isEmpty()) {
                removeAllByPredicate(newConcept, property, toRemove, toAdd);
            }
            return;
        }

        if (!oldSourceURIs.equals(newSourceURIs)) {
            removeAllByPredicate(newConcept, property, toRemove, toAdd);
            for (String sourceURI : newSourceURIs) {
                toAdd.add(model.createStatement(newConcept, property, model.createResource(sourceURI)));
            }
        }
    }

    private void updateNonLegalSourceList(Resource newConcept, Property property, List<String> newSources,
                                           Resource oldConcept, Model model, Set<Statement> toRemove,
                                           Set<Statement> toAdd) {
        if (newSources == null) return;

        List<String> nonEmptySources = newSources.stream()
                .filter(s -> s != null && !s.trim().isEmpty())
                .map(String::trim)
                .toList();

        if (nonEmptySources.isEmpty()) {
            removeAllByPredicate(oldConcept, property, toRemove, toAdd);
            return;
        }

        removeAllByPredicate(newConcept, property, toRemove, toAdd);

        Resource digitalObjectType = model.createResource("https://slovník.gov.cz/generický/digitální-objekty/pojem/digitální-objekt");
        Property schemaUrlProperty = model.createProperty("http://schema.org/url");
        Property dctermsTitle = model.createProperty("http://purl.org/dc/terms/title");

        for (String source : nonEmptySources) {
            Resource digitalDocument = model.createResource();

            toAdd.add(model.createStatement(digitalDocument, RDF.type, digitalObjectType));

            if (UtilityMethods.isValidUrl(source)) {
                toAdd.add(model.createStatement(digitalDocument, schemaUrlProperty, model.createResource(source)));
            } else {
                toAdd.add(model.createStatement(digitalDocument, dctermsTitle, model.createLiteral(source, DEFAULT_LANG)));
            }

            toAdd.add(model.createStatement(newConcept, property, digitalDocument));
        }
    }

    private void renameConceptIRI(Model model, String oldIRI, String newIRI,
                                   Set<Statement> toRemove, Set<Statement> toAdd,
                                   Set<Property> excludePredicates) {
        Resource oldConcept = model.getResource(oldIRI);

        StmtIterator iter = model.listStatements(oldConcept, null, (RDFNode) null);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            Property predicate = stmt.getPredicate();

            if (excludePredicates.contains(predicate)) {
                toRemove.add(stmt);
                continue;
            }

            toRemove.add(stmt);
            toAdd.add(model.createStatement(
                    model.getResource(newIRI),
                    predicate,
                    stmt.getObject()
            ));
        }

        iter = model.listStatements(null, null, oldConcept);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            toRemove.add(stmt);
            toAdd.add(model.createStatement(
                    stmt.getSubject(),
                    stmt.getPredicate(),
                    model.getResource(newIRI)
            ));
        }
    }

    private Set<Property> buildPredicatesToExclude(ConceptEditModel editModel, Model model) {
        Set<Property> predicates = new HashSet<>();

        if (editModel.getNameModel() != null) predicates.add(SKOS.prefLabel);
        if (editModel.getDefinitionModel() != null) predicates.add(SKOS.definition);
        if (editModel.getAltNameModel() != null) predicates.add(SKOS.altLabel);
        if (editModel.getDescriptionModel() != null) {
            predicates.add(model.createProperty("http://purl.org/dc/terms/description"));
        }
        if (editModel.getExactMatch() != null) {
            predicates.add(model.createProperty("http://www.w3.org/2004/02/skos/core#exactMatch"));
        }
        if (editModel.getDefiningLegalSource() != null || editModel.getRelatedLegalSource() != null) {
            predicates.add(model.createProperty(OFN_NAMESPACE + DEFINUJICI_USTANOVENI));
            predicates.add(model.createProperty(OFN_NAMESPACE + SOUVISEJICI_USTANOVENI));
        }
        if (editModel.getDefiningNonLegalSource() != null || editModel.getRelatedNonLegalSource() != null) {
            predicates.add(model.createProperty(uriGenerator.getEffectiveNamespace() + DEFINUJICI_NELEGISLATIVNI_ZDROJ));
            predicates.add(model.createProperty(uriGenerator.getEffectiveNamespace() + SOUVISEJICI_NELEGISLATIVNI_ZDROJ));
        }
        if (editModel.getInTezaurus() != null) {
            predicates.add(model.createProperty(uriGenerator.getEffectiveNamespace() + IN_TEZAURUS));
        }
        if (editModel.getNamespace() != null) {
            predicates.add(model.createProperty(uriGenerator.getEffectiveNamespace() + NAMESPACE));
        }

        switch (editModel.getConceptTypeEnum()) {
            case TRIDA -> {
                ClassConceptEditModel classModel = (ClassConceptEditModel) editModel;
                if (classModel.getType() != null) {
                    predicates.add(RDF.type);
                }
                if (classModel.getBroaderConcept() != null) {
                    predicates.add(RDFS.subClassOf);
                    predicates.add(model.createProperty(uriGenerator.getEffectiveNamespace() + "nadřazená-třída"));
                }
                addCommonConceptPredicates(predicates, classModel, model);
            }
            case VLASTNOST -> {
                PropertyConceptEditModel propModel = (PropertyConceptEditModel) editModel;
                if (propModel.getDomain() != null) predicates.add(RDFS.domain);
                if (propModel.getDataType() != null) predicates.add(RDFS.range);
                if (propModel.getSuperProperty() != null) predicates.add(RDFS.subPropertyOf);
                addCommonConceptPredicates(predicates, propModel, model);
            }
            case VZTAH -> {
                RelationshipConceptEditModel relModel = (RelationshipConceptEditModel) editModel;
                if (relModel.getDomain() != null) predicates.add(RDFS.domain);
                if (relModel.getRange() != null) predicates.add(RDFS.range);
                if (relModel.getSuperRelation() != null) predicates.add(RDFS.subPropertyOf);
                addCommonConceptPredicates(predicates, relModel, model);
            }
        }

        return predicates;
    }

    private void addCommonConceptPredicates(Set<Property> predicates, ClassConceptEditModel editModel, Model model) {
        if (editModel.getIsInPPDF() != null) {
            predicates.add(model.createProperty(DEFAULT_NS + AGENDOVY_104 + JE_PPDF_LONG));
        }
        if (editModel.getIsPublic() != null) {
            predicates.add(model.createProperty(OFN_NAMESPACE_VS + JE_VEREJNY));
        }
        if (editModel.getAgendaCode() != null) {
            predicates.add(model.createProperty(DEFAULT_NS + AGENDOVY_104 + AGENDA));
        }
        if (editModel.getAgendaSystemCode() != null) {
            predicates.add(model.createProperty(DEFAULT_NS + AGENDOVY_104 + AIS));
        }
        if (editModel.getContentType() != null) {
            predicates.add(model.createProperty(OFN_NAMESPACE + TYP_OBSAHU));
        }
        if (editModel.getAcquisitionMethod() != null) {
            predicates.add(model.createProperty(OFN_NAMESPACE + ZPUSOB_ZISKANI));
        }
        if (editModel.getSharingMethod() != null) {
            predicates.add(model.createProperty(OFN_NAMESPACE + ZPUSOB_SDILENI));
        }
        if (editModel.getPrivacyProvisions() != null) {
            predicates.add(model.createProperty(OFN_NAMESPACE_LEGAL + USTANOVENI_NEVEREJNOST));
        }
    }

    private void addCommonConceptPredicates(Set<Property> predicates, PropertyConceptEditModel editModel, Model model) {
        if (editModel.getIsInPPDF() != null) {
            predicates.add(model.createProperty(DEFAULT_NS + AGENDOVY_104 + JE_PPDF_LONG));
        }
        if (editModel.getIsPublic() != null) {
            predicates.add(model.createProperty(OFN_NAMESPACE_VS + JE_VEREJNY));
        }
        if (editModel.getAgendaCode() != null) {
            predicates.add(model.createProperty(DEFAULT_NS + AGENDOVY_104 + AGENDA));
        }
        if (editModel.getAgendaSystemCode() != null) {
            predicates.add(model.createProperty(DEFAULT_NS + AGENDOVY_104 + AIS));
        }
        if (editModel.getContentType() != null) {
            predicates.add(model.createProperty(OFN_NAMESPACE + TYP_OBSAHU));
        }
        if (editModel.getAcquisitionMethod() != null) {
            predicates.add(model.createProperty(OFN_NAMESPACE + ZPUSOB_ZISKANI));
        }
        if (editModel.getSharingMethod() != null) {
            predicates.add(model.createProperty(OFN_NAMESPACE + ZPUSOB_SDILENI));
        }
        if (editModel.getPrivacyProvisions() != null) {
            predicates.add(model.createProperty(OFN_NAMESPACE_LEGAL + USTANOVENI_NEVEREJNOST));
        }
    }

    private void addCommonConceptPredicates(Set<Property> predicates, RelationshipConceptEditModel editModel, Model model) {
        if (editModel.getIsInPPDF() != null) {
            predicates.add(model.createProperty(DEFAULT_NS + AGENDOVY_104 + JE_PPDF_LONG));
        }
        if (editModel.getIsPublic() != null) {
            predicates.add(model.createProperty(OFN_NAMESPACE_VS + JE_VEREJNY));
        }
        if (editModel.getAgendaCode() != null) {
            predicates.add(model.createProperty(DEFAULT_NS + AGENDOVY_104 + AGENDA));
        }
        if (editModel.getAgendaSystemCode() != null) {
            predicates.add(model.createProperty(DEFAULT_NS + AGENDOVY_104 + AIS));
        }
        if (editModel.getContentType() != null) {
            predicates.add(model.createProperty(OFN_NAMESPACE + TYP_OBSAHU));
        }
        if (editModel.getAcquisitionMethod() != null) {
            predicates.add(model.createProperty(OFN_NAMESPACE + ZPUSOB_ZISKANI));
        }
        if (editModel.getSharingMethod() != null) {
            predicates.add(model.createProperty(OFN_NAMESPACE + ZPUSOB_SDILENI));
        }
        if (editModel.getPrivacyProvisions() != null) {
            predicates.add(model.createProperty(OFN_NAMESPACE_LEGAL + USTANOVENI_NEVEREJNOST));
        }
    }

    private String getPropertyValue(Resource resource, Property property) {
        Statement stmt = resource.getProperty(property);
        return stmt != null && stmt.getObject().isLiteral() ? stmt.getObject().asLiteral().getString() : null;
    }

    private Map<String, String> getPropertyValuesWithLanguage(Resource resource) {
        Map<String, String> valuesWithLang = new HashMap<>();
        StmtIterator iter = resource.listProperties(SKOS.altLabel);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            if (stmt.getObject().isLiteral()) {
                Literal literal = stmt.getObject().asLiteral();
                String value = literal.getString();
                String lang = literal.getLanguage() != null && !literal.getLanguage().isEmpty()
                    ? literal.getLanguage()
                    : DEFAULT_LANG;
                valuesWithLang.put(value, lang);
            }
        }
        return valuesWithLang;
    }

    private Map<String, String> getAllPropertyValuesWithLanguage(Resource resource, Property property) {
        Map<String, String> valuesWithLang = new HashMap<>();
        StmtIterator iter = resource.listProperties(property);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            if (stmt.getObject().isLiteral()) {
                Literal literal = stmt.getObject().asLiteral();
                String lang = literal.getLanguage() != null && !literal.getLanguage().isEmpty()
                        ? literal.getLanguage()
                        : DEFAULT_LANG;
                valuesWithLang.put(lang, literal.getString());
            }
        }
        return valuesWithLang;
    }

    private String getNameForUriGeneration(com.dia.ismdtoolbackend.models.NameModel nameModel) {
        if (nameModel == null || nameModel.getName() == null || nameModel.getName().isEmpty()) {
            return "";
        }
        Map<String, String> names = nameModel.getName();
        if (names.containsKey("cs")) {
            return names.get("cs");
        }
        if (names.containsKey(DEFAULT_LANG)) {
            return names.get(DEFAULT_LANG);
        }
        return names.values().iterator().next();
    }

    private String getNameForUriGeneration(Resource resource) {
        Map<String, String> existingNames = getAllPropertyValuesWithLanguage(resource, SKOS.prefLabel);
        if (existingNames.isEmpty()) {
            return "";
        }
        if (existingNames.containsKey("cs")) {
            return existingNames.get("cs");
        }
        if (existingNames.containsKey(DEFAULT_LANG)) {
            return existingNames.get(DEFAULT_LANG);
        }
        return existingNames.values().iterator().next();
    }

    private String getResourceURI(Resource resource, Property property) {
        Statement stmt = resource.getProperty(property);
        return stmt != null && stmt.getObject().isResource() ? stmt.getObject().asResource().getURI() : null;
    }

    private Set<String> getResourceURIs(Resource resource, Property property) {
        Set<String> uris = new HashSet<>();
        StmtIterator iter = resource.listProperties(property);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            if (stmt.getObject().isResource()) {
                uris.add(stmt.getObject().asResource().getURI());
            }
        }
        return uris;
    }

    private void removeAllByPredicate(Resource resource, Property property, Set<Statement> toRemove, Set<Statement> toAdd) {
        StmtIterator iter = resource.listProperties(property);
        while (iter.hasNext()) {
            toRemove.add(iter.next());
        }

        toAdd.removeIf(stmt ->
                stmt.getSubject().equals(resource) &&
                        stmt.getPredicate().equals(property)
        );
    }

    private Set<String> parseBroaderConcepts(List<String> broaderConcept) {
        if (broaderConcept == null || broaderConcept.isEmpty()) return Collections.emptySet();
        Set<String> result = new HashSet<>();
        for (String concept : broaderConcept) {
            String trimmed = concept.trim();
            if (!trimmed.isEmpty()) {
                String uri = DataTypeConverter.isUri(trimmed) ? trimmed :
                            uriGenerator.generateConceptURI(trimmed, null);
                result.add(uri);
            }
        }
        return result;
    }

    private String generateGovernanceIRI(String value, String propertyName) {
        String sanitized = UtilityMethods.sanitizeForIRI(value);
        return switch (propertyName) {
            case TYP_OBSAHU -> "https://data.dia.gov.cz/zdroj/číselníky/typy-obsahu-údajů/položky/" + sanitized;
            case ZPUSOB_SDILENI -> "https://data.dia.gov.cz/zdroj/číselníky/způsoby-sdílení-údajů/položky/" + sanitized;
            case ZPUSOB_ZISKANI -> "https://data.dia.gov.cz/zdroj/číselníky/způsoby-získání-údajů/položky/" + sanitized;
            default -> null;
        };
    }

    private String determineEffectiveNamespace(String namespace) {
        if (namespace != null && !namespace.trim().isEmpty() && UtilityMethods.isValidIRI(namespace)) {
            return UtilityMethods.ensureNamespaceEndsWithDelimiter(namespace);
        }
        return DEFAULT_NS;
    }

    private void updateDataClassification(Resource newConcept, Boolean isPublic,
                                          Resource oldConcept, Model model, Set<Statement> toRemove,
                                          Set<Statement> toAdd) {
        Resource verejnyLegal = model.getResource(OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ);
        Resource neverejnyLegal = model.getResource(OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ);

        if (oldConcept.hasProperty(RDF.type, verejnyLegal) || oldConcept.hasProperty(RDF.type, neverejnyLegal)) {
            processPropertyWithLegalNamespace(newConcept, isPublic, oldConcept, model, toRemove, toAdd, verejnyLegal, neverejnyLegal);
        }
    }

    private void processPropertyWithLegalNamespace(Resource newConcept, Boolean isPublic,
                                                   Resource oldConcept, Model model, Set<Statement> toRemove,
                                                   Set<Statement> toAdd, Resource verejnyLegal, Resource neverejnyLegal) {
        if (oldConcept.hasProperty(RDF.type, verejnyLegal)) {
            toRemove.add(model.createStatement(oldConcept, RDF.type, verejnyLegal));
        }

        if (oldConcept.hasProperty(RDF.type, neverejnyLegal)) {
            toRemove.add(model.createStatement(oldConcept, RDF.type, neverejnyLegal));
        }

        if (isPublic != null) {
            if (isPublic) {
                toAdd.add(model.createStatement(newConcept, RDF.type, verejnyLegal));
            } else {
                toAdd.add(model.createStatement(newConcept, RDF.type, neverejnyLegal));
            }
        }
    }

    private void updateSharedGovernanceMetadata(Resource newConcept, Boolean isInPPDF, String agendaCode,
                                                 String agendaSystemCode, List<String> sharingMethod,
                                                 String acquisitionMethod, String contentType,
                                                 Resource oldConcept, Model model, Set<Statement> toRemove,
                                                 Set<Statement> toAdd) {
        updateBooleanProperty(newConcept, JE_PPDF, isInPPDF, oldConcept, model, toRemove, toAdd);
        updateStringProperty(newConcept, AGENDA_CODE, agendaCode, oldConcept, model, toRemove, toAdd);
        updateStringProperty(newConcept, AIS, agendaSystemCode, oldConcept, model, toRemove, toAdd);
        updateGovernanceProperty(newConcept, contentType, TYP_OBSAHU, oldConcept, model, toRemove, toAdd);
        updateGovernanceProperty(newConcept, acquisitionMethod, ZPUSOB_ZISKANI, oldConcept, model, toRemove, toAdd);
        updateGovernancePropertyList(newConcept, sharingMethod, oldConcept, model, toRemove, toAdd);
    }

    private static class EditContext {
        final Resource oldConcept;
        final Resource newConcept;
        final Model model;
        final boolean iriChanged;
        final Map<Property, List<Statement>> existingStatements;

        public EditContext(Model model, String oldIRI, String newIRI) {
            this.model = model;
            this.oldConcept = model.getResource(oldIRI);
            this.newConcept = model.getResource(newIRI);
            this.iriChanged = !oldIRI.equals(newIRI);

            this.existingStatements = new HashMap<>();
            StmtIterator iter = oldConcept.listProperties();
            while (iter.hasNext()) {
                Statement stmt = iter.next();
                existingStatements
                        .computeIfAbsent(stmt.getPredicate(), k -> new ArrayList<>())
                        .add(stmt);
            }
        }
    }

    public static class EditResult {
        public final String newConceptIRI;
        public final boolean iriChanged;
        public final int changesCount;

        public EditResult(String newConceptIRI, boolean iriChanged, int changesCount) {
            this.newConceptIRI = newConceptIRI;
            this.iriChanged = iriChanged;
            this.changesCount = changesCount;
        }
    }
}
