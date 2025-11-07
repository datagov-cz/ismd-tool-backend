package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.DescriptionModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.concept.*;
import com.dia.utility.DataTypeConverter;
import com.dia.utility.URIGenerator;
import com.dia.utility.UtilityMethods;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private static final String PRIVACY_PROVISION = "privacyProvision";
    private static final String IN_TEZAURUS = "inTezaurus";
    private static final String NAMESPACE = "namespace";

    private final URIGenerator uriGenerator = new URIGenerator();

    public EditResult editConcept(ConceptEditModel editModel, Model model, String graphName) {
        String oldConceptIRI = editModel.getConceptIRI();
        Resource existingConcept = model.getResource(oldConceptIRI);

        if (existingConcept == null || !model.containsResource(existingConcept)) {
            throw new IllegalArgumentException("Concept with IRI " + oldConceptIRI + " not found in the model");
        }

        String effectiveNamespace = determineEffectiveNamespace(graphName);
        uriGenerator.setEffectiveNamespace(effectiveNamespace);

        String newName = editModel.getNameModel() != null ? editModel.getNameModel().getName() : null;
        String oldName = getCurrentName(existingConcept);
        boolean nameChanged = newName != null && !newName.equals(oldName);

        String newConceptIRI = oldConceptIRI;
        if (nameChanged) {
            newConceptIRI = uriGenerator.generateConceptURI(newName, editModel.getIdentifier());
            log.info("Name changed from '{}' to '{}', updating IRI from {} to {}",
                    oldName, newName, oldConceptIRI, newConceptIRI);
        }

        Set<Statement> statementsToRemove = new HashSet<>();
        Set<Statement> statementsToAdd = new HashSet<>();

        switch (editModel.getConceptTypeEnum()) {
            case TRIDA -> editClassConcept((ClassConceptEditModel) editModel, existingConcept,
                    model, statementsToRemove, statementsToAdd, newConceptIRI);
            case VLASTNOST -> editPropertyConcept((PropertyConceptEditModel) editModel, existingConcept,
                    model, statementsToRemove, statementsToAdd, newConceptIRI);
            case VZTAH -> editRelationshipConcept((RelationshipConceptEditModel) editModel, existingConcept,
                    model, statementsToRemove, statementsToAdd, newConceptIRI);
        }

        if (nameChanged && !oldConceptIRI.equals(newConceptIRI)) {
            renameConceptIRI(model, oldConceptIRI, newConceptIRI, statementsToRemove, statementsToAdd);
        }

        model.remove(statementsToRemove.toArray(new Statement[0]));
        model.add(statementsToAdd.toArray(new Statement[0]));

        log.info("Applied {} removals and {} additions", statementsToRemove.size(), statementsToAdd.size());

        return new EditResult(newConceptIRI, nameChanged, statementsToRemove.size() + statementsToAdd.size());
    }

    private void editClassConcept(ClassConceptEditModel editModel, Resource existingConcept,
                                   Model model, Set<Statement> toRemove, Set<Statement> toAdd,
                                   String newConceptIRI) {
        editCommonFields(editModel, existingConcept, model, toRemove, toAdd, newConceptIRI);

        Resource conceptResource = model.getResource(newConceptIRI);

        updateStringProperty(conceptResource, TYPE, editModel.getType(), existingConcept, model, toRemove, toAdd);
        updateStringProperty(conceptResource, AGENDA_CODE, editModel.getAgendaCode(), existingConcept, model, toRemove, toAdd);
        updateStringProperty(conceptResource, AIS, editModel.getAgendaSystemCode(), existingConcept, model, toRemove, toAdd);
        updateGovernanceProperty(conceptResource, editModel.getContentType(), TYP_OBSAHU, existingConcept, model, toRemove, toAdd);
        updateGovernanceProperty(conceptResource, editModel.getAcquisitionMethod(), ZPUSOB_ZISKANI, existingConcept, model, toRemove, toAdd);
        updateGovernanceProperty(conceptResource, editModel.getSharingMethod(), ZPUSOB_SDILENI, existingConcept, model, toRemove, toAdd);
        updateStringProperty(conceptResource, IS_PUBLIC, editModel.getIsPublic(), existingConcept, model, toRemove, toAdd);
        updateStringProperty(conceptResource, PRIVACY_PROVISION, editModel.getPrivacyProvision(), existingConcept, model, toRemove, toAdd);
        updateBroaderConcept(conceptResource, editModel.getBroaderConcept(), existingConcept, model, toRemove, toAdd);
    }

    private void editPropertyConcept(PropertyConceptEditModel editModel, Resource existingConcept,
                                      Model model, Set<Statement> toRemove, Set<Statement> toAdd,
                                      String newConceptIRI) {
        editCommonFields(editModel, existingConcept, model, toRemove, toAdd, newConceptIRI);

        Resource conceptResource = model.getResource(newConceptIRI);

        updateDomainRange(conceptResource, RDFS.domain, editModel.getDomain(), existingConcept, model, toRemove, toAdd);
        updateDataTypeRange(conceptResource, editModel.getDataType(), existingConcept, model, toRemove, toAdd);
        updateSuperProperty(conceptResource, editModel.getSuperProperty(), existingConcept, model, toRemove, toAdd);
        updateBooleanProperty(conceptResource, editModel.getIsInPPDF(), existingConcept, model, toRemove, toAdd);
        updateStringProperty(conceptResource, AGENDA_CODE, editModel.getAgendaCode(), existingConcept, model, toRemove, toAdd);
        updateStringProperty(conceptResource, AIS, editModel.getAgendaSystemCode(), existingConcept, model, toRemove, toAdd);
        updateGovernanceProperty(conceptResource, editModel.getContentType(), TYP_OBSAHU, existingConcept, model, toRemove, toAdd);
        updateGovernanceProperty(conceptResource, editModel.getAcquisitionMethod(), ZPUSOB_ZISKANI, existingConcept, model, toRemove, toAdd);
        updateGovernanceProperty(conceptResource, editModel.getSharingMethod(), ZPUSOB_SDILENI, existingConcept, model, toRemove, toAdd);
        updateStringProperty(conceptResource, IS_PUBLIC, editModel.getIsPublic(), existingConcept, model, toRemove, toAdd);
        updateStringProperty(conceptResource, PRIVACY_PROVISION, editModel.getPrivacyProvision(), existingConcept, model, toRemove, toAdd);
    }

    private void editRelationshipConcept(RelationshipConceptEditModel editModel, Resource existingConcept,
                                          Model model, Set<Statement> toRemove, Set<Statement> toAdd,
                                          String newConceptIRI) {
        editCommonFields(editModel, existingConcept, model, toRemove, toAdd, newConceptIRI);

        Resource conceptResource = model.getResource(newConceptIRI);

        updateDomainRange(conceptResource, RDFS.domain, editModel.getDomain(), existingConcept, model, toRemove, toAdd);
        updateDomainRange(conceptResource, RDFS.range, editModel.getRange(), existingConcept, model, toRemove, toAdd);
        updateSuperProperty(conceptResource, editModel.getSuperRelation(), existingConcept, model, toRemove, toAdd);
        updateBooleanProperty(conceptResource, editModel.getIsInPPDF(), existingConcept, model, toRemove, toAdd);
        updateStringProperty(conceptResource, AGENDA_CODE, editModel.getAgendaCode(), existingConcept, model, toRemove, toAdd);
        updateStringProperty(conceptResource, AIS, editModel.getAgendaSystemCode(), existingConcept, model, toRemove, toAdd);
        updateGovernanceProperty(conceptResource, editModel.getContentType(), TYP_OBSAHU, existingConcept, model, toRemove, toAdd);
        updateGovernanceProperty(conceptResource, editModel.getAcquisitionMethod(), ZPUSOB_ZISKANI, existingConcept, model, toRemove, toAdd);
        updateGovernanceProperty(conceptResource, editModel.getSharingMethod(), ZPUSOB_SDILENI, existingConcept, model, toRemove, toAdd);
        updateStringProperty(conceptResource, IS_PUBLIC, editModel.getIsPublic(), existingConcept, model, toRemove, toAdd);
        updateStringProperty(conceptResource, PRIVACY_PROVISION, editModel.getPrivacyProvision(), existingConcept, model, toRemove, toAdd);
    }

    private void editCommonFields(ConceptEditModel editModel, Resource existingConcept,
                                   Model model, Set<Statement> toRemove, Set<Statement> toAdd,
                                   String newConceptIRI) {
        Resource conceptResource = model.getResource(newConceptIRI);

        updateNameModel(conceptResource, editModel.getNameModel(), existingConcept, model, toRemove, toAdd);
        updateDescriptionModel(conceptResource, editModel.getDescriptionModel(), existingConcept, model, toRemove, toAdd);
        updateDefinitionModel(conceptResource, editModel.getDefinitionModel(), existingConcept, model, toRemove, toAdd);
        updateAltNameModel(conceptResource, editModel.getAltNameModel(), existingConcept, model, toRemove, toAdd);
        updateLegalSources(conceptResource, editModel, existingConcept, model, toRemove, toAdd);
        updateNonLegalSources(conceptResource, editModel, existingConcept, model, toRemove, toAdd);
        updateExactMatch(conceptResource, editModel.getExactMatch(), existingConcept, model, toRemove, toAdd);
        updateStringProperty(conceptResource, IN_TEZAURUS, editModel.getInTezaurus(), existingConcept, model, toRemove, toAdd);
        updateStringProperty(conceptResource, NAMESPACE, editModel.getNamespace(), existingConcept, model, toRemove, toAdd);
    }

    private void updateNameModel(Resource newConcept, NameModel nameModel, Resource oldConcept,
                                 Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (nameModel == null || nameModel.getName() == null) return;

        String oldName = getCurrentName(oldConcept);
        if (!nameModel.getName().equals(oldName)) {
            removeAllByPredicate(oldConcept, SKOS.prefLabel, toRemove);
            String languageTag = nameModel.getLanguageTag() != null ? nameModel.getLanguageTag() : DEFAULT_LANG;
            toAdd.add(model.createStatement(newConcept, SKOS.prefLabel,
                    model.createLiteral(nameModel.getName(), languageTag)));
        }
    }

    private void updateDescriptionModel(Resource newConcept, DescriptionModel descModel, Resource oldConcept,
                                        Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (descModel == null) return;

        Property descProperty = model.createProperty("http://purl.org/dc/terms/description");
        String oldValue = getPropertyValue(oldConcept, descProperty);
        String newValue = descModel.getDescription();

        if (newValue == null || newValue.trim().isEmpty()) {
            if (oldValue != null) {
                removeAllByPredicate(oldConcept, descProperty, toRemove);
            }
        } else if (!newValue.equals(oldValue)) {
            removeAllByPredicate(oldConcept, descProperty, toRemove);
            String languageTag = descModel.getLanguageTag() != null ? descModel.getLanguageTag() : DEFAULT_LANG;
            toAdd.add(model.createStatement(newConcept, descProperty,
                    model.createLiteral(newValue, languageTag)));
        }
    }

    private void updateDefinitionModel(Resource newConcept, DefinitionModel defModel, Resource oldConcept,
                                        Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (defModel == null) return;

        String oldValue = getPropertyValue(oldConcept, SKOS.definition);
        String newValue = defModel.getDefinition();

        if (newValue == null || newValue.trim().isEmpty()) {
            if (oldValue != null) {
                removeAllByPredicate(oldConcept, SKOS.definition, toRemove);
            }
        } else if (!newValue.equals(oldValue)) {
            removeAllByPredicate(oldConcept, SKOS.definition, toRemove);
            String languageTag = defModel.getLanguageTag() != null ? defModel.getLanguageTag() : DEFAULT_LANG;
            toAdd.add(model.createStatement(newConcept, SKOS.definition,
                    model.createLiteral(newValue, languageTag)));
        }
    }

    private void updateAltNameModel(Resource newConcept, List<AltNameModel> altNameModels, Resource oldConcept,
                                     Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (altNameModels == null) return;

        Map<String, String> oldAltNamesWithLang = getPropertyValuesWithLanguage(oldConcept);

        Map<String, String> newAltNamesWithLang = new HashMap<>();
        for (AltNameModel altNameModel : altNameModels) {
            if (altNameModel != null && altNameModel.getAltName() != null && !altNameModel.getAltName().trim().isEmpty()) {
                String languageTag = altNameModel.getLanguageTag() != null
                    ? altNameModel.getLanguageTag()
                    : DEFAULT_LANG;
                newAltNamesWithLang.put(altNameModel.getAltName().trim(), languageTag);
            }
        }

        if (!oldAltNamesWithLang.equals(newAltNamesWithLang)) {
            removeAllByPredicate(oldConcept, SKOS.altLabel, toRemove);
            for (Map.Entry<String, String> entry : newAltNamesWithLang.entrySet()) {
                toAdd.add(model.createStatement(newConcept, SKOS.altLabel,
                        model.createLiteral(entry.getKey(), entry.getValue())));
            }
        }
    }

    private void updateLegalSources(Resource newConcept, ConceptEditModel editModel, Resource oldConcept,
                                     Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        Property definingProp = model.createProperty(uriGenerator.getEffectiveNamespace() + DEFINUJICI_USTANOVENI);
        Property relatedProp = model.createProperty(uriGenerator.getEffectiveNamespace() + SOUVISEJICI_USTANOVENI);

        updateLegalSourceProperty(newConcept, definingProp, editModel.getDefiningLegalSource(),
                oldConcept, model, toRemove, toAdd);
        updateLegalSourceProperty(newConcept, relatedProp, editModel.getRelatedLegalSource(),
                oldConcept, model, toRemove, toAdd);
    }

    private void updateNonLegalSources(Resource newConcept, ConceptEditModel editModel, Resource oldConcept,
                                        Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        Property definingProp = model.createProperty(uriGenerator.getEffectiveNamespace() + DEFINUJICI_NELEGISLATIVNI_ZDROJ);
        Property relatedProp = model.createProperty(uriGenerator.getEffectiveNamespace() + SOUVISEJICI_NELEGISLATIVNI_ZDROJ);

        updateNonLegalSourceProperty(newConcept, definingProp, editModel.getDefiningNonLegalSource(),
                oldConcept, model, toRemove, toAdd);
        updateNonLegalSourceProperty(newConcept, relatedProp, editModel.getRelatedNonLegalSource(),
                oldConcept, model, toRemove, toAdd);
    }

    private void updateExactMatch(Resource newConcept, String exactMatch, Resource oldConcept,
                                   Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (exactMatch == null) return;

        Property exactMatchProp = model.createProperty("http://www.w3.org/2004/02/skos/core#exactMatch");
        Set<String> oldMatches = getResourceURIs(oldConcept, exactMatchProp);
        Set<String> newMatches = parseMultipleIRIs(exactMatch);

        if (exactMatch.trim().isEmpty()) {
            if (!oldMatches.isEmpty()) {
                removeAllByPredicate(oldConcept, exactMatchProp, toRemove);
            }
        } else if (!oldMatches.equals(newMatches)) {
            removeAllByPredicate(oldConcept, exactMatchProp, toRemove);
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
            case IS_PUBLIC, PRIVACY_PROVISION -> updatePrivacyProvision(newConcept, newValue, oldConcept, model, toRemove, toAdd);
            default -> log.warn("Unknown string property: {}", propertyName);
        }
    }

    private void updateClassType(Resource newConcept, String newType, Resource oldConcept,
                                  Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (newType == null) return;

        Resource tspType = model.getResource(OFN_NAMESPACE + TSP);
        Resource topType = model.getResource(OFN_NAMESPACE + TOP);

        boolean oldHasTSP = oldConcept.hasProperty(RDF.type, tspType);
        boolean oldHasTOP = oldConcept.hasProperty(RDF.type, topType);

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

        Property agendaProperty = model.createProperty(uriGenerator.getEffectiveNamespace() + AGENDA);

        if (agendaCode.trim().isEmpty()) {
            removeAllByPredicate(oldConcept, agendaProperty, toRemove);
            return;
        }

        removeAllByPredicate(oldConcept, agendaProperty, toRemove);

        if (UtilityMethods.isValidAgendaValue(agendaCode)) {
            String transformed = UtilityMethods.transformAgendaValue(agendaCode);
            if (DataTypeConverter.isUri(transformed)) {
                toAdd.add(model.createStatement(newConcept, agendaProperty, model.createResource(transformed)));
            } else {
                Literal typedLiteral = DataTypeConverter.createTypedLiteral(transformed, model, null, AGENDA);
                toAdd.add(model.createStatement(newConcept, agendaProperty, typedLiteral));
            }
        }
    }

    private void updateAIS(Resource newConcept, String aisCode, Resource oldConcept,
                           Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (aisCode == null) return;

        Property aisProperty = model.createProperty(uriGenerator.getEffectiveNamespace() + AIS);

        if (aisCode.trim().isEmpty()) {
            removeAllByPredicate(oldConcept, aisProperty, toRemove);
            return;
        }

        removeAllByPredicate(oldConcept, aisProperty, toRemove);

        if (UtilityMethods.isValidAISValue(aisCode)) {
            String transformed = UtilityMethods.transformAISValue(aisCode);
            if (DataTypeConverter.isUri(transformed)) {
                toAdd.add(model.createStatement(newConcept, aisProperty, model.createResource(transformed)));
            } else {
                Literal typedLiteral = DataTypeConverter.createTypedLiteral(transformed, model, null, AIS);
                toAdd.add(model.createStatement(newConcept, aisProperty, typedLiteral));
            }
        }
    }

    private void updatePrivacyProvision(Resource newConcept, String privacyProvision, Resource oldConcept,
                                        Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (privacyProvision == null) return;

        Property provisionProperty = model.createProperty(uriGenerator.getEffectiveNamespace() + USTANOVENI_NEVEREJNOST);

        if (privacyProvision.trim().isEmpty()) {
            removeAllByPredicate(oldConcept, provisionProperty, toRemove);
            return;
        }

        removeAllByPredicate(oldConcept, provisionProperty, toRemove);

        if (UtilityMethods.containsEliPattern(privacyProvision)) {
            String eliPart = UtilityMethods.extractEliPart(privacyProvision);
            if (eliPart != null) {
                String transformedProvision = "https://opendata.eselpoint.cz/esel-esb/" + eliPart;
                toAdd.add(model.createStatement(newConcept, provisionProperty, model.createResource(transformedProvision)));
            }
        }
    }

    private void updateGovernanceProperty(Resource newConcept, String newValue, String propertyName,
                                           Resource oldConcept, Model model, Set<Statement> toRemove,
                                           Set<Statement> toAdd) {
        if (newValue == null) return;

        Property property = model.createProperty(uriGenerator.getEffectiveNamespace() + propertyName);
        String oldIRI = getResourceURI(oldConcept, property);

        if (newValue.trim().isEmpty()) {
            if (oldIRI != null) {
                removeAllByPredicate(oldConcept, property, toRemove);
            }
            return;
        }

        String newIRI = generateGovernanceIRI(newValue, propertyName);

        if (!Objects.equals(oldIRI, newIRI)) {
            removeAllByPredicate(oldConcept, property, toRemove);
            if (newIRI != null) {
                toAdd.add(model.createStatement(newConcept, property, model.createResource(newIRI)));
            }
        }
    }

    private void updateBroaderConcept(Resource newConcept, String broaderConcept, Resource oldConcept,
                                       Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (broaderConcept == null) return;

        Property hierarchyProp = model.createProperty(uriGenerator.getEffectiveNamespace() + "nadřazená-třída");
        Set<String> oldBroader = getResourceURIs(oldConcept, RDFS.subClassOf);
        Set<String> newBroader = parseBroaderConcepts(broaderConcept);

        if (broaderConcept.trim().isEmpty()) {
            if (!oldBroader.isEmpty()) {
                removeAllByPredicate(oldConcept, RDFS.subClassOf, toRemove);
                removeAllByPredicate(oldConcept, hierarchyProp, toRemove);
            }
        } else if (!oldBroader.equals(newBroader)) {
            removeAllByPredicate(oldConcept, RDFS.subClassOf, toRemove);
            removeAllByPredicate(oldConcept, hierarchyProp, toRemove);
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
                removeAllByPredicate(oldConcept, property, toRemove);
            }
            return;
        }

        String newURI = DataTypeConverter.isUri(newValue) ? newValue : uriGenerator.generateConceptURI(newValue, null);

        if (!Objects.equals(oldURI, newURI)) {
            removeAllByPredicate(oldConcept, property, toRemove);
            toAdd.add(model.createStatement(newConcept, property, model.createResource(newURI)));
        }
    }

    private void updateDataTypeRange(Resource newConcept, String dataType, Resource oldConcept,
                                      Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (dataType == null) return;

        String oldRangeURI = getResourceURI(oldConcept, RDFS.range);

        if (dataType.trim().isEmpty()) {
            if (oldRangeURI != null) {
                removeAllByPredicate(oldConcept, RDFS.range, toRemove);
            }
            return;
        }

        String newRangeURI = DataTypeConverter.getXSDTypeURI(dataType.trim());

        if (!Objects.equals(oldRangeURI, newRangeURI)) {
            removeAllByPredicate(oldConcept, RDFS.range, toRemove);
            toAdd.add(model.createStatement(newConcept, RDFS.range, model.createResource(newRangeURI)));
        }
    }

    private void updateSuperProperty(Resource newConcept, String superProperty, Resource oldConcept,
                                      Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (superProperty == null) return;

        String oldURI = getResourceURI(oldConcept, RDFS.subPropertyOf);

        if (superProperty.trim().isEmpty()) {
            if (oldURI != null) {
                removeAllByPredicate(oldConcept, RDFS.subPropertyOf, toRemove);
            }
            return;
        }

        String newURI = DataTypeConverter.isUri(superProperty) ? superProperty :
                        uriGenerator.generateConceptURI(superProperty, null);

        if (!Objects.equals(oldURI, newURI)) {
            removeAllByPredicate(oldConcept, RDFS.subPropertyOf, toRemove);
            toAdd.add(model.createStatement(newConcept, RDFS.subPropertyOf, model.createResource(newURI)));
        }
    }

    private void updateBooleanProperty(Resource newConcept, Boolean newValue,
                                       Resource oldConcept, Model model, Set<Statement> toRemove,
                                       Set<Statement> toAdd) {
        if (newValue == null) return;

        Property property = model.createProperty(uriGenerator.getEffectiveNamespace() + JE_PPDF);
        String oldValue = getPropertyValue(oldConcept, property);
        String newValueStr = newValue.toString();

        if (!Objects.equals(oldValue, newValueStr)) {
            removeAllByPredicate(oldConcept, property, toRemove);
            toAdd.add(model.createStatement(newConcept, property, model.createLiteral(newValueStr)));
        }
    }

    private void updateLegalSourceProperty(Resource newConcept, Property property, String newSource,
                                            Resource oldConcept, Model model, Set<Statement> toRemove,
                                            Set<Statement> toAdd) {
        if (newSource == null) return;

        String oldSourceURI = getResourceURI(oldConcept, property);

        if (newSource.trim().isEmpty()) {
            if (oldSourceURI != null) {
                removeAllByPredicate(oldConcept, property, toRemove);
            }
            return;
        }

        String newSourceURI = null;
        if (UtilityMethods.containsEliPattern(newSource)) {
            String eliPart = UtilityMethods.extractEliPart(newSource);
            if (eliPart != null) {
                newSourceURI = "https://opendata.eselpoint.cz/esel-esb/" + eliPart;
            }
        }

        if (!Objects.equals(oldSourceURI, newSourceURI)) {
            removeAllByPredicate(oldConcept, property, toRemove);
            if (newSourceURI != null) {
                toAdd.add(model.createStatement(newConcept, property, model.createResource(newSourceURI)));
            }
        }
    }

    private void updateNonLegalSourceProperty(Resource newConcept, Property property, String newSource,
                                               Resource oldConcept, Model model, Set<Statement> toRemove,
                                               Set<Statement> toAdd) {
        if (newSource == null) return;

        if (newSource.trim().isEmpty()) {
            removeAllByPredicate(oldConcept, property, toRemove);
            return;
        }

        removeAllByPredicate(oldConcept, property, toRemove);

        if (UtilityMethods.isValidUrl(newSource)) {
            String documentUri = uriGenerator.getEffectiveNamespace() + "digitální-dokument-" + System.currentTimeMillis();
            Resource digitalDocument = model.createResource(documentUri);
            Property schemaUrlProperty = model.createProperty("http://schema.org/url");

            toAdd.add(model.createStatement(digitalDocument, schemaUrlProperty, model.createResource(newSource)));
            toAdd.add(model.createStatement(newConcept, property, digitalDocument));
        }
    }

    private void renameConceptIRI(Model model, String oldIRI, String newIRI,
                                   Set<Statement> toRemove, Set<Statement> toAdd) {
        Resource oldConcept = model.getResource(oldIRI);

        StmtIterator iter = model.listStatements(oldConcept, null, (RDFNode) null);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            toRemove.add(stmt);
            toAdd.add(model.createStatement(
                    model.getResource(newIRI),
                    stmt.getPredicate(),
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

    private String getCurrentName(Resource concept) {
        Statement stmt = concept.getProperty(SKOS.prefLabel);
        return stmt != null && stmt.getObject().isLiteral() ? stmt.getObject().asLiteral().getString() : null;
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

    private void removeAllByPredicate(Resource resource, Property property, Set<Statement> toRemove) {
        StmtIterator iter = resource.listProperties(property);
        while (iter.hasNext()) {
            toRemove.add(iter.next());
        }
    }

    private Set<String> parseMultipleIRIs(String iris) {
        if (iris == null || iris.trim().isEmpty()) return Collections.emptySet();
        Set<String> result = new HashSet<>();
        for (String iri : iris.split(";")) {
            String trimmed = iri.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private Set<String> parseBroaderConcepts(String broaderConcept) {
        if (broaderConcept == null || broaderConcept.trim().isEmpty()) return Collections.emptySet();
        Set<String> result = new HashSet<>();
        for (String concept : broaderConcept.split(";")) {
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
